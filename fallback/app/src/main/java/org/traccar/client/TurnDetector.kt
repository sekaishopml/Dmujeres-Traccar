package org.traccar.client

import kotlin.math.abs

/**
 * Detector puro de giros fuertes (velocidad angular sostenida) para pedir
 * fixes extra en las esquinas sin subir la cadencia base.
 *
 * Un giro dispara una sola vez: hace falta que el yaw baje del umbral (o que
 * pase el enfriamiento) para volver a armarse. Así una misma esquina no genera
 * refuerzos repetidos.
 */
class TurnDetector(
    private val yawThresholdDegPerSec: Double = DEFAULT_YAW_THRESHOLD_DEG_PER_SEC,
    private val minSpeedKnots: Double = DEFAULT_MIN_SPEED_KNOTS,
    private val sustainMs: Long = DEFAULT_SUSTAIN_MS,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {

    private var aboveSinceMs = NOT_ACTIVE
    private var armed = true
    private var lastTriggerMs = NOT_ACTIVE

    /**
     * Alimenta una muestra del giroscopio. Devuelve true solo cuando el giro
     * ya se sostuvo en la ventana y el vehículo va en marcha.
     */
    fun onSample(yawDegPerSec: Double, speedKnots: Double, nowMs: Long): Boolean {
        if (speedKnots <= minSpeedKnots || abs(yawDegPerSec) < yawThresholdDegPerSec) {
            aboveSinceMs = NOT_ACTIVE
            return false
        }
        // Re-arme por tiempo: una eses seguidas no deberían exigir una muestra
        // por debajo del umbral entre giro y giro.
        if (!armed && nowMs - lastTriggerMs >= cooldownMs) {
            armed = true
        }
        if (!armed) return false
        if (aboveSinceMs == NOT_ACTIVE) aboveSinceMs = nowMs
        if (nowMs - aboveSinceMs < sustainMs) return false
        armed = false
        lastTriggerMs = nowMs
        aboveSinceMs = NOT_ACTIVE
        return true
    }

    /** Vuelve el detector a su estado inicial (al parar o soltar el giroscopio). */
    fun reset() {
        aboveSinceMs = NOT_ACTIVE
        armed = true
        lastTriggerMs = NOT_ACTIVE
    }

    companion object {
        /** Giro fuerte: ~25°/s de velocidad angular sostenida. */
        const val DEFAULT_YAW_THRESHOLD_DEG_PER_SEC = 25.0

        /** Solo cuenta con el vehículo en marcha: > ~3 nudos. */
        const val DEFAULT_MIN_SPEED_KNOTS = 3.0

        /** Ventana mínima sobre el umbral (filtra picos de una sola muestra). */
        const val DEFAULT_SUSTAIN_MS = 300L

        /** Enfriamiento mínimo entre giros distintos. */
        const val DEFAULT_COOLDOWN_MS = 2_000L

        private const val NOT_ACTIVE = Long.MIN_VALUE
    }
}
