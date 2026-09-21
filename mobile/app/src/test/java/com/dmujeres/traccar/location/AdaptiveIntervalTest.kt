package com.dmujeres.traccar.location

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveIntervalTest {

    @Test
    fun movingUsesIndustryInterval() {
        // R8: recomendación de muestreo = 10 s en movimiento (antes 5 s).
        assertEquals(10L, AdaptiveDistancePolicy.intervalFor(AdaptiveDistancePolicy.Mode.MOVING, 60L))
        assertEquals(10L, AdaptiveDistancePolicy.intervalFor(AdaptiveDistancePolicy.Mode.MOVING, 10L))
    }

    @Test
    fun movingNeverExceedsBase() {
        assertEquals(3L, AdaptiveDistancePolicy.intervalFor(AdaptiveDistancePolicy.Mode.MOVING, 3L))
    }

    @Test
    fun stationaryKeepsBase() {
        assertEquals(10L, AdaptiveDistancePolicy.intervalFor(AdaptiveDistancePolicy.Mode.STATIONARY, 10L))
        assertEquals(30L, AdaptiveDistancePolicy.intervalFor(AdaptiveDistancePolicy.Mode.STATIONARY, 30L))
    }

    @Test
    fun invalidBaseClamped() {
        assertEquals(1L, AdaptiveDistancePolicy.intervalFor(AdaptiveDistancePolicy.Mode.STATIONARY, 0L))
        assertEquals(1L, AdaptiveDistancePolicy.intervalFor(AdaptiveDistancePolicy.Mode.MOVING, -5L))
    }
}
