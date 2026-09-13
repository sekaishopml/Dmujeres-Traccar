package com.dmujeres.traccar.mqtt

import android.util.Log
import com.dmujeres.traccar.db.DeadLetter
import com.dmujeres.traccar.db.DispatchLock
import com.dmujeres.traccar.db.PendingPosition
import com.dmujeres.traccar.db.PositionDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Propietario único del dispatch de POSICIONES: HTTP es el transporte
 * principal (batch), MQTT queda para presence/heartbeat/telemetría.
 *
 * Antes MQTT (single-flight, 1 msg/ACK) y HTTP (lotes de 50) competían por la
 * misma cola; MQTT NO debe ser punto único de falla para posiciones: si MQTT
 * muere, GPS/Room/HTTP continúan y nada se pierde. El orden se mantiene FIFO
 * por sequence y solo el APPLICATION ACK autoriza el borrado.
 *
 * Éxito de transporte (HTTP 200) NO es confirmación: cada item necesita su
 * `status` de negocio. Reglas explícitas por estado:
 * - accepted/duplicate → DELETE local (el servidor ya la tiene).
 * - rejected/invalid/expired → CUARENTENA ([DeadLetter], nunca borrado
 *   silencioso: es evidencia de bug/deriva de contrato).
 * - pending/throttled/error/sin-respuesta → backoff exponencial con jitter y
 *   reintento (retención: sin ACK no se sabe si el servidor lo vio).
 * - Fallo de transporte (excepción, HTTP no-2xx) → NO se toca attempts: el
 *   próximo evento reintenta de inmediato en vez de esperar backoff.
 *
 * Las presencias (isControl) las envía MQTT en vivo; aquí solo se incluyen
 * cuando MQTT no entrega (`includePresence=true`) como plan B, nunca en
 * competencia: conjuntos disjuntos por llamada.
 *
 * WAKE (microbatch post-insert): [requestFlush] señala por un canal CONFLATED
 * y UNA sola corrutina ([startWakeLoop]) despacha un microbatch de
 * [MICROBATCH_MAX] tras [WAKE_DEBOUNCE_MS] de quietud. No crea dispatchers
 * concurrentes: el wake usa el mismo [flushOnce], que pasa por el
 * [DispatchLock.mutex] global (single-flight con MQTT). Sin wake en tránsito
 * el replay/drain grande queda intacto (lotes de [BATCH_SIZE], default).
 */
object PositionOutboxDispatcher {

    private const val TAG = "OutboxDispatcher"

    /** Posiciones por lote HTTP (50-100 de la propuesta; 50 probado en campo). */
    const val BATCH_SIZE = 50

    /** Contexto de envío (valores, no AppConfig: testeable en JVM). */
    data class DispatchContext(
        val webBaseUrl: String,
        val apiKey: String,
        val journeyStartAt: Long,
        /** Métrica de jornada confirmada (posiciones de la jornada actual). */
        val onConfirmedPosition: (PendingPosition) -> Unit = {},
        /** Métrica/alerta de cuarentena (NACK terminal preservado). */
        val onQuarantined: (DeadLetter) -> Unit = {},
    )

    /** Transporte HTTP inyectable (producción o fake en tests). */
    interface Transport {
        /** POST del lote. Lanza excepción si el transporte falla. */
        suspend fun post(ctx: DispatchContext, items: List<PendingPosition>): PostResult
    }

    /** Resultado del POST: estado de negocio por messageId + salud del transporte. */
    data class PostResult(
        /** messageId -> status (accepted|duplicate|rejected|invalid|expired|pending|throttled|error...). */
        val statuses: Map<String, String>,
        val transportOk: Boolean,
        val httpCode: Int = 0,
    )

