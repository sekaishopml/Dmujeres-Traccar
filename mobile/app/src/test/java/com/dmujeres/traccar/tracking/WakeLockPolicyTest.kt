package com.dmujeres.traccar.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R7: ventana acotada del wakelock experimental de jornada. */
class WakeLockPolicyTest {

    @Test
    fun ventanaCubreDosCiclosDelGuardian() {
        // Guardián de jornada = 120 s → 2×120 s + 30 s de margen = 270 s.
        assertEquals(270_000L, WakeLockPolicy.windowMs(120_000L))
    }

    @Test
    fun pisoDefensivo() {
        assertEquals(WakeLockPolicy.MIN_WINDOW_MS, WakeLockPolicy.effectiveWindowMs(0L))
        assertEquals(270_000L, WakeLockPolicy.effectiveWindowMs(120_000L))
    }

    @Test
    fun soloConTrackingYJornadaActiva() {
        assertTrue(WakeLockPolicy.shouldHold(trackingEnabled = true, journeyActive = true))
        assertFalse(WakeLockPolicy.shouldHold(trackingEnabled = false, journeyActive = true))
        assertFalse(WakeLockPolicy.shouldHold(trackingEnabled = true, journeyActive = false))
    }
}
