package com.dmujeres.traccar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconexión blindada: al volver red solo se empuja worker/servicio si la
 * jornada sigue activa (sin jornada no hay nada que re-registrar).
 * Puro (JVM, sin Android).
 */
class ReconnectPolicyTest {

    @Test
    fun reregistersGpsOnlyWithJourneyActive() {
        assertTrue(ReconnectPolicy.shouldReregisterGps(trackingEnabled = true))
        assertFalse(ReconnectPolicy.shouldReregisterGps(trackingEnabled = false))
    }

    @Test
    fun expeditesOnlyWithJourneyActive() {
        assertTrue(ReconnectPolicy.shouldExpediteOnReconnect(trackingEnabled = true))
        assertFalse(ReconnectPolicy.shouldExpediteOnReconnect(trackingEnabled = false))
    }
}
