package com.dmujeres.traccar.mqtt

import android.content.Context
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.db.PendingPosition
import com.dmujeres.traccar.db.PositionDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    @Volatile var ready: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    private val ackFutures = ConcurrentHashMap<String, CompletableFuture<String>>()
    private val inFlightSequences = ConcurrentHashMap<String, Long>()
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val dispatchWake = Channel<Unit>(Channel.CONFLATED)
    private var dispatchJob: Job? = null
    private var subscriptionRetryJob: Job? = null
    @Volatile private var subscribed = false
    @Volatile private var connecting = false
    private val connectRetryScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Despierta el dispatcher después de insertar una posición en Room. */
    fun wakeDispatch() {
        dispatchWake.trySend(Unit)
        if (ready) startDispatch()
    }

    private fun notifyState(message: String) {
        runCatching { onStateChange(message) }
    }

    private fun errorText(error: Throwable?, fallback: String): String =
        error?.message?.takeIf { it.isNotBlank() }
            ?: error?.toString()?.takeIf { it.isNotBlank() }
            ?: fallback

    /** Reintenta la conexión inicial cada 30 s (Paho no auto-reconecta si el primer intento falla). */
    private fun scheduleConnectRetry() {
        if (!connectRetryScheduled.compareAndSet(false, true)) return
        scope.launch {
            while (scope.isActive && !connected && client != null) {
                delay(30_000)
                connecting = false
                connect()
            }
            connectRetryScheduled.set(false)
        }
    }

    private fun notifyDisconnected(error: String, message: String) {
        lastError = error
        MqttStatus.lastError = error
        MqttStatus.status = MqttStatus.DISCONNECTED
        notifyState(message)
        dispatchWake.trySend(Unit)
    }

    fun connect() {
        if (connecting) return
        if (client != null && connected) return
        connecting = true
        val server = config.serverUrl
        val deviceId = config.deviceId
        if (server.isBlank() || deviceId.isBlank()) {
            connecting = false
            connected = false
            ready = false
            subscribed = false
            notifyDisconnected("Falta servidor o usuario", "Falta configuración")
            return
        }

        val normalizedServer = try {
            normalizeServer(server).also { URI(it) }
        } catch (e: Exception) {
            val error = "Servidor inválido: $server"
            connected = false
            ready = false
            subscribed = false
            notifyDisconnected(error, error)
            return
        }
        val suffix = Integer.toHexString((System.nanoTime() % 0xFFFF).toInt()).padStart(4, '0')
        val clientId = "dmj-" + deviceId.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + "-" + suffix
        connected = false
        ready = false
        subscribed = false
        MqttStatus.status = MqttStatus.CONNECTING
        notifyState("Conectando al servidor...")

        runCatching { client?.disconnect() }
        val newClient = try {
            MqttAsyncClient(normalizedServer, clientId, MemoryPersistence())
        } catch (e: Exception) {
            connecting = false
            val error = errorText(e, "No se pudo crear el cliente MQTT")
            notifyDisconnected(error, "Error MQTT: $error")
            return
        }

        newClient.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String) {
                connecting = false
                if (client !== newClient) return
                connected = true
                ready = false
                subscribed = false
                lastError = null
                MqttStatus.lastError = null
                MqttStatus.status = MqttStatus.CONNECTED
                subscriptionRetryJob?.cancel()
                subscriptionRetryJob = null
                notifyState(if (reconnect) "Reconectado al servidor" else "Conectado al servidor")
                dispatchWake.trySend(Unit)
                subscribeAndDispatch()
            }

            override fun connectionLost(cause: Throwable?) {
                connecting = false
                if (client !== newClient) return
                connected = false
                ready = false
                subscribed = false
                val error = errorText(cause, "Conexión perdida")
                completeInFlightWithoutAck()
                notifyDisconnected(error, "Sin conexión: $error")
                scheduleConnectRetry()
            }

            override fun deliveryComplete(token: IMqttDeliveryToken) {
                // PUBACK del broker: no es la confirmación de negocio.
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                handleAck(message)
            }
        })
        client = newClient

        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            maxReconnectDelay = 10_000
            connectionTimeout = 10
            keepAliveInterval = 45
            isCleanSession = true
            if (config.username.isNotBlank()) {
                userName = config.username
                password = config.password.toCharArray()
            }
        }
        try {
            newClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    connecting = false
                    if (client !== newClient) return
                    connected = false
                    ready = false
                    subscribed = false
                    val error = errorText(exception, "Fallo de conexión")
                    completeInFlightWithoutAck()
                    notifyDisconnected(error, "Sin conexión: $error")
                    scheduleConnectRetry()
                }
            })
        } catch (e: Exception) {
            connecting = false
            if (client === newClient) {
                connected = false
                ready = false
                subscribed = false
                val error = errorText(e, "Fallo de conexión")
                notifyDisconnected(error, "Error MQTT: $error")
            }
        }
    }

    private fun subscribeAndDispatch() {
        val current = client ?: return
        if (!connected) return
        if (subscribed) {
            ready = true
            startDispatch()
            return
        }
        ready = false
        try {
            current.subscribe(config.ackTopic(), 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    if (client !== current || !connected) return
                    subscribed = true
                    ready = true
                    lastError = null
                    MqttStatus.lastError = null
                    MqttStatus.status = MqttStatus.CONNECTED
                    subscriptionRetryJob?.cancel()
                    subscriptionRetryJob = null
                    notifyState("Conexión lista para enviar")
                    startDispatch()
                    dispatchWake.trySend(Unit)
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    if (client !== current) return
                    subscribed = false
                    ready = false
                    val error = errorText(exception, "Fallo al suscribir el ACK")
                    notifyDisconnected(error, "No se pudo suscribir al ACK: $error")
                    scheduleSubscriptionRetry(current)
                }
            })
        } catch (e: Exception) {
            if (client !== current) return
            subscribed = false
            ready = false
            val error = errorText(e, "Fallo al suscribir el ACK")
            notifyDisconnected(error, "No se pudo suscribir al ACK: $error")
            scheduleSubscriptionRetry(current)
        }
    }

    private fun scheduleSubscriptionRetry(current: MqttAsyncClient) {
        if (subscriptionRetryJob?.isActive == true || !scope.isActive) return
        subscriptionRetryJob = scope.launch {
            delay(5_000)
            subscriptionRetryJob = null
            if (client === current && connected && !subscribed) {
                subscribeAndDispatch()
            }
        }
    }

    private fun startDispatch() {
        if (!ready || dispatchJob?.isActive == true) return
        dispatchJob = scope.launch { dispatchLoop() }
    }

    private suspend fun dispatchLoop() {
        while (scope.isActive) {
            if (!ready) {
                dispatchWake.receive()
                continue
            }

            val pending = withContext(Dispatchers.IO) { dao.allOrdered() }
            if (!ready) continue

            val now = System.currentTimeMillis()
            val next = pending.firstOrNull {
                val retry = retryAt[it.messageId]
                retry == null || retry <= now
            }
            if (next == null) {
                val nextRetryAt = pending.mapNotNull { retryAt[it.messageId] }.minOrNull()
                if (nextRetryAt == null) {
                    dispatchWake.receive()
                } else {
                    val waitMs = (nextRetryAt - System.currentTimeMillis()).coerceAtLeast(1L)
                    withTimeoutOrNull(waitMs) { dispatchWake.receive() }
                }
                continue
            }

            val status = publishWithAck(next)
            when (status) {
                "accepted", "duplicate", "rejected", "invalid", "expired" -> {
                    val deleted = withContext(Dispatchers.IO) { dao.delete(next.messageId) }
                    retryAt.remove(next.messageId)
                    if (deleted > 0 && (status == "accepted" || status == "duplicate")) {
                        recordConfirmedPosition(next)
                    }
                    if (status != "accepted" && status != "duplicate") {
                        notifyState("Mensaje rechazado por el servidor ($status): ${next.messageId}")
                    }
                }

                else -> {
                    // Sin ACK: backoff exponencial y continuar con el resto de la cola.
                    val attempts = next.attempts + 1
                    withContext(Dispatchers.IO) { dao.updateAttempts(next.messageId, attempts) }
                    val backoffMs = 5_000L * (1L shl minOf(attempts, 8))
                    retryAt[next.messageId] = System.currentTimeMillis() + backoffMs
                    if (attempts > config.maxRetries) {
                        withContext(Dispatchers.IO) { dao.delete(next.messageId) }
                        retryAt.remove(next.messageId)
                        notifyState("Mensaje descartado tras ${config.maxRetries} reintentos: ${next.messageId}")
                    }
                }
            }
            delay(200)
        }
    }

    private suspend fun publishWithAck(position: PendingPosition): String? {
        if (!ready) return null
        val future = CompletableFuture<String>()
        ackFutures[position.messageId] = future
        inFlightSequences[position.messageId] = position.sequence
        try {
            val current = client ?: return null
            if (!current.isConnected) return null
            val message = MqttMessage(position.payload.toByteArray(Charsets.UTF_8)).apply { qos = 1 }
            current.publish(config.telemetryTopic(), message)
            config.lastPublishedAt = System.currentTimeMillis()
            return withContext(Dispatchers.IO) {
                try {
                    future.get(config.ackTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = errorText(e, "Fallo al publicar")
            lastError = error
            MqttStatus.lastError = error
            val current = client
            if (current == null || !current.isConnected) {
                connected = false
                ready = false
                subscribed = false
                notifyDisconnected(error, "Sin conexión: $error")
            } else {
                ready = false
                subscribed = false
                notifyDisconnected(error, "Error MQTT al publicar: $error")
                scheduleSubscriptionRetry(current)
            }
            return null
        } finally {
            ackFutures.remove(position.messageId)
            inFlightSequences.remove(position.messageId)
        }
    }

    private fun recordConfirmedPosition(position: PendingPosition) {
        val type = runCatching { JSONObject(position.payload).optString("type") }.getOrDefault("")
        if (type == "position" && position.journeyId > 0L && position.journeyId == config.journeyStartAt) {
            config.recordJourneyConfirmed(position.journeyId)
        }
    }

    private fun completeInFlightWithoutAck() {
        ackFutures.values.forEach { it.complete("") }
    }

    private fun handleAck(message: MqttMessage) {
        try {
            val json = JSONObject(String(message.payload, Charsets.UTF_8))
            if (json.optInt("schema", -1) != 1 || json.optString("type") != "ack") return
            val messageId = json.optString("messageId")
            val deviceId = json.optString("deviceId")
            val sequence = json.optLong("sequence", -1L)
            val status = json.optString("status")
            val expectedSequence = inFlightSequences[messageId]
            if (messageId.isBlank() || deviceId != config.deviceId || expectedSequence == null
                || expectedSequence != sequence
                || status !in setOf("accepted", "duplicate", "rejected", "invalid", "expired")
            ) return
            config.lastAckAt = System.currentTimeMillis()
            ackFutures[messageId]?.complete(status)
        } catch (e: Exception) {
            // ACK ilegible: se ignora y el mensaje se reintentará.
        }
    }

    fun disconnect() {
        dispatchJob?.cancel()
        subscriptionRetryJob?.cancel()
        subscriptionRetryJob = null
        completeInFlightWithoutAck()
        inFlightSequences.clear()
        val oldClient = client
        client = null
        connected = false
        ready = false
        subscribed = false
        try {
            oldClient?.disconnect()
        } catch (e: Exception) {
            // ignorar al detener voluntariamente el servicio
        }
        MqttStatus.status = MqttStatus.DISCONNECTED
        dispatchWake.trySend(Unit)
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

        /**
         * Convierte la dirección a un formato que Paho entiende (tcp:// o ssl://).
         * Paho NO acepta mqtt:// (por eso fallaba). Añade puerto por defecto si falta.
         */
        fun normalizeServer(server: String): String {
            var value = server.trim().trimEnd('/')
            var secure = false
            if (value.startsWith("mqtts://")) { value = value.removePrefix("mqtts://"); secure = true }
            if (value.startsWith("ssl://")) { value = value.removePrefix("ssl://"); secure = true }
            if (value.startsWith("mqtt://")) value = value.removePrefix("mqtt://")
            if (value.startsWith("http://")) value = value.removePrefix("http://")
            if (value.startsWith("https://")) { value = value.removePrefix("https://"); secure = true }
            if (value.contains("://")) return value
            val hostPort = value.substringBefore('/')
            val hasPort = hostPort.contains(':')
            val defaultPort = if (secure) ":8883" else ":1883"
            return (if (secure) "ssl://" else "tcp://") + hostPort + if (hasPort) "" else defaultPort
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
