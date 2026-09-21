package com.dmujeres.traccar.location

/**
 * R3.5-C / P3: política PURA de inicio de movimiento desde detenido.
 *
 * Problema real: [AdaptiveDistancePolicy] solo pasaba a MOVING con speed > 5 m/s;
 * un vehículo que arranca lento (5→10→20 km/h ≈ 1.4→5.5 m/s) podía quedarse
 * demasiado tiempo en modo stationary (captura rala), perdiendo el inicio real
 * del trayecto.
 *
 * Esta política NO fabrica coordenadas ni hace dead reckoning: solo decide
 * CUÁNDO cambiar de estrategia de adquisición usando evidencia real:
 *  - velocidad GNSS real del fix (doppler/implied),
 *  - desplazamiento real respecto al ancla estacionaria,
 *  - señal de movimiento de sensores (accel/gyro) — evidencia auxiliar.
 *
 * Reglas (calibradas con las constantes ya existentes del proyecto):
 *  - speed >= [MOVING_SPEED_MPS] (5 m/s, legado) → MOVING inmediato.
 *  - speed >= [CANDIDATE_SPEED_MPS] (1.5 m/s, ya usado por la fusión) o
 *    desplazamiento >= [DISPLACEMENT_CONFIRM_M] (15 m, umbral geométrico de
 *    FixFilter) → evidencia real: requiere [CONFIRM_SAMPLES] muestras
 *    consecutivas para confirmar MOVING (candidate intermedio).
 *  - sensor aislado (sin velocidad/desplazamiento) → MOVEMENT_CANDIDATE, nunca
 *    MOVING (jamás produce posición).
 *  - sin fix fresco → STATIONARY (no se inventa movimiento).
 *  - histéresis inversa: MOVING → STATIONARY solo con speed < 1 m/s o sin fix.
 */
object MovementStartPolicy {

    /** Velocidad de candidatura: la fusión ya considera movimiento ~1.5 m/s. */
    const val CANDIDATE_SPEED_MPS = 1.5f

    /** Confirmación inmediata heredada (5 m/s). */
    const val MOVING_SPEED_MPS = 5.0f

    /** Debajo de esta velocidad, MOVING vuelve a STATIONARY (histéresis legada). */
    const val STATIONARY_SPEED_MPS = 1.0f

    /** Desplazamiento real (m) respecto al ancla estacionaria que evidencia inicio. */
    const val DISPLACEMENT_CONFIRM_M = 15.0f

    /** Muestras consecutivas de evidencia real para confirmar MOVING. */
    const val CONFIRM_SAMPLES = 2

    enum class State { STATIONARY, MOVEMENT_CANDIDATE, MOVING }

    data class Evidence(
        /** speed del último fix válido (m/s); null = sin dato. */
        val gnssSpeedMps: Float?,
        /** distancia real (m) al ancla fijada al entrar/estar en STATIONARY. */
        val displacementFromAnchorM: Float?,
        /** accel/gyro indican movimiento (evidencia auxiliar, nunca única). */
        val sensorMovement: Boolean,
        /** hay fix fresco encolado (GPS operativo ahora mismo). */
        val hasFreshFix: Boolean,
    )

    data class Decision(val state: State, val reason: String)

    /**
     * Transición pura.
     * @param current estado actual
     * @param candidateStreak muestras consecutivas de evidencia real previas
     *        (el llamador persiste el retorno [Decision] + su conteo).
     */
    fun next(current: State, evidence: Evidence, candidateStreak: Int): Decision {
        if (!evidence.hasFreshFix) {
            return Decision(State.STATIONARY, "sin-fix-fresco")
        }
        val speed = evidence.gnssSpeedMps?.takeIf { it.isFinite() && it >= 0f }

        if (current == State.MOVING) {
            // Histéresis (paridad con el legado): se mantiene solo con
            // velocidad clara; nulo o < 1 m/s vuelve a STATIONARY.
            if (speed == null || speed < STATIONARY_SPEED_MPS) {
                return Decision(State.STATIONARY, "speed-null-o<${STATIONARY_SPEED_MPS}")
            }
            return Decision(State.MOVING, "sigue-moving")
        }
        if (speed != null && speed >= MOVING_SPEED_MPS) {
            return Decision(State.MOVING, "speed>=${MOVING_SPEED_MPS}")
        }

        val realEvidence = (speed != null && speed >= CANDIDATE_SPEED_MPS) ||
            (evidence.displacementFromAnchorM?.let { it >= DISPLACEMENT_CONFIRM_M } == true)

        if (realEvidence) {
            val streak = candidateStreak + 1
            // R8.1: desplazamiento >=15 m del ancla estacionaria es evidencia
            // geométrica DURA: confirma MOVING con UNA muestra. La velocidad
            // sola mantiene su doble confirmación (anti-Doppler mentiroso).
            val hardDisplacement =
                evidence.displacementFromAnchorM?.let { it >= DISPLACEMENT_CONFIRM_M } == true
            return if (hardDisplacement || streak >= CONFIRM_SAMPLES) {
                Decision(
                    State.MOVING,
                    if (hardDisplacement) "desplazamiento>=$DISPLACEMENT_CONFIRM_M" else "evidencia x$streak",
                )
            } else {
                Decision(State.MOVEMENT_CANDIDATE, "evidencia 1/$CONFIRM_SAMPLES")
            }
        }
        if (evidence.sensorMovement) {
            // Sensores solos: candidato (activa adquisición más densa) pero
            // NUNCA confirman movimiento ni producen coordenada.
            return Decision(State.MOVEMENT_CANDIDATE, "sensor-only")
        }
        return Decision(State.STATIONARY, "sin-evidencia")
    }

    /** ¿Este estado debe usar adquisición densa (burst de arranque)? */
    fun usesBurstCapture(state: State): Boolean =
        state == State.MOVEMENT_CANDIDATE || state == State.MOVING
}
