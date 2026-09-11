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
}
