package com.dmujeres.traccar.outbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpFlushPolicyTest {

    private val now = 1_000_000_000_000L

    @Test
    fun emptyQueueNeverFlushes() {
        assertFalse(
            HttpFlushPolicy.shouldFlush(
                pendingCount = 0,
                mqttDelivering = false,
                lastAckAt = 0L,
                oldestPendingAt = null,
                now = now,
            )
        )
    }

    @Test
    fun disconnectedWithPendingFlushes() {
        assertTrue(
            HttpFlushPolicy.shouldFlush(
                pendingCount = 3957,
                mqttDelivering = false,
                lastAckAt = now - 1_000L,
                oldestPendingAt = now - 1_000L,
                now = now,
            )
        )
    }

    @Test
    fun connectedWithoutAnyAckFlushes() {
        assertTrue(
            HttpFlushPolicy.shouldFlush(
                pendingCount = 10,
                mqttDelivering = true,
                lastAckAt = 0L,
                oldestPendingAt = now - 1_000L,
                now = now,
            )
        )
    }

    @Test
    fun connectedWithStaleAckFlushes() {
        assertTrue(
            HttpFlushPolicy.shouldFlush(
                pendingCount = 10,
                mqttDelivering = true,
                lastAckAt = now - HttpFlushPolicy.STUCK_WITHOUT_ACK_MS - 1_000L,
                oldestPendingAt = now - 1_000L,
                now = now,
            )
        )
    }

    @Test
    fun connectedWithFreshAckAndYoungBacklogDoesNotFlush() {
        assertFalse(
            HttpFlushPolicy.shouldFlush(
                pendingCount = 10,
                mqttDelivering = true,
                lastAckAt = now - 30_000L,
                oldestPendingAt = now - 60_000L,
                now = now,
            )
        )
    }

    @Test
    fun freshPresenceAcksDoNotHideAgingPositionBacklog() {
        // Caso del atasco: MQTT "conectado", presencias confirmadas hace 30 s
        // (lastAck fresco) pero el pendiente más viejo supera los 10 min.
        assertTrue(
            HttpFlushPolicy.shouldFlush(
                pendingCount = 3957,
                mqttDelivering = true,
                lastAckAt = now - 30_000L,
                oldestPendingAt = now - HttpFlushPolicy.BACKLOG_AGE_MS - 1_000L,
                now = now,
            )
        )
    }

    @Test
    fun unknownOldestAgeFlushesWhenDelivering() {
        // Filas legacy con enqueuedAt = 0: no se puede probar que sean frescas.
        assertTrue(
            HttpFlushPolicy.shouldFlush(
                pendingCount = 5,
                mqttDelivering = true,
                lastAckAt = now - 30_000L,
                oldestPendingAt = null,
                now = now,
            )
        )
    }
}
