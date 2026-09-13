package com.dmujeres.traccar.location

/**
 * Filtro anti-drift puro (testeable en JVM, sin dependencias Android).
 *
 * Extrae la lógica de [com.dmujeres.traccar.location.TrackingService] para poder
 * probarla con `testDebugUnitTest`:
 * - Techo de accuracy 500 m ([MAX_ACCURACY_M]).
 * - Primer fix tras arranque exige accuracy < 150 m ([FIRST_FIX_MAX_ACCURACY_M]),
 *   si no se descarta sin fundar la ventana.
 * - Ventana honesta de 6 ([WINDOW_SIZE]): el llamador registra TODOS los evaluados
 *   (aceptados y rechazados) salvo el primer rechazo que no funda ventana; el chequeo
 *   de degradado exige un bueno (<= good) en TODA la ventana.
 * - dt<=0 no auto-pasa: valida staleness con elapsedRealtimeNanos (>120 s descarta)
 *   y si el fix es una re-entrega cacheada (time/elapsed no avanzan) solo se acepta
 *   si las coordenadas se movieron > 2 m respecto al anterior; si no, Reject("stale_relay").
 * - Marca lowQuality cuando accuracy en [bad, 500) en vez de tirar el fix.
 */
object FixFilter {

    /**
     * Distancia mínima entre updates del FLP en movimiento (24 m, perfil oculto
     * estilo Traccar "Highest": reportar cada N metros en marcha; el sistema
     * nunca garantiza el valor exacto, es una sugerencia al FLP).
     * Se aplica con `LocationRequest.setMinUpdateDistanceMeters`.
     */
    const val MIN_UPDATE_DISTANCE_M = 24f

    /** Techo absoluto: accuracy >= 500 m se rechaza (igual que antes). */
    const val MAX_ACCURACY_M = 500f

    /** Primer fix tras arranque: exige accuracy < 150 m o se descarta sin fundar ventana. */
    const val FIRST_FIX_MAX_ACCURACY_M = 150f

    /** Staleness máxima por elapsedRealtimeNanos: > 120 s se descarta. */
    const val STALE_AFTER_NANOS = 120_000_000_000L

    /**
     * Silencio (ms) tras el cual un fix se trata como RE-ADQUISICIÓN: juzgar
     * "velocidad implícita" contra una referencia de hace horas es meaningless
     * (cualquier velocidad fue posible en el hueco: viaje real sin fixes por
     * Doze/túnel, o salto). Pasado este umbral se omite el rechazo
     * implied_speed (siguen vigentes accuracy/degraded/stale). Mayor que el
     * peor hueco legítimo (polling con backoff hasta 10 min en batería baja).
     */
    const val REACQUIRE_AFTER_MS = 15 * 60_000L

    /** Tamaño de la ventana honesta (aceptados + rechazados). */
    const val WINDOW_SIZE = 6

    // ── Perfil de captura oculto estilo Traccar "Highest" (sin UI: siempre
    //    preciso en producción). Regla OR de reporte (docs Traccar + foros):
    //    se encola si pasó la frecuencia O se avanzó la distancia O se giró
    //    el ángulo. El ángulo es lo que evita rectas en curvas: cada giro en
    //    intersección genera su punto y el trazo sigue la calle.
    /** Distancia (m) desde el último aceptado que dispara reporte. */
    const val REPORT_DISTANCE_M = 24.0

    /** Giro (grados) respecto al rumbo aceptado que dispara reporte. */
    const val REPORT_ANGLE_DEG = 15.0

    /** Pata mínima (m) para que el giro cuente (filtra jitter parado). */
    const val REPORT_ANGLE_MIN_LEG_M = 8.0

    /** Velocidad implícita mínima (m/s) para que el giro cuente. */
    const val REPORT_ANGLE_MIN_SPEED_MPS = 1.5

    /** Quietud (ms) tras la cual entra el heartbeat de parada. */
    const val STOP_STILL_TIMEOUT_MS = 60_000L

    /** Cadencia del heartbeat en parada (Traccar: stationary heartbeat 60 s). */
    const val STOP_HEARTBEAT_SECONDS = 60L

    /**
     * Anti-livelock R1: si la ventana sigue vacía (cero capturas) y el primer fix
     * exige accuracy < 150, un GPS que siempre da >=150 dejaría cero capturas
     * para siempre. Tras [EMPTY_WINDOW_MAX_REJECTS] rechazos consecutivos con
     * ventana vacía (o [EMPTY_WINDOW_FORCE_AFTER_MS] desde startTracking) se
     * funda la ventana y se acepta marcado como lowQuality.
     */
    const val EMPTY_WINDOW_MAX_REJECTS = 10
    const val EMPTY_WINDOW_FORCE_AFTER_MS = 5 * 60_000L

