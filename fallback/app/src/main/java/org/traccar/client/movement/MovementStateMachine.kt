package org.traccar.client.movement

/**
 * Máquina de estados de movimiento (pura: sin Android, testeable en JVM).
 *
 * Por qué existe: el caso Pilay demostró que decidir moviendo/parado solo con
 * el acelerómetro deja la cadencia en 120 s durante un viaje completo (en
 * marcha constante el sensor queda "quieto" y ya no llegan fixes que lo
 * desmientan). Esta máquina combina las evidencias por prioridad y nunca deja
 * que la falta de evidencia degrade a lento: sin datos se captura en fino
 * (ACTIVE-seguro), no en grueso.
 *
 * Prioridad de evidencias (ADR-006):
 * 1. Velocidad GPS >= 3 kn: manda siempre.
 * 2. Desplazamiento acumulado entre fixes >= 150 m: suple velocidad nula.
 * 3. Significant motion / giro: arranque de ruta.
 * 4. Acelerómetro ([MotionMonitor]): ÚLTIMO indicio, nunca por sí solo.
 * 5. Historial persistido: reconstrucción tras recreación de proceso.
 *
 * Reglas temporales:
 * - STATIONARY exige >= 3 min de evidencia consistente de quietud.
 * - Sin fix nuevo NO se baja a cadencia lenta; a los 4 min sin fix se entra
 *   en RECOVERING (re-solicitar, GPS del sistema, alarma).
 * - UNKNOWN (sensor) se resuelve a ACTIVE: lo seguro es capturar en fino.
 */
class MovementStateMachine {

    /** Estados de la arquitectura cerrada (§6). */
    enum class State {
        STOPPED,
        STARTING,
        ACTIVE,
        STATIONARY,
        DEGRADED,
        RECOVERING,
    }

    /**
     * Muestra mínima del historial persistido para reconstruir el estado tras
     * recrear el proceso (nunca "en memoria = válido").
     */
    data class FixSample(
        val atMs: Long,
        val speedKn: Double,
        val displacementM: Double,
    )

    var state: State = State.STOPPED
        private set

    /** Cuándo empezó la evidencia consistente de quietud (para los 3 min). */
    private var stillSinceMs: Long = -1L

    /** Último fix aceptado (para los 4 min sin fix). */
    private var lastFixAtMs: Long = -1L

    /** Último momento con evidencia de movimiento. */
    private var lastMotionAtMs: Long = -1L

    // --- Ciclo de jornada ---------------------------------------------------

    /** La jornada siempre nace de una acción visible del usuario. */
    fun onJourneyStarted(nowMs: Long): State {
        state = State.STARTING
        stillSinceMs = -1L
        lastFixAtMs = -1L
        lastMotionAtMs = nowMs
        return state
    }

    fun onJourneyStopped(): State {
        state = State.STOPPED
        stillSinceMs = -1L
        lastFixAtMs = -1L
        lastMotionAtMs = -1L
        return state
    }

    // --- Entradas ------------------------------------------------------------

    /**
     * Fix GPS aceptado.
     *
     * @param speedKn velocidad reportada por el GPS (nudos).
     * @param displacementM desplazamiento desde el último fix aceptado (m).
     * @param imuMoving acelerómetro: true/false, null = sin datos (UNKNOWN).
     * @param significantMotion aviso del sensor de movimiento significativo.
     */
    fun onFix(
        nowMs: Long,
        speedKn: Double,
        displacementM: Double,
        imuMoving: Boolean?,
        significantMotion: Boolean = false,
    ): State {
        if (state == State.STOPPED) return state
        lastFixAtMs = nowMs
        val moving = isMovingEvidence(speedKn, displacementM, imuMoving, significantMotion)
        if (moving) {
            lastMotionAtMs = nowMs
            stillSinceMs = -1L
            // Cualquier evidencia de movimiento captura en fino.
            state = State.ACTIVE
        } else {
            // Quietud aparente: solo cuenta como evidencia si el GPS no dice lo
            // contrario (el IMU nunca veta al GPS) y se sostiene 3 min. El
            // primer fix aceptado siempre sale de STARTING hacia ACTIVE
            // (cadencia fina); STATIONARY exige sostener la evidencia.
            if (stillSinceMs < 0L) stillSinceMs = nowMs
            if (state == State.STARTING) {
                state = State.ACTIVE
            } else if (state == State.ACTIVE || state == State.RECOVERING) {
                if (nowMs - stillSinceMs >= STATIONARY_AFTER_MS) {
                    state = State.STATIONARY
                }
            }
        }
        return state
    }

    /**
     * Pulso del acelerómetro entre fixes: solo un indicio más, nunca decide
     * solo. Si el IMU dice moviendo se adelanta la cadencia fina; si dice
     * quieto no se baja de cadencia (el GPS manda).
     */
    fun onImuHint(nowMs: Long, imuMoving: Boolean?): State {
        if (state == State.STOPPED) return state
        if (imuMoving == true) {
            lastMotionAtMs = nowMs
            stillSinceMs = -1L
            if (state == State.STATIONARY || state == State.STARTING) {
                state = State.ACTIVE
            }
        }
        return state
    }

    /** Aviso one-shot del sensor de movimiento significativo: arranca ruta. */
    fun onSignificantMotion(nowMs: Long): State {
        if (state == State.STOPPED) return state
        lastMotionAtMs = nowMs
        stillSinceMs = -1L
        if (state != State.ACTIVE) state = State.ACTIVE
        return state
    }

