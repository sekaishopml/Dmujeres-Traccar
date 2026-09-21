package com.dmujeres.traccar.location

/**
 * R5: máquina de estados PURA del rescate de localización (1.1.7).
 *
 * Problema: con jornada activa, el GPS puede dejar de entregar fixes mientras
 * el teléfono sigue en movimiento. Ya existe la FUSIÓN de evidencia
 * ([LocationSensorFusion]: GPS_HEALTH/DEGRADED/LOST + MOVEMENT_LIKELY, nunca
 * coordenadas) pero nadie ACTIVA una reacquisición agresiva temporal.
 *
 * Esta política decide CUÁNDO entrar en RESCUE y cuándo salir, con:
 *  - single-flight y cooldown (el controlador las aplica),
 *  - burst acotado ([RESCUE_DURATION_MS]),
 *  - GPS_LOST + STATIONARY sin burst infinito,
 *  - GPS_LOST + MOVEMENT_LIKELY → RESCUE.
 *
 * Reglas dures: los sensores NUNCA producen coordenadas; solo autorizan
 * re-intentar GNSS. Todos los umbrales provienen de los valores ya calibrados
 * del proyecto ([LocationSensorFusion], [MovementStartPolicy]).
 */
object MovementRescuePolicy {

    /** Fix fresco (misma ventana de la fusión). */
    const val FRESH_FIX_MS = LocationSensorFusion.FRESH_FIX_MS // 60 s

    /** Fix obsoleto = GPS_DEGRADED (misma ventana de la fusión: 5 min). */
    const val STALE_FIX_MS = LocationSensorFusion.STALE_FIX_MS // 5 min

    /** Segunda evidencia consecutiva para pasar de CANDIDATE a RESCUE. */
    const val CONFIRM_EVIDENCE_SAMPLES = 2

    /** Duración máxima de la ventana de rescate (burst de reacquisición). */
    const val RESCUE_DURATION_MS = 90_000L

    /** Cooldown entre rescates (batería + anti-loop). */
    const val RESCUE_COOLDOWN_MS = 5 * 60_000L

    enum class RescueState { NORMAL, MOVEMENT_CANDIDATE, GPS_DEGRADED, GPS_LOST, RESCUE, FIX_RECOVERED }

    data class Input(
        val journeyActive: Boolean,
        /** ms desde el último fix aceptado (null = nunca hubo). */
        val fixAgeMs: Long?,
        /** MotionSensor MOVING (histéresis propia: no flapea con un pico). */
        val sensorMoving: Boolean,
        /** desplazamiento real desde el ancla (m), si medible. */
        val displacementM: Float?,
        /** rescate activo ahora (el controlador lo reporta). */
        val rescueActive: Boolean,
        /** evidencias consecutivas de movimiento sin fix (el llamador persiste). */
        val movementStreak: Int,
        /** ms del último rescate terminado (cooldown). null = nunca. */
        val lastRescueAtMs: Long? = null,
        /** reloj actual (para el cooldown). */
        val nowMs: Long = 0L,
    )

    data class Decision(
        val state: RescueState,
        /** true ⇒ pedir acciones de reacquisición (nudge + one-shot + burst). */
        val rescueRequested: Boolean,
        val reason: String,
    )

    fun decide(previous: RescueState, input: Input): Decision {
        if (!input.journeyActive) {
            return Decision(RescueState.NORMAL, rescueRequested = false, reason = "sin-jornada")
        }
        // Fix real fresco: recuperación o normalidad.
        val fresh = input.fixAgeMs != null && input.fixAgeMs <= FRESH_FIX_MS
        if (fresh) {
            return if (previous == RescueState.RESCUE) {
                Decision(RescueState.FIX_RECOVERED, rescueRequested = false, reason = "real-fix-tras-rescate")
            } else {
                Decision(RescueState.NORMAL, rescueRequested = false, reason = "fix-fresco")
            }
        }

        val degraded = input.fixAgeMs != null && input.fixAgeMs > FRESH_FIX_MS
        val lost = input.fixAgeMs == null || input.fixAgeMs > STALE_FIX_MS
        val moving = moving(input)

        val inCooldown = input.lastRescueAtMs?.let { input.nowMs - it < RESCUE_COOLDOWN_MS } == true

        return when {
            input.rescueActive -> Decision(RescueState.RESCUE, rescueRequested = false, reason = "rescue-en-curso")

            lost && moving -> if (inCooldown) {
                Decision(previous, rescueRequested = false, reason = "cooldown")
            } else {
                Decision(RescueState.RESCUE, rescueRequested = true, reason = "gps-lost+movement")
            }

            degraded && moving -> {
                val streak = input.movementStreak + 1
                if (streak >= CONFIRM_EVIDENCE_SAMPLES) {
                    if (inCooldown) {
                        Decision(previous, rescueRequested = false, reason = "cooldown")
                    } else {
                        Decision(RescueState.RESCUE, rescueRequested = true, reason = "degraded+movement x$streak")
                    }
                } else {
                    Decision(RescueState.MOVEMENT_CANDIDATE, rescueRequested = false, reason = "movimiento 1/$CONFIRM_EVIDENCE_SAMPLES")
                }
            }

            lost -> Decision(RescueState.GPS_LOST, rescueRequested = false, reason = "gps-lost+stationary")
            degraded -> Decision(RescueState.GPS_DEGRADED, rescueRequested = false, reason = "fix-envejece")
            else -> Decision(RescueState.NORMAL, rescueRequested = false, reason = "sin-degradación")
        }
    }

    /** Evidencia de movimiento: sensor (estable por histéresis) o desplazamiento real >= 15 m. */
    private fun moving(input: Input): Boolean =
        input.sensorMoving || (input.displacementM?.let { it >= 15f } == true)
}