    /** Decide si un rechazo con ventana vacía debe forzarse a Accept(lowQuality). */
    fun shouldForceEmptyWindowAccept(emptyRejects: Int, elapsedSinceStartMs: Long): Boolean =
        emptyRejects >= EMPTY_WINDOW_MAX_REJECTS || elapsedSinceStartMs >= EMPTY_WINDOW_FORCE_AFTER_MS

    /**
     * Arranque: solo se limpia la ventana si NO se recupera jornada. Si se
     * recupera, la memoria del bueno persiste y evita re-livelock.
     */
    fun shouldClearWindowOnStart(recoveringJourney: Boolean): Boolean = !recoveringJourney

    /**
     * lastFixAt solo avanza tras insert OK: insertWithinLimit devuelve >=0 si
     * encoló (0 = sin descartes, >0 = descartados) y -1 si no hubo espacio.
     * Avanzar antes enmascara fallos de DB/buffer y suprime heartbeats.
     */
    fun shouldAdvanceLastFix(insertResult: Int): Boolean = insertResult >= 0

    /**
     * Razón de invalidez para Log.w antes de descartar en silencio (par de
     * [isValidLocation]). null = válido.
     */
    fun invalidLocationReason(lat: Double, lon: Double, accuracyM: Float): String? {
        if (!lat.isFinite() || lat !in -90.0..90.0) return "invalid_lat=$lat"
        if (!lon.isFinite() || lon !in -180.0..180.0) return "invalid_lon=$lon"
        if (!accuracyM.isFinite() || accuracyM < 0f) return "invalid_accuracy=$accuracyM"
        if (accuracyM >= MAX_ACCURACY_M) return "accuracy_ceiling=$accuracyM"
        return null
    }

    fun isValidLocation(lat: Double, lon: Double, accuracyM: Float): Boolean =
        invalidLocationReason(lat, lon, accuracyM) == null

    data class RecentFix(
        val lat: Double,
        val lon: Double,
        val accuracyM: Float,
        val timeMs: Long,
        val elapsedNanos: Long = 0L,
    )

    sealed interface Decision {
        data class Accept(val lowQuality: Boolean) : Decision
        data class Reject(val reason: String) : Decision
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earth = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return earth * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    /** Marca de calidad: accuracy en [badM, 500). Se omite del payload si < bad. */
    fun isLowQuality(accuracyM: Float, accuracyBadM: Float = 80f): Boolean =
        accuracyM >= accuracyBadM && accuracyM < MAX_ACCURACY_M

    fun recentMovingConsistently(
        window: List<RecentFix>,
        consistentSpeedMps: Float,
    ): Boolean {
        if (window.size < 2) return false
        val a = window[window.size - 2]
        val b = window[window.size - 1]
        val dt = (b.timeMs - a.timeMs) / 1000.0
        if (dt <= 0) return false
        return distanceMeters(a.lat, a.lon, b.lat, b.lon) / dt > consistentSpeedMps
    }

    /** Rumbo geográfico 0-360 (0 = norte, horario) del tramo a→b. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val la1 = Math.toRadians(lat1)
        val la2 = Math.toRadians(lat2)
        val y = Math.sin(dLon) * Math.cos(la2)
        val x = Math.cos(la1) * Math.sin(la2) -
            Math.sin(la1) * Math.cos(la2) * Math.cos(dLon)
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    /** Diferencia de rumbos 0-180. */
    fun angleDiffDeg(bearingA: Double, bearingB: Double): Double {
        if (!bearingA.isFinite() || !bearingB.isFinite()) return Double.NaN
        var diff = Math.abs(bearingA - bearingB) % 360.0
        if (diff > 180.0) diff = 360.0 - diff
        return diff
    }

    /** Referencia del último fix ACEPTADO (encolado) para la regla OR. */
    data class AcceptedRef(
        val lat: Double,
        val lon: Double,
        val timeMs: Long,
        /** Rumbo del tramo que LLEGÓ a este punto; NaN si se desconoce. */
        val inboundBearingDeg: Double = Double.NaN,
    )

    /**
     * Regla OR de reporte estilo Traccar (pura, testeable): acepta el candidato
     * si pasó la frecuencia desde el último aceptado, o avanzó [REPORT_DISTANCE_M],
     * o giró [REPORT_ANGLE_DEG] con pata e implícita mínimas (curvas densas,
     * rectas limpias). Sin referencia (primer fix) siempre acepta.
     */
    fun acceptByRule(
        ref: AcceptedRef?,
        lat: Double,
        lon: Double,
        timeMs: Long,
        frequencyMs: Long,
        distanceM: Double = REPORT_DISTANCE_M,
        angleDeg: Double = REPORT_ANGLE_DEG,
    ): Boolean {
        if (ref == null) return true
        if (timeMs - ref.timeMs >= frequencyMs) return true
        val leg = distanceMeters(ref.lat, ref.lon, lat, lon)
        if (leg >= distanceM) return true
        if (leg >= REPORT_ANGLE_MIN_LEG_M && ref.inboundBearingDeg.isFinite()) {
            val dtSeconds = (timeMs - ref.timeMs) / 1000.0
            val implied = if (dtSeconds > 0) leg / dtSeconds else Double.NaN
            if (implied.isFinite() && implied >= REPORT_ANGLE_MIN_SPEED_MPS) {
                val turn = angleDiffDeg(
                    ref.inboundBearingDeg,
                    bearingDeg(ref.lat, ref.lon, lat, lon),
                )
                if (turn.isFinite() && turn >= angleDeg) return true
            }
        }
        return false
    }