    /**
     * Reloj (llamado cada minuto por el controlador): aplica las transiciones
     * puramente temporales. Sin fix nuevo jamás se baja a STATIONARY; a los
     * 4 min sin fix se pide rescate.
     */
    fun onTick(nowMs: Long): State {
        if (state == State.STOPPED || state == State.STARTING) return state
        // lastFixAtMs >= 0: hubo al menos un fix (el 0L es válido en tests; en
        // producción los epoch reales nunca son 0; -1L = sin fix aún).
        if (lastFixAtMs >= 0L && nowMs - lastFixAtMs >= NO_FIX_RECOVER_MS) {
            if (state == State.ACTIVE || state == State.STATIONARY || state == State.DEGRADED) {
                state = State.RECOVERING
            }
        }
        return state
    }

    /** GPS apagado / sin permiso / proveedor caído: degradado, no parado. */
    fun onPositionUnavailable(): State {
        if (state != State.STOPPED) state = State.DEGRADED
        return state
    }

    /** Disparo de recuperación (alarma, FCM, boot, red): rescatar, no reloj. */
    fun onRecoveryTriggered(nowMs: Long): State {
        if (state == State.STOPPED) return state
        if (state == State.ACTIVE || state == State.STATIONARY || state == State.DEGRADED) {
            state = State.RECOVERING
            // Al recuperar se arranca en fino hasta tener evidencia (3 min).
            stillSinceMs = -1L
            lastMotionAtMs = nowMs
        }
        return state
    }

    /**
     * La recuperación termina cuando hay fix fresco Y la cola fluye: entonces
     * se vuelve a ACTIVE-seguro (la evidencia posterior dirá si es STATIONARY).
     */
    fun onRecovered(nowMs: Long, queueFlowing: Boolean): State {
        if (state == State.RECOVERING && queueFlowing) {
            state = State.ACTIVE
            lastFixAtMs = nowMs
            lastMotionAtMs = nowMs
            stillSinceMs = -1L
        }
        return state
    }

    // --- Reconstrucción -------------------------------------------------------

    /**
     * Reconstruye el estado desde el almacén tras recrear el proceso.
     * Sin historial se arranca en ACTIVE-seguro durante 3 min (nunca se asume
     * quietud sin evidencia). Con historial reciente en movimiento se sigue en
     * ACTIVE; si todo es quieto desde hace >= 3 min, STATIONARY.
     */
    fun restore(nowMs: Long, journeyOpen: Boolean, history: List<FixSample>): State {
        if (!journeyOpen) {
            state = State.STOPPED
            return state
        }
        if (history.isEmpty()) {
            // Sin datos: fino y seguro, no lento.
            state = State.ACTIVE
            lastFixAtMs = -1L
            stillSinceMs = nowMs
            lastMotionAtMs = nowMs
            return state
        }
        val recent = history.filter { nowMs - it.atMs <= HISTORY_WINDOW_MS }
        if (recent.isEmpty()) {
            // Historial viejo (p. ej. reboot hace horas): rescate, no quietud.
            state = State.RECOVERING
            lastFixAtMs = -1L
            stillSinceMs = -1L
            return state
        }
        val moving = recent.any { it.speedKn >= MOVING_SPEED_KN || it.displacementM >= MOVING_DISTANCE_M }
        val oldestRecent = recent.minOf { it.atMs }
        lastFixAtMs = recent.maxOf { it.atMs }
        if (moving) {
            state = State.ACTIVE
            lastMotionAtMs = lastFixAtMs
            stillSinceMs = -1L
        } else if (nowMs - oldestRecent >= STATIONARY_AFTER_MS) {
            state = State.STATIONARY
            stillSinceMs = oldestRecent
        } else {
            // Quietud breve sin 3 min de evidencia: fino y seguro.
            state = State.ACTIVE
            stillSinceMs = oldestRecent
            lastMotionAtMs = nowMs
        }
        return state
    }

    /** ¿La cadencia debe ser fina? Todo salvo STATIONARY/STOPPED captura fino. */
    fun wantsFineCadence(): Boolean = when (state) {
        State.ACTIVE, State.STARTING, State.DEGRADED, State.RECOVERING -> true
        State.STATIONARY, State.STOPPED -> false
    }

    private fun isMovingEvidence(
        speedKn: Double,
        displacementM: Double,
        imuMoving: Boolean?,
        significantMotion: Boolean,
    ): Boolean {
        // 1. El GPS manda siempre. 2. El desplazamiento suple velocidad nula.
        // 3. El sensor significativo arranca ruta. 4. El IMU es el último
        // indicio: suma, pero su UNKNOWN (null) nunca resta.
        if (speedKn >= MOVING_SPEED_KN) return true
        if (displacementM >= MOVING_DISTANCE_M) return true
        if (significantMotion) return true
        return imuMoving == true
    }

    companion object {
        /** Velocidad GPS (kn) desde la que hay movimiento real. */
        const val MOVING_SPEED_KN = 3.0

        /** Desplazamiento (m) que suple una velocidad nula. */
        const val MOVING_DISTANCE_M = 150.0

        /** STATIONARY exige 3 min de evidencia consistente. */
        const val STATIONARY_AFTER_MS = 3 * 60_000L

        /** Sin fix 4 min se pide rescate (igual que el watchdog). */
        const val NO_FIX_RECOVER_MS = 4 * 60_000L

        /** Ventana del historial que cuenta para reconstruir. */
        const val HISTORY_WINDOW_MS = 10 * 60_000L
    }
}
