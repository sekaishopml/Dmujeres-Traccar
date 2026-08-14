package com.dmujeres.traccar.mqtt

import android.content.Context
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.db.PendingPosition
import com.dmujeres.traccar.db.PositionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Cliente MQTT del canal móvil (QoS 1). La entrega se considera confirmada solo con el
 * ACK de aplicación del servidor (accepted/duplicate); sin ACK se reintenta más tarde.
 */
class MqttManager(
    private val context: Context,
    private val config: AppConfig,
    private val dao: PositionDao,
    private val scope: CoroutineScope,
    private val onStateChange: (String) -> Unit = {}
) {

    @Volatile private var client: MqttAsyncClient? = null
    @Volatile var connected: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    private val ackFutures = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val retryAt = ConcurrentHashMap<String, Long>()
    private var dispatchJob: Job? = null
    @Volatile private var subscribed = false

    private val messageCallback: (String) -> Unit = { message -> onStateChange(message) }

    fun connect() {
        val server = config.serverUrl
        val deviceId = config.deviceId
        if (server.isBlank() || deviceId.isBlank()) {
            lastError = "Falta servidor o usuario"
            MqttStatus.status = MqttStatus.DISCONNECTED
            messageCallback("Falta configuración")
            return
        }
        val uri = try { URI(normalizeServer(server)) } catch (e: Exception) {
            lastError = "Servidor inválido: $server"
            MqttStatus.status = MqttStatus.DISCONNECTED
            messageCallback("Servidor inválido")
            return
        }
        val clientId = "dmj-" + deviceId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        MqttStatus.status = MqttStatus.CONNECTING
        messageCallback("Conectando al servidor...")
        try {
            val newClient = MqttAsyncClient(normalizeServer(server), clientId, MemoryPersistence())
            newClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String) {
                    connected = true
                    lastError = null
                    MqttStatus.status = MqttStatus.CONNECTED
                    messageCallback("Conectado al servidor")
                    subscribeAndDispatch()
                }

                override fun connectionLost(cause: Throwable?) {
                    connected = false
                    subscribed = false
                    lastError = cause?.message ?: "Conexión perdida"
                    MqttStatus.status = MqttStatus.DISCONNECTED
                    messageCallback("Sin conexión: $lastError")
                }

                override fun deliveryComplete(token: IMqttDeliveryToken) {
                    // PUBACK del broker: no es la confirmación de negocio.
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    handleAck(message)
                }
            })

            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                maxReconnectDelay = 10_000
                connectionTimeout = 10
                isCleanSession = true
                if (config.username.isNotBlank()) {
                    userName = config.username
                    password = config.password.toCharArray()
                }
            }
            client = newClient
            newClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    connected = false
                    lastError = exception?.message ?: "Fallo de conexión"
                    MqttStatus.status = MqttStatus.DISCONNECTED
                    messageCallback("Sin conexión: $lastError")
                }
            })
        } catch (e: Exception) {
            lastError = e.message
            messageCallback("Error MQTT: ${e.message}")
        }
    }

    private fun subscribeAndDispatch() {
        val current = client ?: return
        if (subscribed) {
            startDispatch()
            return
        }
        try {
            current.subscribe(config.ackTopic(), 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    subscribed = true
                    startDispatch()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    lastError = exception?.message
                    messageCallback("No se pudo suscribir al ACK")
                }
            })
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    private fun startDispatch() {
        if (dispatchJob?.isActive == true) return
        dispatchJob = scope.launch { dispatchLoop() }
    }

    private suspend fun dispatchLoop() {
        while (scope.isActive && connected) {
            val now = System.currentTimeMillis()
            val next = dao.allOrdered().firstOrNull {
                val retry = retryAt[it.messageId]
                retry == null || retry <= now
            } ?: break
            val status = publishWithAck(next)
            when (status) {
                "accepted", "duplicate", "rejected", "invalid", "expired" -> {
                    withContext(Dispatchers.IO) { dao.delete(next.messageId) }
                    retryAt.remove(next.messageId)
                    if (status != "accepted" && status != "duplicate") {
                        messageCallback("Mensaje rechazado por el servidor ($status): ${next.messageId}")
                    }
                }
                else -> {
                    // Sin ACK: backoff exponencial y continuar con el resto de la cola.
                    val attempts = next.attempts + 1
                    withContext(Dispatchers.IO) { dao.updateAttempts(next.messageId, attempts) }
                    val backoffMs = 5_000L * (1L shl minOf(attempts, 8))
                    retryAt[next.messageId] = now + backoffMs
                    if (attempts > config.maxRetries) {
                        withContext(Dispatchers.IO) { dao.delete(next.messageId) }
                        retryAt.remove(next.messageId)
                        messageCallback("Mensaje descartado tras ${config.maxRetries} reintentos: ${next.messageId}")
                    }
                }
            }
            // Pequeña pausa entre mensajes para no saturar el canal.
            kotlinx.coroutines.delay(200)
        }
    }

    private suspend fun publishWithAck(position: PendingPosition): String? {
        val future = CompletableFuture<String>()
        ackFutures[position.messageId] = future
        try {
            val current = client ?: return null
            val message = MqttMessage(position.payload.toByteArray(Charsets.UTF_8)).apply { qos = 1 }
            current.publish(config.telemetryTopic(), message)
            return withContext(Dispatchers.IO) {
                try {
                    future.get(config.ackTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    null
                }
            }
        } catch (e: Exception) {
            lastError = e.message
            return null
        } finally {
            ackFutures.remove(position.messageId)
        }
    }

    private fun handleAck(message: MqttMessage) {
        try {
            val json = JSONObject(String(message.payload, Charsets.UTF_8))
            val messageId = json.optString("messageId")
            val status = json.optString("status")
            ackFutures[messageId]?.complete(status)
        } catch (e: Exception) {
            // ACK ilegible: se ignora y el mensaje se reintentará.
        }
    }

    fun disconnect() {
        dispatchJob?.cancel()
        ackFutures.keys.toList().forEach { ackFutures[it]?.complete("") }
        try {
            client?.disconnect()
        } catch (e: Exception) {
            // ignorar
        }
        client = null
        connected = false
        subscribed = false
        MqttStatus.status = MqttStatus.DISCONNECTED
    }
    companion object {
        /** Prueba la conexión con las credenciales dadas y devuelve un mensaje claro. */
        fun testConnection(
            server: String,
            username: String,
            password: String,
            onResult: (Boolean, String) -> Unit
        ) {
            if (server.isBlank() || username.isBlank()) {
                onResult(false, "Falta la dirección del servidor o el usuario")
                return
            }
            val clientId = "dmj-test-" + username.filter { it.isLetterOrDigit() }.take(16)
            val testClient = try {
                MqttAsyncClient(normalizeServer(server), clientId, MemoryPersistence())
            } catch (e: Exception) {
                onResult(false, "La dirección del servidor no es válida")
                return
            }
            val options = MqttConnectOptions().apply {
                connectionTimeout = 10
                isCleanSession = true
                isAutomaticReconnect = false
                if (username.isNotBlank()) {
                    userName = username
                    this.password = password.toCharArray()
                }
            }
            try {
                testClient.connect(options, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        runCatching { testClient.disconnect() }
                        onResult(true, "Conectado correctamente al servidor")
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        runCatching { testClient.disconnect() }
                        onResult(false, friendlyMqttError(exception?.message))
                    }
                })
            } catch (e: Exception) {
                onResult(false, "No se pudo conectar al servidor. Revisa Internet o la dirección.")
            }
        }

        /** Acepta direcciones escritas de cualquier forma y las convierte a mqtt://host:puerto. */
        fun normalizeServer(server: String): String {
            var value = server.trim()
            if (value.startsWith("http://")) value = value.removePrefix("http://")
            if (value.startsWith("https://")) value = value.removePrefix("https://")
            if (!value.contains("://")) value = "mqtt://$value"
            return value
        }

        private fun friendlyMqttError(raw: String?): String {
            val message = raw.orEmpty().lowercase()
            return when {
                message.contains("user name or password") || message.contains("bad_username") ->
                    "Usuario o contraseña incorrectos"
                message.contains("not authorized") -> "Acceso denegado para este usuario"
                message.contains("unable to connect") || message.contains("connection refused")
                    || message.contains("timed out") || message.contains("server unavailable")
                    || message.contains("timeout") ->
                    "No se pudo conectar al servidor. Revisa Internet o la dirección del servidor."
                else -> "Fallo de conexión: $raw"
            }
        }
    }
}
