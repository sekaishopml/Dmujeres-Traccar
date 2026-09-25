package org.traccar.client

/**
 * Cadencia adaptativa de la petición de ubicaciones (pura, testeable en JVM).
 *
 * Decisión del dueño: en movimiento se pide fino (vista en vivo fresca, sin
 * batching) para que el trazo siga la vía; parado se pide grueso y con batching
 * para bajar el consumo. Los proveedores traducen esta decisión a su API
 * (FusedLocationProviderClient / LocationManager).
 */
object AdaptiveCadence {

    /** Petición en movimiento: trazo denso sin volver a 1 Hz (834 mAh/24 h). */
    const val MOVING_INTERVAL_MS = 10_000L

    /** Límites del control remoto "Frecuencia" para la cadencia en movimiento. */
    const val MIN_MOVING_S = 10L
    const val MAX_MOVING_S = 60L

    /** Petición en quietud: sin cambios reales no hace falta más. */
    const val STATIONARY_INTERVAL_MS = 120_000L

    /** Desplazamiento mínimo entre fixes (m): filtra jitter sin cortar esquinas. */
    const val MIN_DISTANCE_M = 10f

    /**
     * Sin batching: el batching en parado estiraba los puntos de 2 min a 10-60 min
     * cuando el teléfono entraba en doze (ruta con huecos aunque estuviera quieto
     * y, peor, al arrancar de nuevo). El ahorro de parado se mantiene con los 120 s.
     */
    const val STATIONARY_MAX_UPDATE_DELAY_MS = 0L

    enum class Accuracy { HIGH, BALANCED, LOW }

    data class Request(
        val intervalMs: Long,
        val minDistanceM: Float,
        val maxUpdateDelayMs: Long,
        val accuracy: Accuracy,
    )

    fun intervalMs(moving: Boolean): Long =
        if (moving) MOVING_INTERVAL_MS else STATIONARY_INTERVAL_MS

    /**
     * Cadencia en movimiento según el control remoto "Frecuencia" del panel
     * (`mobile.intervalSeconds`), acotada a [15, 60] s para no volver a 1 Hz ni
     * perder el trazo. Sin valor válido manda el default de 15 s.
     */
    fun movingIntervalMs(configuredSeconds: Long?): Long {
        val seconds = configuredSeconds?.takeIf { it > 0 } ?: (MOVING_INTERVAL_MS / 1000)
        return seconds.coerceIn(MIN_MOVING_S, MAX_MOVING_S) * 1000
    }

    /**
     * Petición según estado. En movimiento manda `mobile.accuracy` (default
     * alta para no perder el trazo) y la frecuencia configurada; parado
     * siempre BALANCED y 120 s (ahorro).
     */
    fun request(
        moving: Boolean,
        configuredAccuracy: String?,
        configuredIntervalSeconds: Long? = null,
    ): Request = if (moving) {
        Request(
            intervalMs = movingIntervalMs(configuredIntervalSeconds),
            minDistanceM = MIN_DISTANCE_M,
            maxUpdateDelayMs = 0L,
            accuracy = accuracyFrom(configuredAccuracy, Accuracy.HIGH),
        )
    } else {
        Request(
            intervalMs = STATIONARY_INTERVAL_MS,
            minDistanceM = MIN_DISTANCE_M,
            maxUpdateDelayMs = STATIONARY_MAX_UPDATE_DELAY_MS,
            accuracy = Accuracy.BALANCED,
        )
    }

    /** `mobile.accuracy` ("high"/"medium"/"low") → precisión; inválido = default. */
    fun accuracyFrom(value: String?, default: Accuracy): Accuracy = when (value) {
        "high" -> Accuracy.HIGH
        "medium" -> Accuracy.BALANCED
        "low" -> Accuracy.LOW
        else -> default
    }
}
