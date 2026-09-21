package com.dmujeres.traccar.location

/**
 * R8 (angle): política pura de la regla OR del muestreo — al detectar un GIRO
 * REAL de la ruta se pide un fix extra inmediato para que la curva quede
 * trazada (patrón del "angle" de Traccar Client; investigación en
 * docs/audit/ARCH_RESEARCH_SAMPLING.md).
 *
 * Guardas contra el bearing ruidoso del GPS: exige pata mínima y velocidad
 * implícita; con bearing inválido (NaN) no dispara nunca. Rate-limit propio
 * para no pedir fixes en cada intersección.
 */
object RouteSamplePolicy {

    /** Grados de giro entre segmentos consecutivos que cuentan como curva. */
    const val ANGLE_TRIGGER_DEG = 15.0

    /** Pata mínima (m): por debajo el bearing es puro ruido. */
    const val MIN_SEGMENT_M = 8.0

    /** Velocidad implícita mínima (m/s): en quieto no hay "giro". */
    const val MIN_IMPLIED_SPEED_MPS = 1.5f

    /** Mínimo entre peticiones de giro (ms). */
    const val MIN_INTERVAL_BETWEEN_MS = 10_000L

    /** Delta angular normalizado a [0, 180]. */
    fun normalizeDelta(degrees: Double): Double {
        if (!degrees.isFinite()) return Double.NaN
        val d = ((degrees % 360.0) + 360.0) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    fun shouldForceSample(
        segmentBearingDeltaDeg: Double,
        segmentM: Double,
        impliedSpeedMps: Float?,
    ): Boolean {
        if (!segmentBearingDeltaDeg.isFinite()) return false
        if (!segmentM.isFinite() || segmentM < MIN_SEGMENT_M) return false
        if (impliedSpeedMps == null || !impliedSpeedMps.isFinite()) return false
        if (impliedSpeedMps < MIN_IMPLIED_SPEED_MPS) return false
        return normalizeDelta(segmentBearingDeltaDeg) >= ANGLE_TRIGGER_DEG
    }
}
