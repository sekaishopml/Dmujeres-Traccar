package com.dmujeres.traccar.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del Tracking Health Engine: consolidación honesta en un estado
 * operativo, separando "sin GPS" de "proceso muerto" de "red caída" de
 * "servidor sin recibir".
 */
class TrackingHealthPolicyTest {

    private fun live(nowMs: Long = 1_000_000L) = TrackingHealthPolicy.Layers(
        trackingEnabled = true,
        serviceRunning = true,
        journeyActive = true,
        nowMs = nowMs,
        lastCallbackAt = nowMs - 5_000L,
        lastAcceptedAt = nowMs - 5_000L,
        lastHeartbeatAt = nowMs - 5_000L,
        lastAckAt = nowMs - 5_000L,
        lastRecoveryAt = 0L,
        recoveryPending = false,
        networkAvailable = true,
        screenOn = true,
    )

    @Test
    fun `captura y ack frescos - LIVE`() {
        assertEquals(TrackingHealthPolicy.STATE_LIVE, TrackingHealthPolicy.evaluate(live()))
    }

    @Test
    fun `jornada terminada - OFFLINE`() {
        val l = live().copy(trackingEnabled = false, journeyActive = false)
        assertEquals(TrackingHealthPolicy.STATE_OFFLINE, TrackingHealthPolicy.evaluate(l))
    }

    @Test
    fun `recuperacion pendiente - RECOVERY`() {
        val l = live().copy(recoveryPending = true, lastRecoveryAt = 900_000L)
        assertEquals(TrackingHealthPolicy.STATE_RECOVERY, TrackingHealthPolicy.evaluate(l))
    }

    @Test
    fun `sin evidencia del proceso 10 min - SILENT sin asumir muerte`() {
        val now = 1_000_000L
        val l = live(now).copy(
            lastCallbackAt = now - 11 * 60_000L,
            lastAcceptedAt = now - 11 * 60_000L,
            lastHeartbeatAt = now - 11 * 60_000L,
        )
        assertEquals(TrackingHealthPolicy.STATE_SILENT, TrackingHealthPolicy.evaluate(l))
    }

    @Test
    fun `heartbeat fresco mantiene NO-SILENT aunque no haya GPS`() {
        // Caso honesto: sin GPS pero el proceso emite heartbeat local → el
        // proceso está vivo; el estado NO puede ser SILENT ni "muerto".
        val now = 1_000_000L
        val l = live(now).copy(
            lastCallbackAt = now - 11 * 60_000L,
            lastAcceptedAt = now - 11 * 60_000L,
            lastHeartbeatAt = now - 60_000L,
        )
        val state = TrackingHealthPolicy.evaluate(l)
        assertTrue(state != TrackingHealthPolicy.STATE_SILENT)
        assertEquals(TrackingHealthPolicy.STATE_DEGRADED, state)
    }

    @Test
    fun `captura fresca sin ack - DEGRADED`() {
        val now = 1_000_000L
        val l = live(now).copy(lastAckAt = now - 5 * 60_000L)
        assertEquals(TrackingHealthPolicy.STATE_DEGRADED, TrackingHealthPolicy.evaluate(l))
    }

    @Test
    fun `red caida con captura fresca - DEGRADED con causa NETWORK_DOWN`() {
        val now = 1_000_000L
        val l = live(now).copy(
            networkAvailable = false,
            lastAckAt = now - 5 * 60_000L,
        )
        assertEquals(TrackingHealthPolicy.STATE_DEGRADED, TrackingHealthPolicy.evaluate(l))
        assertEquals("NETWORK_DOWN", TrackingHealthPolicy.probableCause(l))
    }

    @Test
    fun `screen off sin callbacks - causa SCREEN_OFF_NO_CALLBACK`() {
        val now = 1_000_000L
        val l = live(now).copy(
            screenOn = false,
            lastCallbackAt = now - 6 * 60_000L,
            lastAcceptedAt = now - 6 * 60_000L,
            lastHeartbeatAt = now - 60_000L,
        )
        assertEquals(TrackingHealthPolicy.STATE_DEGRADED, TrackingHealthPolicy.evaluate(l))
        assertEquals("SCREEN_OFF_NO_CALLBACK", TrackingHealthPolicy.probableCause(l))
    }

    @Test
    fun `sin evidencia en absoluto - UNKNOWN por falta de datos`() {
        val now = 1_000_000L
        val l = live(now).copy(
            lastCallbackAt = 0L,
            lastAcceptedAt = 0L,
            lastHeartbeatAt = 0L,
            lastAckAt = 0L,
        )
        assertEquals(TrackingHealthPolicy.STATE_SILENT, TrackingHealthPolicy.evaluate(l))
    }
}
