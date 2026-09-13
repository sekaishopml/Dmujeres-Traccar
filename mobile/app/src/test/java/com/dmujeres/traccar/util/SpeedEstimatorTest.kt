package com.dmujeres.traccar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedEstimatorTest {

    @Test
    fun dopplerTrustedWhenPresent() {
        assertEquals(11f, SpeedEstimator.effectiveMps(11f, 2f)!!, 0.001f)
    }

    @Test
    fun fallsBackToImpliedWhenDopplerZero() {
        // Caso ZTE: Doppler en 0 en marcha → manda la geometría.
        assertEquals(10.5f, SpeedEstimator.effectiveMps(0f, 10.5f)!!, 0.001f)
        assertEquals(10.5f, SpeedEstimator.effectiveMps(null, 10.5f)!!, 0.001f)
    }

    @Test
    fun nullWhenNothing() {
        assertNull(SpeedEstimator.effectiveMps(null, null))
        assertNull(SpeedEstimator.effectiveMps(0f, null))
    }

    @Test
    fun impliedNeedsPositiveDt() {
        assertNull(SpeedEstimator.impliedMps(-2.2, -79.88, 1000L, -2.2, -79.88, 1000L))
        assertNull(SpeedEstimator.impliedMps(-2.2, -79.88, 2000L, -2.2, -79.88, 1000L))
    }

    @Test
    fun impliedCityDriving() {
        // 110 m en 10 s ≈ 11 m/s (≈40 km/h).
        val implied = SpeedEstimator.impliedMps(-2.20, -79.88, 0L, -2.19901, -79.88, 10_000L)
        assertEquals(11.0f, implied!!, 0.5f)
    }

    @Test
    fun impliedCappedByMaxDoesNotInventSpeed() {
        // REGRESSION JOSEPH: salto de reloj/teleport con implícita absurda no
        // debe inventar velocidad: con tope 45 m/s cae a desconocida.
        assertNull(SpeedEstimator.effectiveMps(0f, 150f, 45f))
        assertEquals("unknown", SpeedEstimator.speedSource(0f, 150f, 45f))
        // Dentro del tope sí respalda al Doppler atascado.
        assertEquals(25f, SpeedEstimator.effectiveMps(0f, 25f, 45f)!!, 0.001f)
        assertEquals("implied", SpeedEstimator.speedSource(0f, 25f, 45f))
        assertEquals("doppler", SpeedEstimator.speedSource(11f, 2f, 45f))
        assertEquals("unknown", SpeedEstimator.speedSource(null, null, 45f))
        assertEquals("unknown", SpeedEstimator.speedSource(0f, null, 45f))
    }
}
