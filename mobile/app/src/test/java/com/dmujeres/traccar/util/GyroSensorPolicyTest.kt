package com.dmujeres.traccar.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests JVM de GyroSensorPolicy (umbrales rad/s iniciales, observacionales). */
class GyroSensorPolicyTest {

    /** Buffer completo (~15 muestras a 1.5 Hz ≈ 10 s). */
    private val full = 15

    @Test
    fun gyro1_quietoEsSteady() {
        // Equipo en reposo: magnitud ≈ 0 rad/s con jitter mínimo (~0.005).
        val steady = List(full) { i -> 0.05 + (if (i % 2 == 0) 0.005 else -0.005) }
        assertEquals(GyroState.STEADY, GyroSensorPolicy.classify(steady))
        // Sin ruido (stdDev = 0) también STEADY.
        assertEquals(GyroState.STEADY, GyroSensorPolicy.classify(List(full) { 0.0 }))
    }

    @Test
    fun gyro2_girandoEsRotating() {
        // Oscilación amplia de la magnitud (stdDev ≈ 0.4 > 0.35 rad/s).
        val rotating = List(full) { i -> 0.5 + (if (i % 2 == 0) 0.4 else -0.4) }
        assertEquals(GyroState.ROTATING, GyroSensorPolicy.classify(rotating))
    }

    @Test
    fun gyro3_ruidoMedioEsUnknown() {
        // Zona de histéresis (0.02 <= stdDev <= 0.35): UNKNOWN.
        val mid = List(full) { i -> 0.1 + (if (i % 2 == 0) 0.2 else -0.2).coerceAtLeast(0.0) }
        assertEquals(GyroState.UNKNOWN, GyroSensorPolicy.classify(mid))
    }

    @Test
    fun gyro4_pocasMuestrasEsUnknown() {
        // Menos de 6 muestras → UNKNOWN aunque la varianza sea extrema.
        assertEquals(
            GyroState.UNKNOWN,
            GyroSensorPolicy.classify(listOf(0.0, 0.0, 0.0, 0.0, 0.0)),
        )
        assertEquals(
            GyroState.UNKNOWN,
            GyroSensorPolicy.classify(listOf(0.0, 2.0, 0.0, 2.0, 0.0)),
        )
        assertEquals(GyroState.UNKNOWN, GyroSensorPolicy.classify(emptyList()))
    }

    @Test
    fun gyro5_bordesDeUmbral() {
        // Umbral STEADY: justo por debajo (stdDev ≈ 0.01 < 0.02) → STEADY;
        // apenas encima (0.03: no < 0.02) → UNKNOWN.
        assertEquals(
            GyroState.STEADY,
            GyroSensorPolicy.classify(List(full) { i -> 0.1 + (if (i % 2 == 0) 0.01 else -0.01) }),
        )
        assertEquals(
            GyroState.UNKNOWN,
            GyroSensorPolicy.classify(List(full) { i -> 0.1 + (if (i % 2 == 0) 0.03 else -0.03) }),
        )
        // Umbral ROTATING: apenas por encima (stdDev ≈ 0.41 > 0.35) → ROTATING;
        // apenas por debajo (0.3) → UNKNOWN.
        assertEquals(
            GyroState.ROTATING,
            GyroSensorPolicy.classify(List(full) { i -> 0.5 + (if (i % 2 == 0) 0.4 else -0.4) }),
        )
        assertEquals(
            GyroState.UNKNOWN,
            GyroSensorPolicy.classify(List(full) { i -> 0.5 + (if (i % 2 == 0) 0.3 else -0.3) }),
        )
    }
}