    /** Transporte real: POST JSON al endpoint de lotes con timeouts acotados. */
    object HttpTransport : Transport {
        override suspend fun post(ctx: DispatchContext, items: List<PendingPosition>): PostResult {
            val requestBody = JSONArray()
            items.forEach { requestBody.put(JSONObject(it.payload)) }
            val connection = try {
                @Suppress("BlockingMethodInNonBlockingContext")
                (URL("${ctx.webBaseUrl}/api/mobile/v1/positions").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Api-Key", ctx.apiKey)
                    doOutput = true
                    outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "dispatch: sin transporte HTTP", e)
                throw e
            }
            return try {
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
                if (code !in 200..299) {
                    Log.w(TAG, "dispatch: HTTP $code (se conserva Room, backoff del llamador)")
                    return PostResult(emptyMap(), transportOk = false, httpCode = code)
                }
                val results = JSONObject(body).optJSONArray("results")
                val statuses = mutableMapOf<String, String>()
                if (results != null) {
                    for (i in 0 until results.length()) {
                        val item = results.optJSONObject(i) ?: continue
                        val messageId = item.optString("messageId")
                        if (messageId.isNotBlank()) {
                            statuses[messageId] = item.optString("status")
                        }
                    }
                }
                PostResult(statuses, transportOk = true, httpCode = code)
            } catch (e: Exception) {
                Log.w(TAG, "dispatch: respuesta ilegible, se conserva Room", e)
                throw e
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }

    /** Resultado de un flush: conteos para logs/métricas (nunca excepciones). */
    data class FlushOutcome(
        val confirmed: Int,
        val quarantined: Int,
        val retryScheduled: Int,
        val transportOk: Boolean,
    )

    /**
     * Envía UN lote FIFO de vencidos por HTTP. Nunca lanza: cualquier fallo se
     * refleja en [FlushOutcome] y Room queda intacto.
     *
     * @param includePresence incluye presencias vencidas (plan B cuando MQTT no
     * entrega). Con MQTT sano, las presencias las lleva MQTT en vivo.
     */
    suspend fun flushOnce(
        dao: PositionDao,
        transport: Transport,
        ctx: DispatchContext,
        nowMs: Long = System.currentTimeMillis(),
        batchSize: Int = BATCH_SIZE,
        includePresence: Boolean = true,
    ): FlushOutcome {
        return DispatchLock.mutex.withLock {
            flushLocked(dao, transport, ctx, nowMs, batchSize, includePresence)
        }
    }

    private suspend fun flushLocked(
        dao: PositionDao,
        transport: Transport,
        ctx: DispatchContext,
        nowMs: Long,
        batchSize: Int,
        includePresence: Boolean,
    ): FlushOutcome {
        // FIFO sobre VENCIDOS (respeta retryAt/backoff como el dispatch MQTT):
        // ORDER BY sequence ASC, solo retryAt cumplido.
        val pending = try {
            dao.allDue(nowMs, batchSize).filter { includePresence || !it.isControl }
        } catch (e: Exception) {
            return FlushOutcome(0, 0, 0, transportOk = true)
        }
        if (pending.isEmpty()) {
            return FlushOutcome(0, 0, 0, transportOk = true)
        }
        val result = try {
            transport.post(ctx, pending)
        } catch (e: Exception) {
            // Transporte caído a mitad de drain: se DETIENE el drain pero Room
            // queda intacto y attempts NO se toca (reintento inmediato próximo evento).
            return FlushOutcome(0, 0, 0, transportOk = false)
        }
        if (!result.transportOk) {
            return FlushOutcome(0, 0, 0, transportOk = false)
        }
        Log.d(TAG, "POSITION_DISPATCHED items=${pending.size} "
            + "firstSeq=${pending.firstOrNull()?.sequence} lastSeq=${pending.lastOrNull()?.sequence}")
        var confirmed = 0
        var quarantined = 0
        var retryScheduled = 0
        for (item in pending) {
            when (result.statuses[item.messageId]) {
                "accepted", "duplicate" -> {
                    val deleted = runCatching { dao.delete(item.messageId) }.getOrDefault(0)
                    if (deleted > 0) {
                        confirmed++
                        Log.d(TAG, "POSITION_ACKED seq=${item.sequence} "
                            + "journey=${item.journeyId} status=${result.statuses[item.messageId]}")
                        if ((result.statuses[item.messageId] == "accepted"
                                || result.statuses[item.messageId] == "duplicate")
                            && isCurrentJourneyPosition(ctx.journeyStartAt, item)
                        ) {
                            runCatching { ctx.onConfirmedPosition(item) }
                        }
                    }
                }
                "rejected", "invalid", "expired" -> {
                    // NACK terminal: a cuarentena con motivo, JAMÁS delete directo.
                    val reason = result.statuses[item.messageId] ?: "rejected"
                    if (quarantine(dao, item, reason, nowMs)) {
                        quarantined++
                        runCatching { ctx.onQuarantined(DeadLetter.fromPending(item, reason, nowMs)) }
                    }
                }
                else -> {
                    // pending/throttled/error/ausente: backoff y reintento.
                    val attempts = item.attempts + 1
                    val backoffMs = DispatchPolicy.dispatchBackoffMs(attempts)
                    runCatching {
                        dao.updateAttempts(item.messageId, attempts)
                        dao.updateRetryAt(item.messageId, nowMs + backoffMs)
                    }
                    retryScheduled++
                }
            }
        }
        return FlushOutcome(confirmed, quarantined, retryScheduled, transportOk = true)
    }

    /**
     * Traslada un pendiente a cuarentena (NACK terminal): preserva evidencia y
     * saca el mensaje del outbox en una transacción. Devuelve true si quedó
     * registrado (fila nueva o ya existente).
     */
    suspend fun quarantine(dao: PositionDao, item: PendingPosition, reason: String, nowMs: Long): Boolean {
        val dead = DeadLetter.fromPending(item, reason, nowMs)
        return runCatching {
            dao.moveToDeadLetter(dead)
            Log.w(TAG, "dispatch: cuarentena device=${item.deviceId} "
                + "seq=${item.sequence} reason=$reason id=${item.messageId}")
            true
        }.getOrDefault(false)
    }

    /**
     * ¿Es posición de la jornada actual? (para la métrica de confirmadas).
     * Pura y testeable: type==position && journeyId vigente.
     */
    fun isCurrentJourneyPosition(journeyStartAt: Long, item: PendingPosition): Boolean {
        if (journeyStartAt <= 0L || item.journeyId != journeyStartAt) {
            return false
        }
        return runCatching { JSONObject(item.payload).optString("type") == "position" }
            .getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // WAKE: microbatch post-insert (latencia online sin dispatchers extra)
    // ------------------------------------------------------------------

    /** Tope del microbatch que dispara el wake post-insert. */
    const val MICROBATCH_MAX = 5

    /**
     * Debounce del wake: ráfagas de inserciones colapsan en UN flush.
     * Corto a propósito (es latencia online; el drain grande sigue siendo
     * evento-driven con debounce de 10 s y el watchdog hace trickle cada 30 s).
     */
    const val WAKE_DEBOUNCE_MS = 1_200L

    /** Canal de señales de wake: CONFLATED colapsa ráfagas en una sola. */
    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)

    /** Idempotencia de [startWakeLoop]: un solo wakeLoop vivo a la vez. */
    private val wakeStarted = AtomicBoolean(false)

    /** Dependencias del wakeLoop (objeto sin constructor: se inyectan al start). */
    @Volatile private var wakeDao: PositionDao? = null
    @Volatile private var wakeTransport: Transport? = null
    @Volatile private var wakeCtxProvider: (() -> DispatchContext)? = null
    @Volatile private var wakeDebounceMs: Long = WAKE_DEBOUNCE_MS

    /**
     * Hook opcional: ¿MQTT está listo para llevar presencias en vivo?
     * TrackingService lo setea con `mqtt?.ready`. Convenio (mismo criterio que
     * el servicio usa hoy para `includePresence`): SOLO si el hook existe y
     * responde false se incluyen presencias en el flush de wake (plan B);
     * hook ausente o listo → solo posiciones (las presencias son de MQTT).
     */
    @Volatile var mqttReady: (() -> Boolean)? = null

    /**
     * Señala un flush inmediato post-insert. No-suspend, nunca lanza: desde
     * cualquier hilo. Varias señales seguidas colapsan en un solo flush
     * (CONFLATED + debounce).
     */
    fun requestFlush() {
        runCatching { wakeChannel.trySend(Unit) }
    }

    /**
     * Arranca UNA corrutina wakeLoop en [scope]. Idempotente: si ya corre,
     * no-op (AtomicBoolean); si el scope se canceló (ej. destroy del service),
     * el flag se libera y un start posterior relanza con las deps nuevas.
     * El ctx se pide por [ctxProvider] en CADA flush (TrackingService lo
     * construye fresco: journeyStartAt y callbacks cambian por jornada).
     *
     * @param wakeDebounceMs inyectable para tests (default [WAKE_DEBOUNCE_MS]).
     */
    fun startWakeLoop(
        scope: CoroutineScope,
        dao: PositionDao,
        transport: Transport,
        ctxProvider: () -> DispatchContext,
        wakeDebounceMs: Long = WAKE_DEBOUNCE_MS,
    ) {
        if (!wakeStarted.compareAndSet(false, true)) return
        wakeDao = dao
        wakeTransport = transport
        wakeCtxProvider = ctxProvider
        this.wakeDebounceMs = wakeDebounceMs
        scope.launch { wakeLoop() }.invokeOnCompletion { wakeStarted.set(false) }
    }

    /** Único consumidor del canal de wake: recibe → debounce → coalesce → flush. */
    private suspend fun wakeLoop() {
        while (true) {
            wakeChannel.receive()
            delay(wakeDebounceMs)
            // Coalescing: wakes llegados durante el debounce no generan flush extra.
            while (wakeChannel.tryReceive().isSuccess) Unit
            val dao = wakeDao ?: continue
            val transport = wakeTransport ?: continue
            val ctx = wakeCtxProvider?.invoke() ?: continue
            // Presencias solo si MQTT está EXPLÍCITAMENTE caído (plan B); sin
            // hook listo o ausente, el wake lleva solo posiciones.
            val includePresence = mqttReady?.invoke() == false
            try {
                flushOnce(
                    dao, transport, ctx,
                    batchSize = MICROBATCH_MAX,
                    includePresence = includePresence,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "wakeLoop: flush falló (el loop sigue vivo)", e)
            }
        }
    }
}
