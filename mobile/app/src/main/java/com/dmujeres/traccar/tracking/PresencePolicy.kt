package com.dmujeres.traccar.tracking

import com.dmujeres.traccar.core.MobileProtocol

/**
 * Condición de encolado de presencia, extraída del bloque original de
 * `enqueuePresence` como política pura (JVM). Semántica EXACTA:
 *
 * - Señal normal (`journeyStatus == null`): solo con tracking arrancado y sin
 *   cierre en curso.
 * - Señal `"started"`: solo exige que no haya un cierre en curso (la emite el
 *   propio arranque, donde `started` ya es true).
 * - Señal `"ended"`/cualquier otra: siempre (la emite el cierre y debe salir
 *   aunque `started` ya sea false y `stopping` sea true).
 */
object PresencePolicy {

    fun shouldEnqueue(journeyStatus: String?, started: Boolean, stopping: Boolean): Boolean =
        when (journeyStatus) {
            null -> started && !stopping
            MobileProtocol.JOURNEY_STATUS_STARTED -> !stopping
            else -> true
        }
}
