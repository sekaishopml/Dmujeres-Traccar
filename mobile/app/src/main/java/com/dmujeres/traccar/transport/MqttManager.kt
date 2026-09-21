package com.dmujeres.traccar.transport

import com.dmujeres.traccar.core.MqttStatus
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.core.DispatchLock
import com.dmujeres.traccar.core.MqttServerNormalizer
import com.dmujeres.traccar.core.RttMeter
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
import kotlinx.coroutines.sync.withLock
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
    /**
     * R7/R11: puerto de persistencia de la cola de control; `transport` no
     * conoce Room. El dueño de la cola (outbox) lo implementa.
     */
    private val store: ControlQueueStore,
    private val scope: CoroutineScope,
    private val onStateChange: (String) -> Unit = {},
    /** Aviso cuando una presencia cae en cuarentena (NACK terminal). */
    private val onQuarantined: (messageId: String) -> Unit = {},
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
    /** elapsedRealtime de cuándo entró `connecting=true` (watchdog de cuña). */
    @Volatile private var connectingStartedElapsedMs = 0L

    /** R3.5-J: hubo una pérdida de conexión; el próximo connect exitoso cuenta
     *  como reconexión real (la métrica no debe morir con autoReconnect=false). */
    @Volatile private var pendingReconnect = false
    /**
     * Puerta única de reconexión ([ReconnectGate]): TODOS los disparadores
     * (watchdog, onAvailable, connectionLost, onFailure) pasan por `connect()`
     * y su cadencia. Sin bucles infinitos paralelos: cada fallo programa UN
     * solo reintento (cancelable); el watchdog re-toca la puerta cada 30 s.
     */
    @Volatile private var connectAttempts = 0
    @Volatile private var lastConnectAttemptAt = 0L
    @Volatile private var nextConnectAllowedAt = 0L
    @Volatile private var connectGeneration = 0
    private var connectRetryJob: Job? = null

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

    /**
     * Programa UN solo reintento con backoff exponencial + jitter
     * ([ReconnectGate.connectDelayMs]): lo cancela cualquier intento nuevo,
     * un éxito o un `disconnect()` voluntario. No hay bucle `while`: la cadena
     * avanza fallo → 1 tiro programado → fallo → … con techo 5 min, y el
     * watchdog es el otro único emisor (también tras la puerta).
     */
    private fun scheduleConnectRetry() {
        connectRetryJob?.cancel()
        val generation = connectGeneration
        val delayMs = ReconnectGate.connectDelayMs(connectAttempts)
        nextConnectAllowedAt = System.currentTimeMillis() + delayMs
        connectRetryJob = scope.launch {
            delay(delayMs)
            connectRetryJob = null
            if (connectGeneration != generation || !scope.isActive) return@launch
            if (connected) {
                connectAttempts = 0
                return@launch
            }
            connecting = false
            connect()
        }
    }

    private fun notifyDisconnected(error: String, message: String) {
        lastError = error
        MqttStatus.lastError = error
        MqttStatus.status = MqttStatus.DISCONNECTED
        notifyState(message)
        dispatchWake.trySend(Unit)
    }

    /**
     * Único punto de entrada para (re)conectar. La [ReconnectGate] limita a 1
     * intento por ventana aunque la red flapee y varios emisores pidan a la
     * vez (watchdog 30 s + onAvailable + reintento programado).
     *
     * El guard es atómico (monitor de instancia): check connecting/connected →
     * crear cliente → conectar corre TODO bajo el mismo lock, así dos
     * llamadores concurrentes (main + Dispatchers.Default) producen UN cliente,
     * no dos.
     *
     * @param immediate vía rápida para vuelta de red VALIDADA: solo debounce
     * 2 s, sin esperar el backoff (el usuario volvió a tener Internet, no hay
     * que castigarlo con la espera del outage).
     */
    fun connect(immediate: Boolean = false) {
        synchronized(this) {
            connectLocked(immediate)
        }
    }

    /**
     * Cuña anti-colgado: si Paho nunca invocó onSuccess/onFailure (socket
     * colgado pese a connectionTimeout), `connecting` quedaría true para
     * siempre y TODOS los connect() posteriores serían no-op. Un connect()
     * posterior que encuentra el guard tomado desde hace más de
     * [StaleConnectingPolicy.CONNECTION_WATCHDOG_MS] lo libera y procede con
     * un intento nuevo (el cliente viejo se cierra como ya hace el cuerpo).
     */
    private fun clearStaleConnecting() {
        if (StaleConnectingPolicy.clearIfStale(
                connecting, connectingStartedElapsedMs, SystemClock.elapsedRealtime(),
            )
        ) {
            Log.w(TAG, "connect: guard 'connecting' colgado >"
                + "${StaleConnectingPolicy.CONNECTION_WATCHDOG_MS}ms, se libera y reintenta")
            connecting = false
            connectingStartedElapsedMs = 0L
        }
    }

    private fun connectLocked(immediate: Boolean) {
        val now = System.currentTimeMillis()
        clearStaleConnecting()
        if (connecting) return
        if (client != null && connected) return
        if (immediate) {
            if (!ReconnectGate.shouldAttemptImmediate(now, lastConnectAttemptAt)) return
        } else {
            if (!ReconnectGate.shouldAttempt(now, nextConnectAllowedAt, lastConnectAttemptAt)) return
        }
        lastConnectAttemptAt = now
        connectGeneration++
        connectRetryJob?.cancel()
        connectRetryJob = null
        connecting = true
        connectingStartedElapsedMs = SystemClock.elapsedRealtime()
        val server = config.serverUrl
        val deviceId = config.deviceId
        if (server.isBlank() || deviceId.isBlank()) {
            connecting = false
            connectingStartedElapsedMs = 0L
            connected = false
            ready = false
            subscribed = false
            notifyDisconnected("Falta servidor o usuario", "Falta configuración")
            return
        }

        val normalizedServer = try {
            MqttServerNormalizer.normalizeServer(server).also { URI(it) }
        } catch (e: Exception) {
            val error = "Servidor inválido: $server"
            connecting = false
            connectingStartedElapsedMs = 0L
            connected = false
            ready = false
            subscribed = false
            notifyDisconnected(error, error)
            return
        }
        // Sufijo ESTABLE por instalación: con uno aleatorio cada reconexión creaba
        // sesiones nuevas en EMQX que nadie cerraba (decenas de zombies del mismo
        // teléfono acumulando colas QoS1 de acks). Mismo clientId => el broker hace
        // takeover de la sesión anterior en vez de dejarla colgada.
        val suffix = config.mqttClientSuffix.ifBlank {
            randomSuffix().also { config.mqttClientSuffix = it }
        }
        val clientId = "dmj-" + deviceId.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + "-" + suffix
        connected = false
        ready = false
        subscribed = false
        MqttStatus.status = MqttStatus.CONNECTING
        notifyState("Conectando al servidor...")

        // disconnect() deja el objeto vivo con automaticReconnect; hay que cerrarlo
        // forzosamente para que no siga reconectando instancias antiguas en paralelo.
        client?.let { stale ->
            runCatching { stale.disconnect() }
            runCatching { stale.close(true) }
        }
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
                connectAttempts = 0
                nextConnectAllowedAt = 0L
                connectRetryJob?.cancel()
                connectRetryJob = null
                if (reconnect || pendingReconnect) {
                    // La reconexión propia (ReconnectGate → connect()) llega como
                    // reconnect=false: contamos también esa ruta real, no solo el
                    // auto-reconnect de Paho (deshabilitado por diseño).
                    runCatching { config.incReconnect24h() }
                    pendingReconnect = false
                }
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
                pendingReconnect = true
                connectAttempts = (connectAttempts + 1).coerceAtMost(ReconnectGate.MAX_ATTEMPTS)
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

        // LWT (Last Will and Testament): si el TCP se corta inesperadamente, el broker
        // publica automáticamente este mensaje. El servidor recibe network="lost" y crea
        // el evento mobileNetworkLost en segundos (no hay que esperar el timeout de silencio).
        val willPayload = org.json.JSONObject().apply {
            put("schema", 1)
            put("type", "presence")
            put("messageId", "lwt-" + System.currentTimeMillis())
            put("deviceId", deviceId)
            put("sequence", 0)
            put("sentAt", java.time.Instant.now().toString())
            put("observedAt", java.time.Instant.now().toString())
            put("payload", org.json.JSONObject().apply {
                put("network", "none")
                put("gps", "unknown")
                put("battery", -1)
                put("pending", 0)
            })
        }.toString().toByteArray(Charsets.UTF_8)

        val options = MqttConnectOptions().apply {
            // Dueño único de la cadencia: la app (ReconnectGate). Paho NO
            // reconecta solo: su retry peleaba con el nuestro (cada connect()
            // cierra el cliente anterior y mataba el retry de Paho a mitad).
            isAutomaticReconnect = false
            connectionTimeout = 10
            // 300 s (antes 45): bajo Doze el TimerTask de Paho no corre (los
            // PINGREQ no salen con pantalla apagada) y el broker corta a
            // 1.5×keepalive: con 45 s (~67 s) el Galaxy A52 acumulaba ~163
            // reconexiones/24 h (docs/audit/OS_DEVICE_SAMSUNG_A52.md). 300 s da
            // margen holgado; la cadencia de reconexión (ReconnectGate) no cambia.
            keepAliveInterval = KEEP_ALIVE_SECONDS.toInt()
            isCleanSession = true
            setWill(config.telemetryTopic(), willPayload, 1, false)
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
                    connectAttempts = (connectAttempts + 1).coerceAtMost(ReconnectGate.MAX_ATTEMPTS)
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

    // MQTT es el canal de PRESENCIA (heartbeat/started/ended), NO de posiciones:
    // las posiciones las entrega PositionOutboxDispatcher por HTTP en lotes, así
    // que MQTT nunca es punto único de falla para la ruta. Este loop solo toma
    // controles vencidos (dueControls); el throughput single-flight sobra para
    // el volumen de presencia. Ver informe de arquitectura HTTP-first.
    private suspend fun dispatchLoop() {
        while (scope.isActive) {
            if (!ready) {
                dispatchWake.receive()
                continue
            }

            val now = System.currentTimeMillis()
            // Solo presencia: lote acotado de controles vencidos (nunca toda la
            // tabla; las posiciones las drena HTTP por otro camino disjunto).
            val batch: List<ControlItem> = try {
                withContext(Dispatchers.IO) {
                    store.dueControls(now, DISPATCH_BATCH_SIZE)
                }
            } catch (e: Exception) {
                // R8: sin log, una DB caída se veía como "sin controles".
                com.dmujeres.traccar.platform.SentryLog.breadcrumb("mqtt", "db_error_dueControls", e.message ?: e.javaClass.simpleName)
                emptyList()
            }
            if (!ready) continue

            // Fase A: restaura map desde el lote (persistencia de backoff) sin tabla completa.
            batch.forEach { p ->
                if (p.retryAt > 0L && p.retryAt > now) {
                    retryAt[p.messageId] = p.retryAt
                }
            }
            // FIFO estricto por sequence entre vencidos (ver DispatchPolicy): el
            // replay sale completo y en orden; `ended` tiene la sequence mayor y
            // sale el último, tras drenar las posiciones.
            val items = batch.map {
                DispatchPolicy.QueueItem(
                    messageId = it.messageId,
                    sequence = it.sequence,
                    retryAtDb = it.retryAt,
                    retryAtMem = retryAt[it.messageId],
                    isControl = it.isControl,
                )
            }
            val nextId = DispatchPolicy.selectNext(items, now)?.messageId
            val next = nextId?.let { id -> batch.firstOrNull { it.messageId == id } }
            if (next == null) {
                // Semántica de espera: query ligera MIN(retryAt futuro) + mem + lote.
                val waitNow = System.currentTimeMillis()
                val nextRetryAt: Long? = try {
                    val dbFuture = withContext(Dispatchers.IO) {
                        store.minFutureRetryAt(waitNow)
                    }?.takeIf { it > waitNow }
                    val memFuture = retryAt.values.filter { it > waitNow }.minOrNull()
                    val batchFuture = DispatchPolicy.nextRetryAt(items, waitNow)
                    listOfNotNull(dbFuture, memFuture, batchFuture).minOrNull()
                } catch (_: Exception) {
                    DispatchPolicy.nextRetryAt(items, waitNow)
                }
                if (nextRetryAt == null) {
                    dispatchWake.receive()
                } else {
                    val waitMs = (nextRetryAt - System.currentTimeMillis()).coerceAtLeast(1L)
                    withTimeoutOrNull(waitMs) { dispatchWake.receive() }
                }
                continue
            }

            // Single-flight con el dispatcher HTTP: publish/delete/update bajo el mismo
            // Mutex para no pisar el mismo messageId. withLock es suspend (sin deadlock).
            DispatchLock.mutex.withLock {
                val status = publishWithAck(next)
                when (status) {
                    "accepted", "duplicate" -> {
                        val deleted = withContext(Dispatchers.IO) { store.delete(next.messageId) }
                        retryAt.remove(next.messageId)
                        if (deleted > 0) {
                            recordConfirmedPosition(next)
                        }
                    }
                    "rejected", "invalid", "expired" -> {
                        // NACK terminal: a cuarentena con motivo, JAMÁS delete
                        // directo (evidencia de bug/deriva de contrato).
                        val nowMs = System.currentTimeMillis()
                        val moved = withContext(Dispatchers.IO) {
                            store.quarantine(next, status, nowMs)
                        }
                        retryAt.remove(next.messageId)
                        if (moved) {
                            runCatching { onQuarantined(next.messageId) }
                        }
                        notifyState("Mensaje rechazado por el servidor ($status): ${next.messageId}")
                    }

                    else -> {
                        // Sin ACK: backoff exponencial con jitter y RETENCIÓN infinita.
                        // Ruta sin pérdida: sin ACK no se sabe si el servidor lo vio,
                        // así que el mensaje NO se borra por agotar reintentos (ver
                        // DispatchPolicy.shouldDiscardUnacked); un NACK explícito
                        // (rejected/invalid/expired) va a CUARENTENA, nunca a borrado.
                        val attempts = next.attempts + 1
                        val backoffMs = DispatchPolicy.dispatchBackoffMs(attempts)
                        val nextRetryAt = System.currentTimeMillis() + backoffMs
                        withContext(Dispatchers.IO) {
                            store.updateAttempts(next.messageId, attempts)
                            store.updateRetryAt(next.messageId, nextRetryAt)
                        }
                        retryAt[next.messageId] = nextRetryAt
                        if (DispatchPolicy.shouldDiscardUnacked(next.isControl, attempts, config.maxRetries)) {
                            withContext(Dispatchers.IO) { store.delete(next.messageId) }
                            retryAt.remove(next.messageId)
                            notifyState("Mensaje descartado tras ${config.maxRetries} reintentos: ${next.messageId}")
                        }
                    }
                }
            }
            delay(200)
        }
    }

    private suspend fun publishWithAck(position: ControlItem): String? {
        if (!ready) return null
        val future = CompletableFuture<String>()
        ackFutures[position.messageId] = future
        inFlightSequences[position.messageId] = position.sequence
        try {
            val current = client ?: return null
            if (!current.isConnected) return null
            val message = MqttMessage(position.payload.toByteArray(Charsets.UTF_8)).apply { qos = 1 }
            val publishStartedAt = System.currentTimeMillis()
            current.publish(config.telemetryTopic(), message)
            config.lastPublishedAt = System.currentTimeMillis()
            return withContext(Dispatchers.IO) {
                try {
                    val status = future.get(config.ackTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                    if (status.isNotEmpty()) {
                        // RTT de aplicación: publish → ACK del servidor. Un "" es el complete()
                        // de desconexión (sin ACK real) y no debe contaminar la media.
                        config.mobileRttMs = RttMeter.update(System.currentTimeMillis() - publishStartedAt).toInt()
                    }
                    status.ifEmpty { null }
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

    private fun recordConfirmedPosition(position: ControlItem) {
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
            // serverReceivedAt (opcional, MobileMqttConsumer): solo depuración; el RTT de
            // referencia es el medido localmente en publishWithAck.
            val serverReceivedAt = json.optString("serverReceivedAt", "")
            if (serverReceivedAt.isNotEmpty()) {
                Log.d(TAG, "ACK messageId=$messageId status=$status serverReceivedAt=$serverReceivedAt")
            }
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
        connectRetryJob?.cancel()
        connectRetryJob = null
        connectAttempts = 0
        nextConnectAllowedAt = 0L
        connectGeneration++
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
        // Cierre forzoso: sin esto el automaticReconnect revive la instancia tras
        // el disconnect y deja conexiones/hilos huérfanos.
        runCatching { oldClient?.close(true) }
        MqttStatus.status = MqttStatus.DISCONNECTED
        dispatchWake.trySend(Unit)
    }

    companion object {
        private const val TAG = "MqttManager"

        /**
         * Keepalive MQTT. Doze suspende el TimerTask de Paho (no salen PINGREQ
         * con pantalla apagada) y el broker cierra la sesión a 1.5×keepalive;
         * con 45 s (~67 s) eso daba ~163 reconexiones/24 h en Galaxy A52
         * (docs/audit/OS_DEVICE_SAMSUNG_A52.md). 300 s sigue la recomendación de
         * la investigación y no toca la cadencia de [ReconnectGate].
         */
        const val KEEP_ALIVE_SECONDS = 300L

        /** Timeout del login de prueba: sin esto un broker colgado deja el login colgado. */
        const val TEST_CONNECTION_TIMEOUT_MS = 12_000L

        /** Lote acotado del dispatch paginado (evita O(N²)/OOM con 10k). */
        const val DISPATCH_BATCH_SIZE = 100

        /** Sufijo aleatorio anti-colisión, igual que connect() (~línea 125 original). */
        fun randomSuffix(): String =
            Integer.toHexString((System.nanoTime() % 0xFFFF).toInt()).padStart(4, '0')

        /** ClientId de prueba con sufijo aleatorio (testeable con sufijo fijo). */
        fun buildTestClientId(username: String, suffix: String = randomSuffix()): String =
            "dmj-test-" + username.filter { it.isLetterOrDigit() }.take(16) + "-" + suffix

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
            val clientId = buildTestClientId(username)
            val testClient = try {
                MqttAsyncClient(MqttServerNormalizer.normalizeServer(server), clientId, MemoryPersistence())
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
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            fun finish(ok: Boolean, message: String) {
                if (done.compareAndSet(false, true)) {
                    // disconnect() es async en Paho; close() libera hilos en finally de cada rama.
                    runCatching { testClient.disconnect() }
                    runCatching { testClient.close() }
                    runCatching { onResult(ok, message) }
                }
            }
            // Watchdog 12 s: fuerza onFailure aunque Paho nunca responda (login colgado).
            val watchdog = Thread({
                try {
                    Thread.sleep(TEST_CONNECTION_TIMEOUT_MS)
                    finish(false, "No se pudo conectar al servidor. Revisa Internet o la dirección del servidor. (tiempo de espera agotado)")
                } catch (_: InterruptedException) {
                    // test ya terminado
                }
            }, "mqtt-test-timeout")
            watchdog.isDaemon = true
            watchdog.start()
            try {
                testClient.connect(options, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        finish(true, "Conectado correctamente al servidor")
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        finish(false, friendlyMqttError(exception?.message))
                    }
                })
            } catch (e: Exception) {
                finish(false, "No se pudo conectar al servidor. Revisa Internet o la dirección.")
            }
        }

        /**
         * Convierte la dirección a un formato que Paho entiende (tcp:// o ssl://).
         * Paho NO acepta mqtt:// (por eso fallaba). Añade puerto por defecto si falta.
         * @deprecated Usar [MqttServerNormalizer.normalizeServer] directamente.
         */
        fun normalizeServer(server: String): String =
            MqttServerNormalizer.normalizeServer(server)

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

/**
 * Política pura del watchdog de conexión colgada (JVM-testeable): ¿está el
 * guard `connecting` tomado desde hace más de [CONNECTION_WATCHDOG_MS]?
 * `startedElapsedMs <= 0` (sin marca) nunca se considera colgado.
 */
object StaleConnectingPolicy {

    /** Paho connectionTimeout=10 s; margen hasta 15 s para declarar colgado. */
    const val CONNECTION_WATCHDOG_MS = 15_000L

    /**
     * @param connecting guard actual.
     * @param startedElapsedMs elapsedRealtime al entrar en connecting (0 = sin marca).
     * @param nowElapsedMs elapsedRealtime actual.
     * @return true si hay que liberar el guard (connecting=false) y reintentar.
     */
    fun clearIfStale(
        connecting: Boolean,
        startedElapsedMs: Long,
        nowElapsedMs: Long,
        watchdogMs: Long = CONNECTION_WATCHDOG_MS,
    ): Boolean {
        if (!connecting) return false
        if (startedElapsedMs <= 0L) return false
        return nowElapsedMs - startedElapsedMs > watchdogMs
    }
}
