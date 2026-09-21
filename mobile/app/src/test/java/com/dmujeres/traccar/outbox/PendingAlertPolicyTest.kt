package com.dmujeres.traccar.outbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingAlertPolicyTest {

    private val now = 1_000_000_000_000L

    @Test
    fun emptyQueueNeverAbnormal() {
        assertFalse(
            PendingAlertPolicy.isAbnormal(
                pendingCount = 0,
                oldestPendingAt = null,
                lastAckAt = 0L,
                now = now,
            )
        )
    }

    @Test
    fun fewFreshWithRecentAckNotAbnormal() {
        // Caso sano: 1-5 pendientes recientes con ACKs al día. No debe alarmar.
        assertFalse(
            PendingAlertPolicy.isAbnormal(
                pendingCount = 3,
                oldestPendingAt = now - 30_000L,
                lastAckAt = now - 10_000L,
                now = now,
            )
        )
    }

    @Test
    fun singleInFlightPendingNotAbnormal() {
        // El dispatch secuencial deja >= 1 pendiente casi siempre: es normal.
        assertFalse(
            PendingAlertPolicy.isAbnormal(
                pendingCount = 1,
                oldestPendingAt = now - 5_000L,
                lastAckAt = now - 5_000L,
                now = now,
            )
        )
    }

    @Test
    fun manyYoungStillAbnormal() {
        // Acumulación rápida: > 30 aunque sean jóvenes.
        assertTrue(
            PendingAlertPolicy.isAbnormal(
                pendingCount = 31,
                oldestPendingAt = now - 30_000L,
                lastAckAt = now - 10_000L,
                now = now,
            )
        )
    }

    @Test
    fun oldBacklogAbnormal() {
        assertTrue(
            PendingAlertPolicy.isAbnormal(
                pendingCount = 3,
                oldestPendingAt = now - PendingAlertPolicy.ABNORMAL_AGE_MS - 1_000L,
                lastAckAt = now - 30_000L,
                now = now,
            )
        )
    }

    @Test
    fun stuckWithoutAckAbnormal() {
        // MQTT "listo" pero sin ACK > 2 min con cola: atascado aunque el
        // pendiente más viejo aún sea joven.
        assertTrue(
            PendingAlertPolicy.isAbnormal(
                pendingCount = 3,
                oldestPendingAt = now - 30_000L,
                lastAckAt = now - PendingAlertPolicy.STUCK_WITHOUT_ACK_MS - 1_000L,
                now = now,
            )
        )
    }

    @Test
    fun neverAckedStartupNotAbnormal() {
        // Arranque: 1-2 pendientes jóvenes y aún sin ningún ACK. No debe
        // alarmar de inmediato (lastAckAt = 0 no cuenta como "sin ACK").
        assertFalse(
            PendingAlertPolicy.isAbnormal(
                pendingCount = 2,
                oldestPendingAt = now - 20_000L,
                lastAckAt = 0L,
                now = now,
            )
        )
    }

    @Test
    fun unknownOldestAgeAbnormal() {
        // Filas legacy con enqueuedAt = 0: no se puede probar que sean frescas.
        assertTrue(
            PendingAlertPolicy.isAbnormal(
                pendingCount = 5,
                oldestPendingAt = null,
                lastAckAt = now - 30_000L,
                now = now,
            )
        )
    }
}
