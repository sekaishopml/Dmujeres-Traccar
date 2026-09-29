package org.traccar.client

/**
 * Cadencia de captura (pura, testeable en JVM).
 *
 * Dos decisiones separadas:
 * - La PETICIÓN al GPS: con jornada abierta es alta precisión cada 1 s,
 *   continua, se mueva o no el equipo. Es lo que hacía la 2.1.55 con la que
 *   Pilay trazó impecable el 23/09 (un punto cada 2 s, 8 m de precisión, 2-4 %
 *   de batería por hora). Las versiones que bajaban a red/120 s en quietud
 *   dependían de que el sensor avisara el arranque; en Infinix el sensor se
 *   duerme y el equipo viajaba kilómetros con un punto cada 15 min. Sin
 *   jornada, el GPS descansa (red, 120 s).
 * - El REPORTE (qué se guarda): lo filtra PositionProvider por distancia,
 *   giro y latido; en movimiento el latido es la "Frecuencia" del panel y en
 *   quietud 120 s.
 */
object AdaptiveCadence {

    /** Petición al GPS con jornada abierta: continua, 1 Hz. */
    const val TRACKING_GPS_INTERVAL_MS = 1_000L

    /** Latido de reporte en movimiento por defecto (sin distancia recorrida). */
    const val MOVING_INTERVAL_MS = 10_000L

    /** Límites del control remoto "Frecuencia" para el latido en movimiento. */
    const val MIN_MOVING_S = 10L
    const val MAX_MOVING_S = 60L

    /** Latido de reporte en quietud, y petición de red sin jornada. */
    const val STATIONARY_INTERVAL_MS = 120_000L

    /** Desplazamiento mínimo entre fixes sin jornada (m). */
    const val MIN_DISTANCE_M = 10f

    enum class Accuracy { HIGH, BALANCED, LOW }

    data class Request(
        val intervalMs: Long,
        val minDistanceM: Float,
        val maxUpdateDelayMs: Long,
        val accuracy: Accuracy,
    )

    /** Latido de reporte según el estado de movimiento. */
    fun intervalMs(moving: Boolean): Long =
        if (moving) MOVING_INTERVAL_MS else STATIONARY_INTERVAL_MS

    /**
     * Latido en movimiento según la "Frecuencia" del panel
     * (`mobile.intervalSeconds`), acotado a [10, 60] s.
     */
    fun movingIntervalMs(configuredSeconds: Long?): Long {
        val seconds = configuredSeconds?.takeIf { it > 0 } ?: (MOVING_INTERVAL_MS / 1000)
        return seconds.coerceIn(MIN_MOVING_S, MAX_MOVING_S) * 1000
    }

    /**
     * Petición al GPS. Con jornada: alta precisión cada 1 s, sin distancia
     * mínima ni batching (`mobile.accuracy` ya no la baja: una ruta de
     * auditoría no se captura con antenas). Sin jornada: red cada 120 s.
     */
    fun request(journeyOpen: Boolean): Request = if (journeyOpen) {
        Request(
            intervalMs = TRACKING_GPS_INTERVAL_MS,
            minDistanceM = 0f,
            maxUpdateDelayMs = 0L,
            accuracy = Accuracy.HIGH,
        )
    } else {
        Request(
            intervalMs = STATIONARY_INTERVAL_MS,
            minDistanceM = MIN_DISTANCE_M,
            maxUpdateDelayMs = 0L,
            accuracy = Accuracy.BALANCED,
        )
    }
}
