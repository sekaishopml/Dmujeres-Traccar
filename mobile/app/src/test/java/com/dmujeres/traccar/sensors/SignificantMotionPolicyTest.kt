package com.dmujeres.traccar.sensors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R7: el sensor de movimiento significativo no debe disparar en ráfaga. */
class SignificantMotionPolicyTest {

    @Test
    fun primerDisparoSiemprePasa() {
        assertTrue(SignificantMotionPolicy.shouldFire(lastTriggerAtMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun dentroDelCooldownNoPasa() {
        assertFalse(
            SignificantMotionPolicy.shouldFire(
                lastTriggerAtMs = 10_000L, nowMs = 10_000L + SignificantMotionPolicy.COOLDOWN_MS - 1,
            )
        )
    }

    @Test
    fun alCumplirCooldownPasa() {
        assertTrue(
            SignificantMotionPolicy.shouldFire(
                lastTriggerAtMs = 10_000L, nowMs = 10_000L + SignificantMotionPolicy.COOLDOWN_MS,
            )
        )
    }

    @Test
    fun cooldownConfigurableSeRespeta() {
        assertTrue(SignificantMotionPolicy.shouldFire(1_000L, 2_000L, cooldownMs = 500L))
        assertFalse(SignificantMotionPolicy.shouldFire(1_000L, 1_400L, cooldownMs = 500L))
    }
}
