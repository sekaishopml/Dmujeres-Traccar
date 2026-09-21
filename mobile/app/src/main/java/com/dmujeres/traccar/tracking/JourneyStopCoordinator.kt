package com.dmujeres.traccar.tracking

import com.dmujeres.traccar.transport.MqttManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * FASE R3: secuencia de cierre de jornada extraída de `TrackingService` SIN
 * cambio de comportamiento ni de orden:
 *
 *   1. drenar el outbox (HTTP, hasta deadline de StopDrainPolicy),
 *   2. encolar la presencia "ended" y esperarla,
 *   3. drenar de nuevo (entrega la señal por HTTP si MQTT no confirmó),
 *   4. esperar [finishDelayMs] (3 s) y avisar por [onFinished].
 *
 * Los lambdas inyectables permiten congelar el orden en JVM
 * (`JourneyStopCoordinatorTest`); en producción el servicio aporta outbox,
 * presencia y `finishStopping`.
 */
class JourneyStopCoordinator(
    private val controllerScope: CoroutineScope,
    private val flushPendingOnStop: suspend () -> Unit,
    private val enqueueEnded: (CoroutineScope, MqttManager?) -> Job,
    private val onFinished: (CoroutineScope, MqttManager?) -> Unit,
    private val finishDelayMs: Long = FINISH_DELAY_MS,
    /** Dispatchers.Main en producción; inyectable para tests JVM. */
    private val finishDispatcher: CoroutineContext = Dispatchers.Main,
) {

    fun begin(closingScope: CoroutineScope, closingMqtt: MqttManager?): Job = controllerScope.launch {
        runCatching { flushPendingOnStop() }

        // Se encola después del primer drenaje para que la señal ended informe
        // pending=0 cuando la cola pudo vaciarse. El segundo drenaje entrega la
        // señal por HTTP si MQTT sigue sin confirmar.
        val endedJob = enqueueEnded(closingScope, closingMqtt)
        runCatching { endedJob.join() }
        runCatching { flushPendingOnStop() }
        delay(finishDelayMs)
        withContext(finishDispatcher) {
            onFinished(closingScope, closingMqtt)
        }
    }

    companion object {
        const val FINISH_DELAY_MS = 3_000L
    }
}
