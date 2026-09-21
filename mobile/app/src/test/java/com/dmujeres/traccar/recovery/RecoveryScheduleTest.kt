package com.dmujeres.traccar.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEGUNDO PLANO BLINDADO: el periódico no debe exigir red (mataría el drenaje
 * offline) y el one-shot inmediato solo tiene sentido con jornada activa.
 * Puro (JVM, sin Android).
 */
class RecoveryScheduleTest {

    @Test
    fun periodicDoesNotRequireNetwork() {
        // REQUIRED_NETWORK mataría el drenaje offline: el outbox debe drenar
        // cuando vuelva red, no solo cuando WorkManager crea que hay red.
        assertFalse(RecoverySchedule.requiresNetwork())
    }

    @Test
    fun periodicAndBackoffConstants() {
        assertEquals(15L, RecoverySchedule.PERIODIC_MINUTES)
        assertEquals(10L, RecoverySchedule.BACKOFF_MINUTES)
    }

    @Test
    fun immediateOnlyWithTrackingActive() {
        assertTrue(RecoverySchedule.shouldEnqueueImmediate(trackingEnabled = true))
        assertFalse(RecoverySchedule.shouldEnqueueImmediate(trackingEnabled = false))
    }

    @Test
    fun reconnectOnlyWithTrackingActive() {
        assertTrue(RecoverySchedule.shouldEnqueueOnReconnect(trackingEnabled = true))
        assertFalse(RecoverySchedule.shouldEnqueueOnReconnect(trackingEnabled = false))
    }
}
