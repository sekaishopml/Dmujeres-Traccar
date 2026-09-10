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
     * Distancia mínima entre updates del FLP (15 m urbano/peatón; en movimiento no se nota).
     * Se aplica con `LocationRequest.setMinUpdateDistanceMeters`.
     */
    const val MIN_UPDATE_DISTANCE_M = 15f

    /** Techo absoluto: accuracy >= 500 m se rechaza (igual que antes). */
    const val MAX_ACCURACY_M = 500f

    /** Primer fix tras arranque: exige accuracy < 150 m o se descarta sin fundar ventana. */
    const val FIRST_FIX_MAX_ACCURACY_M = 150f

    /** Staleness máxima por elapsedRealtimeNanos: > 120 s se descarta. */
    const val STALE_AFTER_NANOS = 120_000_000_000L

    /** Tamaño de la ventana honesta (aceptados + rechazados). */
    const val WINDOW_SIZE = 6

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
        // 5. Velocidad implícita (GPS loco).
        val dt = (wallTimeMs - previous.timeMs) / 1000.0
        // dt > 0 garantizado por el bloque anterior.
        val implied = distanceMeters(previous.lat, previous.lon, lat, lon) / dt
        if (implied > maxImpliedSpeedMps && !recentMovingConsistently(window, consistentSpeedMps)) {
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
