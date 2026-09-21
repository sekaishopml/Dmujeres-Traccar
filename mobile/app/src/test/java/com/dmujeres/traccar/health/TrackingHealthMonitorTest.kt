package com.dmujeres.traccar.health

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FASE 3/4: caracterización del monitor de salud extraído
 * (`TrackingHealthMonitor`) — bucket de idempotencia y contrato de periodos.
 */
class TrackingHealthMonitorTest {

    @Test
    fun `bucket de idempotencia es el minuto de pared`() {
        assertEquals(0L, TrackingHealthMonitor.bucketOf(0L))
        assertEquals(0L, TrackingHealthMonitor.bucketOf(59_999L))
        assertEquals(1L, TrackingHealthMonitor.bucketOf(60_000L))
        assertEquals(2L, TrackingHealthMonitor.bucketOf(120_001L))
    }

    @Test
    fun `periodo y retencion alineados al diseno`() {
        // 5 min de snapshot periódico (12/h) y 24 h de retención (288 filas).
        assertEquals(5L * 60_000L, TrackingHealthMonitor.SNAPSHOT_PERIOD_MS)
        assertEquals(24L * 3_600_000L, TrackingHealthMonitor.RETENTION_MS)
        assertEquals(288L, TrackingHealthMonitor.RETENTION_MS / TrackingHealthMonitor.SNAPSHOT_PERIOD_MS)
    }

    @Test
    fun `tipos de evento estables para el contrato`() {
        assertEquals("HEARTBEAT", TrackingHealthMonitor.EVENT_HEARTBEAT)
        assertEquals("STATE_CHANGE", TrackingHealthMonitor.EVENT_STATE_CHANGE)
        assertEquals("CRITICAL", TrackingHealthMonitor.EVENT_CRITICAL)
    }
}
