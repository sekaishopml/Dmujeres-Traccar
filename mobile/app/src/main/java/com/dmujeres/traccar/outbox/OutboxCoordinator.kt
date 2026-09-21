package com.dmujeres.traccar.outbox

import android.util.Log
import com.dmujeres.traccar.data.BufferDrainPolicy
import com.dmujeres.traccar.data.PositionDao
import com.dmujeres.traccar.data.StopDrainPolicy
import com.dmujeres.traccar.outbox.PositionOutboxDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FASE 3 (§12-§15): coordinador del outbox extraído de `TrackingService` SIN
 * cambio de comportamiento:
 * - contexto del dispatcher HTTP-first (propietario único de posiciones),
 * - drenaje por lotes con single-flight + debounce + auto-encadenado,
 * - drenaje de cierre (deadline de [StopDrainPolicy]).
 *
 * Sigue siendo Room la fuente de verdad: este coordinador NUNCA borra; el
 * flush solo elimina lo confirmado, lo terminal va a cuarentena y lo no
 * confirmado reintenta con backoff.
 */
class OutboxCoordinator(
    /**
     * Contexto de dispatch fresco por drenaje (journeyId y callbacks cambian
     * por jornada). Inversión de dependencia: el coordinador no conoce
     * `AppConfig` ni el MQTT; el borde (TrackingService) los aporta.
     */
    private val dispatchContextProvider: () -> PositionOutboxDispatcher.DispatchContext,
    private val dao: () -> PositionDao,
    private val scopeProvider: () -> CoroutineScope,
    private val mqttReady: () -> Boolean,
    private val onHttpConfirmed: () -> Unit,
    /**
     * Frontera con el dispatcher: por defecto HTTP (comportamiento de
     * producción); inyectable en tests para caracterizar debounce/single-flight/
     * encadenado sin red. NO cambia la semántica: el flush real sigue siendo
     * [PositionOutboxDispatcher.flushOnce].
     */
    private val flush: suspend (PositionDao, PositionOutboxDispatcher.DispatchContext, Boolean) -> PositionOutboxDispatcher.FlushOutcome =
        { dao, ctx, includePresence ->
            PositionOutboxDispatcher.flushOnce(
                dao, PositionOutboxDispatcher.HttpTransport, ctx, includePresence = includePresence,
            )
        },
    /** Reloj inyectable (tests de debounce); producción = reloj del sistema. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Retardo del auto-encadenado (tests); producción = [DRAIN_CHAIN_DELAY_MS]. */
    private val chainDelayMs: Long = DRAIN_CHAIN_DELAY_MS,
) {

    /**
     * Single-flight del drenaje: un solo `drainBacklog` corre a la vez aunque
     * se acumulen eventos (red + MQTT + watchdog). El que llega tarde se omite:
     * el drenaje en curso ya vacía por lotes hasta que un lote confirma 0.
     */
    private val drainInProgress = AtomicBoolean(false)
    @Volatile private var lastDrainAt = 0L

    /** Contexto del dispatcher HTTP-first (mismo contrato que antes). */
    fun dispatchContext(): PositionOutboxDispatcher.DispatchContext = dispatchContextProvider()

    /**
     * Drena el outbox por lotes HTTP tras recuperar conexión. Nunca borra: el
     * flush solo elimina lo confirmado; lo terminal va a cuarentena; lo no
     * confirmado reintenta con backoff.
     */
    fun drainBacklog(reason: String) {
        val nowMs = now()
        if (nowMs - lastDrainAt < DRAIN_DEBOUNCE_MS) {
            Log.i(TAG, "Drenaje omitido ($reason): debounce")
            return
        }
        if (!drainInProgress.compareAndSet(false, true)) {
            Log.i(TAG, "Drenaje omitido ($reason): ya hay uno en curso")
            return
        }
        lastDrainAt = nowMs
        // Plan B para presencia: si MQTT no entrega, HTTP también barre los
        // controles vencidos (con MQTT sano los lleva MQTT en vivo).
        val includePresence = !mqttReady()
        val ctx = dispatchContext()
        scopeProvider().launch {
            try {
                var batches = 0
                var totalConfirmed = 0
                var totalQuarantined = 0
                var progress = 0
                do {
                    val outcome = flush(dao(), ctx, includePresence)
                    // Progreso = confirmadas + cuarentenadas (ambas vacían outbox).
                    progress = outcome.confirmed + outcome.quarantined
                    totalConfirmed += outcome.confirmed
                    totalQuarantined += outcome.quarantined
                    batches++
                    if (!outcome.transportOk) break
                } while (BufferDrainPolicy.continueDraining(progress, batches))
                Log.i(TAG, "Drenaje ($reason): $batches lotes, confirmed=$totalConfirmed "
                    + "quarantined=$totalQuarantined")
                if (totalConfirmed > 0) {
                    runCatching { onHttpConfirmed() }
                    // Anti-hambre (Fase 10): si quedó backlog tras un lote de
                    // progreso, se AUTO-ENCADENA el siguiente drain ~10 s
                    // después (el debounce lo admite). Si el transporte falla
                    // o el outbox vacía, la cadena termina sola: solo se
                    // encadena cuando CONFIRMÓ algo.
                    val pendingLeft = runCatching {
                        withContext(Dispatchers.IO) { dao().countFlow().first() }
                    }.getOrDefault(0)
                    if (pendingLeft > 0) {
                        scopeProvider().launch {
                            delay(chainDelayMs)
                            drainBacklog("continuación")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Drenaje post-reconexión ($reason)", e)
            } finally {
                drainInProgress.set(false)
            }
        }
    }

    /**
     * Drenaje de cierre: al finalizar NO se borra la cola; se drena por HTTP
     * hasta vaciarla o hasta el deadline. Lo que quede sigue en Room y lo
     * drena TrackingRecoveryWorker; nunca se descarta.
     */
    suspend fun flushPendingOnStop() {
        val ctx = dispatchContext()
        val deadline = now() + StopDrainPolicy.TIMEOUT_MS
        while (!StopDrainPolicy.timedOut(now(), deadline)) {
            val pending = withContext(Dispatchers.IO) { dao().count() }
            if (pending == 0) return
            val outcome = flush(dao(), ctx, true)
            delay(StopDrainPolicy.retryDelayAfter(outcome.confirmed + outcome.quarantined))
        }
    }

    companion object {
        private const val TAG = "OutboxCoordinator"

        /**
         * Debounce entre drenajes del outbox: `onAvailable` puede flapear en
         * handovers WiFi↔datos y además dispara `onMqttStateChanged(CONNECTED)`;
         * sin esto el mismo backlog se drenaba 2-3 veces en segundos.
         */
        const val DRAIN_DEBOUNCE_MS = 10_000L
        const val DRAIN_CHAIN_DELAY_MS = 10_500L
    }
}
