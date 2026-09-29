package org.traccar.client.movement

import org.traccar.client.capture.WalkDetector

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
 * Prioridad de evidencias (ADR-006, extendida con caminata):
 * 1. Velocidad GPS >= 3 kn: manda siempre.
+ * 1b. Caminata (avance neto 2-8 km/h sostenido 3 min): también es movimiento,
+ *     aunque la instantánea quede bajo 3 kn; nunca se degrada a STATIONARY
+ *     mientras haya avance sostenido.
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

    /**
     * Avance bajo pero sostenido (caminata): se alimenta con cada fix que trae
     * coordenadas ([onFixWithPosition]). Mientras haya avance, la máquina no
     * puede caer a STATIONARY aunque la velocidad instantánea sea <3 kn, y la
     * cadencia se mantiene fina (ACTIVE).
     */
    private val walkDetector = WalkDetector()

    /** ¿El avance acumulado indica caminata ahora mismo? (para el panel). */
    val isWalking: Boolean
        get() = walkDetector.walking

    // --- Ciclo de jornada ---------------------------------------------------

    /** La jornada siempre nace de una acción visible del usuario. */
    fun onJourneyStarted(nowMs: Long): State {
        state = State.STARTING
        stillSinceMs = -1L
        lastFixAtMs = -1L
        lastMotionAtMs = nowMs
        walkDetector.reset()
        return state
    }

    fun onJourneyStopped(): State {
        state = State.STOPPED
        stillSinceMs = -1L
        lastFixAtMs = -1L
        lastMotionAtMs = -1L
        walkDetector.reset()
        return state
    }

    /**
     * Alinea la máquina con la jornada persistida. Bug real (Manzaba 2.1.83,
     * Fernando 2.1.80): la app arranca el servicio al abrirse, con la jornada
     * aún cerrada (STOPPED); al pulsar "Iniciar jornada" solo se escribían las
     * prefs y la máquina seguía en STOPPED todo el día: fixes ignorados,
     * cadencia lenta, sin rescate. Solo trazaba si el sistema recreaba el
     * servicio. El controlador llama esto en cada pulso y en cada
     * onStartCommand. Devuelve true si cambió el estado.
     */
    fun syncJourney(nowMs: Long, journeyOpen: Boolean): Boolean {
        if (journeyOpen && state == State.STOPPED) {
            onJourneyStarted(nowMs)
            return true
        }
        if (!journeyOpen && state != State.STOPPED) {
            onJourneyStopped()
            return true
        }
        return false
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
    ): State = onFixInternal(nowMs, speedKn, displacementM, imuMoving, significantMotion, isWalking)

    /**
     * Fix GPS aceptado CON coordenadas: además de lo mismo que [onFix],
     * alimenta el detector de caminata con el avance real. Es la entrada que
     * usa el controlador en producción; [onFix] queda para compatibilidad y
     * tests legados (no toca el historial de avance).
     */
    fun onFixWithPosition(
        nowMs: Long,
        speedKn: Double,
        displacementM: Double,
        latitude: Double,
        longitude: Double,
        imuMoving: Boolean?,
        significantMotion: Boolean = false,
    ): State {
        if (state == State.STOPPED) return state
        val walking = walkDetector.add(nowMs, latitude, longitude)
        return onFixInternal(nowMs, speedKn, displacementM, imuMoving, significantMotion, walking)
    }

    private fun onFixInternal(
        nowMs: Long,
        speedKn: Double,
        displacementM: Double,
        imuMoving: Boolean?,
        significantMotion: Boolean,
        walking: Boolean,
    ): State {
        if (state == State.STOPPED) return state
        lastFixAtMs = nowMs
        val moving = isMovingEvidence(speedKn, displacementM, imuMoving, significantMotion, walking)
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
        // Sin fixes nuevos el avance viejo caduca: no vale como caminata eterna.
        walkDetector.evict(nowMs)
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
            // El avance previo al hueco ya no dice nada del presente.
            walkDetector.reset()
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
        // El historial no trae coordenadas: el avance previo no se hereda (se
        // reconstruye en vivo con los primeros fixes; ante la duda, fino).
        walkDetector.reset()
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
        walking: Boolean,
    ): Boolean {
        // 1. El GPS manda siempre. 1b. La caminata (avance bajo pero sostenido)
        // también es movimiento: nunca se degrada a STATIONARY por velocidad
        // instantánea <3 kn mientras haya avance. 2. El desplazamiento suple
        // velocidad nula. 3. El sensor significativo arranca ruta. 4. El IMU
        // es el último indicio: suma, pero su UNKNOWN (null) nunca resta.
        if (speedKn >= MOVING_SPEED_KN) return true
        if (walking) return true
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