    /** Heartbeat de parada: quieto más de [STOP_STILL_TIMEOUT_MS] → cadencia larga. */
    fun heartbeatDue(lastMovementMs: Long, nowMs: Long): Boolean =
        nowMs - lastMovementMs > STOP_STILL_TIMEOUT_MS

    @Suppress("ReturnCount")
    fun evaluate(
        lat: Double,
        lon: Double,
        accuracyM: Float,
        wallTimeMs: Long,
        elapsedNanos: Long,
        nowElapsedNanos: Long,
        window: List<RecentFix>,
        maxImpliedSpeedMps: Float = 45f,
        accuracyBadM: Float = 80f,
        accuracyGoodM: Float = 20f,
        consistentSpeedMps: Float = 30f,
        // Dt real (s) entre el fix previo y este, calculado por el llamador con
        // FixTime.dtSeconds (elapsedRealtime preferente sobre location.time).
        // null = comportamiento por defecto (dt de wallTimeMs de ambos fixes).
        dtSecondsOverride: Double? = null,
    ): Decision {
        // 1. Validez básica + techo (igual que TrackingService.isValidLocation).
        if (!lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            !accuracyM.isFinite() || accuracyM < 0f || accuracyM >= MAX_ACCURACY_M
        ) {
            return Decision.Reject(if (accuracyM.isFinite() && accuracyM >= MAX_ACCURACY_M) "accuracy_ceiling" else "invalid")
        }
        // 2. Staleness por elapsedRealtimeNanos (solo si ambos relojes conocidos).
        if (elapsedNanos > 0L && nowElapsedNanos > 0L) {
            val staleness = nowElapsedNanos - elapsedNanos
            if (staleness > STALE_AFTER_NANOS) {
                return Decision.Reject("stale")
            }
        }
        // 3. Primer fix tras arranque: exige accuracy < 150 o no funda ventana.
        val previous = window.lastOrNull() ?: return if (accuracyM < FIRST_FIX_MAX_ACCURACY_M) {
            Decision.Accept(lowQuality = isLowQuality(accuracyM, accuracyBadM))
        } else {
            Decision.Reject("first_fix_bad")
        }
        // 4. Time/elapsed no avanzan: re-entrega del FLP del mismo fix cacheado
        //    (wifi repetida 100+ veces). Solo se acepta si las coordenadas se
        //    movieron > 2 m respecto a `previous`; si no, es un duplicado y se
        //    rechaza como "stale_relay" (no era suficiente con accuracy < good).
        val wallNonMonotonic = wallTimeMs <= previous.timeMs
        val elapsedNonMonotonic = elapsedNanos > 0L && previous.elapsedNanos > 0L &&
            elapsedNanos <= previous.elapsedNanos
        if (wallNonMonotonic || elapsedNonMonotonic) {
            val moved = distanceMeters(previous.lat, previous.lon, lat, lon)
            return if (moved > 2.0) {
                Decision.Accept(lowQuality = isLowQuality(accuracyM, accuracyBadM))
            } else {
                Decision.Reject("stale_relay")
            }
        }
        // 5. Velocidad implícita (GPS loco). Con silencio prolongado NO se juzga:
        // la referencia es de hace horas y cualquier velocidad fue posible en
        // el hueco (re-adquisición tras Doze/túnel). Ver REACQUIRE_AFTER_MS.
        val dt = dtSecondsOverride ?: ((wallTimeMs - previous.timeMs) / 1000.0)
        // dt > 0 garantizado por el bloque anterior.
        val implied = distanceMeters(previous.lat, previous.lon, lat, lon) / dt
        if (dt * 1000.0 <= REACQUIRE_AFTER_MS &&
            implied > maxImpliedSpeedMps && !recentMovingConsistently(window, consistentSpeedMps)
        ) {
            return Decision.Reject("implied_speed")
        }
        // 6. Degradado: accuracy > bad con un bueno (<= good) en TODA la ventana.
        //    La ventana incluye rechazados (honesta), así la memoria del bueno persiste
        //    mientras quede algún bueno en los últimos 6 evaluados.
        if (accuracyM > accuracyBadM && window.any { it.accuracyM <= accuracyGoodM }) {
            return Decision.Reject("degraded")
        }
        return Decision.Accept(lowQuality = isLowQuality(accuracyM, accuracyBadM))
    }
}
