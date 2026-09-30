package org.traccar.client

/**
 * Velocidad implícita entre dos puntos aceptados (pura, testeable en JVM).
 * Solo se confía si el tramo supera el ruido del fix (máx. entre 8 m y la
 * precisión) y el intervalo es razonable (1..120 s); si no, null y se
 * conserva lo que informó el teléfono.
 */
object SpeedFallback {
    private const val MIN_LEG_M = 8.0
    private const val MAX_DT_S = 120.0
    /** 180 km/h: por encima es un salto del GPS, no movimiento. */
    private const val MAX_SPEED_MPS = 50.0

    fun impliedSpeedMps(legMeters: Double, dtSeconds: Double, accuracyMeters: Double): Double? {
        if (dtSeconds < 1.0 || dtSeconds > MAX_DT_S) return null
        if (legMeters < maxOf(MIN_LEG_M, accuracyMeters)) return null
        val speed = legMeters / dtSeconds
        return if (speed > MAX_SPEED_MPS) null else speed
    }
}
