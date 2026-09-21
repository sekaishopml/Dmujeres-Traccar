package com.dmujeres.traccar.outbox

/**
 * Presupuesto de captura (F1): estimación honesta de volumen para el informe
 * de batería/datos. No inventa mediciones: bytes por punto es una ESTIMACIÓN
 * declarada como tal.
 */
object CaptureBudgetPolicy {

    /** Estimación declarada (no medición): bytes por punto encolado (JSON + overhead). */
    const val BYTES_PER_POINT_ESTIMATE = 220L

    /** Aviso a partir de 12 000 puntos/día (≈ ritmo de 5 s en una jornada). */
    const val DAILY_POINTS_WARN = 12_000L

    /** Aviso a partir de 8 MiB/día estimados de tráfico. */
    const val DAILY_BYTES_WARN = 8L * 1024 * 1024

    /** Estimación de bytes para N puntos (aproximada y declarada). */
    fun estimateBytes(points: Long): Long = points * BYTES_PER_POINT_ESTIMATE

    /** Supera el presupuesto diario de puntos (umbral incluido). */
    fun isOverPoints(pointsToday: Long): Boolean = pointsToday >= DAILY_POINTS_WARN

    /** Supera el presupuesto diario estimado de bytes (umbral incluido). */
    fun isOverBytes(pointsToday: Long): Boolean =
        estimateBytes(pointsToday) >= DAILY_BYTES_WARN

    /** Texto corto para logs/telemetría, p. ej. "captura: 3210 pts ≈ 706 KB (ok)". */
    fun summary(pointsToday: Long): String {
        // KB decimales (÷1000) como en el ejemplo del KDoc, no KiB.
        val kb = estimateBytes(pointsToday) / 1000L
        val state = if (isOverPoints(pointsToday) || isOverBytes(pointsToday)) "excede" else "ok"
        return "captura: $pointsToday pts ≈ $kb KB ($state)"
    }
}
