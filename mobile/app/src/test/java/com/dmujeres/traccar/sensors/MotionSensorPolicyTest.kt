package com.dmujeres.traccar.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionSensorPolicyTest {

    private fun quietMagnitudes(n: Int, gravity: Double = 9.81): List<Double> =
        List(n) { i -> gravity + (if (i % 2 == 0) 0.05 else -0.05) }

    @Test
    fun policy1_lowVarianceIsStationary() {
        // Parado: magnitud ≈ gravedad con ruido mínimo (< 0.15 m/s²).
        assertEquals(
            MotionState.STATIONARY,
            MotionSensorPolicy.classify(quietMagnitudes(10)),
        )
        assertEquals(
            MotionState.STATIONARY,
            MotionSensorPolicy.classify(quietMagnitudes(10, gravity = 9.78)),
        )
    }

    @Test
    fun policy2_highVarianceIsMoving() {
        // Movido: oscilación grande alrededor de la gravedad (stdDev > 0.6).
        val moving = List(10) { i -> 9.81 + (if (i % 2 == 0) 1.5 else -1.5) }
        assertEquals(MotionState.MOVING, MotionSensorPolicy.classify(moving))
    }

    @Test
    fun policy3_mediumNoiseIsUnknown() {
        // Ruido medio (~0.3 stdDev): zona de histéresis → UNKNOWN.
        val mid = List(10) { i -> 9.81 + (if (i % 2 == 0) 0.3 else -0.3) }
        assertEquals(MotionState.UNKNOWN, MotionSensorPolicy.classify(mid))
    }

    @Test
    fun policy4_fewSamplesIsUnknown() {
        // < 6 muestras → UNKNOWN aunque la varianza sea extrema.
        assertEquals(
            MotionState.UNKNOWN,
            MotionSensorPolicy.classify(listOf(9.81, 9.81, 9.81, 9.81, 9.81)),
        )
        assertEquals(
            MotionState.UNKNOWN,
            MotionSensorPolicy.classify(listOf(8.0, 12.0, 8.0, 12.0, 8.0)),
        )
    }

    @Test
    fun policy5_gravityOffsetDoesNotChangeVerdict() {
        // La clasificación depende de la desviación, no del valor absoluto:
        // offset de calibración (+0.4) no cambia STATIONARY.
        val biased = List(10) { i -> 10.21 + (if (i % 2 == 0) 0.05 else -0.05) }
        assertEquals(MotionState.STATIONARY, MotionSensorPolicy.classify(biased))
        // Y el mean cercano a gravedad real también se clasifica igual con
        // la gravedad explícita del dispositivo (~9.8).
        assertEquals(
            MotionState.STATIONARY,
            MotionSensorPolicy.classify(quietMagnitudes(10), gravity = 9.8),
        )
        // Vacío → UNKNOWN (sin datos).
        assertEquals(MotionState.UNKNOWN, MotionSensorPolicy.classify(emptyList()))
    }

    @Test
    fun policy6_thresholdBoundaries() {
        // stdDev exactamente 0.15 (no < 0.15) → UNKNOWN (frontera histéresis).
        val exact = List(10) { i -> 9.81 + (if (i % 2 == 0) 0.15 else -0.15) }
        assertEquals(MotionState.UNKNOWN, MotionSensorPolicy.classify(exact))
        // stdDev exactamente 0.6 (no > 0.6) → UNKNOWN (frontera histéresis).
        val mid = List(10) { i -> 9.81 + (if (i % 2 == 0) 0.6 else -0.6) }
        assertEquals(MotionState.UNKNOWN, MotionSensorPolicy.classify(mid))
        // Un paso más allá de 0.6 → MOVING.
        val over = List(10) { i -> 9.81 + (if (i % 2 == 0) 0.65 else -0.65) }
        assertEquals(MotionState.MOVING, MotionSensorPolicy.classify(over))
    }

    @Test
    fun policy7_movingMappingFromPureState() {
        // Mapeo del veredicto puro al significado de isMoving(): STATIONARY →
        // false, MOVING → true, UNKNOWN → null (sin datos).
        val stationary = MotionSensorPolicy.classify(quietMagnitudes(10))
        val unknown = MotionSensorPolicy.classify(listOf(9.81))
        val moving = MotionSensorPolicy.classify(List(10) { i -> 9.81 + (if (i % 2 == 0) 1.5 else -1.5) })
        assertEquals(false, stationary == MotionState.MOVING)
        assertEquals(null, if (unknown == MotionState.UNKNOWN) null else false)
        assertEquals(true, moving == MotionState.MOVING)
    }
}
