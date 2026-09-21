package com.dmujeres.traccar.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F0: embudo por bucket — deltas correctos, clamp por reinicio y JSON. */
class HealthFunnelPolicyTest {

    private fun counters(
        received: Long,
        rejected: Long,
        reasons: Map<String, Long>,
        enqueued: Long,
        acked: Long,
    ) = HealthFunnelPolicy.Counters(received, rejected, reasons, enqueued, acked)

    @Test
    fun parseaDesgloseCompacto() {
        val parsed = HealthFunnelPolicy.parseBreakdown("accuracy:3|implied:10|stale:0")
        assertEquals(3L, parsed["accuracy"])
        assertEquals(10L, parsed["implied"])
        assertEquals(null, parsed["stale"]) // 0 no se reporta
        assertTrue(HealthFunnelPolicy.parseBreakdown(null).isEmpty())
    }

    @Test
    fun deltaCalculaDiferenciaPorBucket() {
        val prev = counters(10, 3, mapOf("accuracy" to 2, "stale" to 1), 7, 5)
        val now = counters(16, 5, mapOf("accuracy" to 3, "stale" to 2), 11, 9)
        val delta = HealthFunnelPolicy.delta(prev, now, movingSeconds = 120, windowSeconds = 300)
        assertEquals(6L, delta.received)
        assertEquals(2L, delta.rejected)
        assertEquals(1L, delta.rejectedByReason["accuracy"])
        assertEquals(1L, delta.rejectedByReason["stale"])
        assertEquals(4L, delta.enqueued)
        assertEquals(4L, delta.acked)
        assertEquals(120L, delta.movingSeconds)
    }

    @Test
    fun reinicioClampeaACeroNoResta() {
        val prev = counters(100, 20, mapOf("accuracy" to 10), 80, 70)
        val now = counters(2, 1, mapOf("accuracy" to 1), 1, 0)
        val delta = HealthFunnelPolicy.delta(prev, now, movingSeconds = 30, windowSeconds = 300)
        assertEquals(0L, delta.received)
        assertEquals(0L, delta.rejected)
        assertEquals(0L, delta.enqueued)
        assertEquals(0L, delta.acked)
        assertTrue(delta.rejectedByReason.isEmpty())
    }

    @Test
    fun primeraLecturaEsLineaBaseSinInventarDelta() {
        // Honestidad: sin lectura previa NO se puede saber qué pasó en el
        // bucket; los contadores acumulados de prefs no son del bucket. El
        // primer consume solo fija la base (delta 0) y el movimiento sí cuenta.
        val delta = HealthFunnelPolicy.delta(
            previous = null,
            current = counters(5, 1, mapOf("stale" to 1), 4, 3),
            movingSeconds = 60,
            windowSeconds = 300,
        )
        assertEquals(0L, delta.received)
        assertEquals(0L, delta.enqueued)
        assertEquals(0L, delta.acked)
        assertTrue(delta.rejectedByReason.isEmpty())
        assertEquals(60L, delta.movingSeconds)
    }

    @Test
    fun jsonCompactoEstable() {
        val delta = HealthFunnelPolicy.Delta(
            received = 6, rejected = 2, rejectedByReason = mapOf("stale" to 2),
            enqueued = 4, acked = 4, movingSeconds = 120, windowSeconds = 300,
        )
        assertEquals(
            "{\"rx\":6,\"rj\":2,\"rr\":{\"stale\":2},\"en\":4,\"ak\":4,\"mv\":120,\"w\":300}",
            HealthFunnelPolicy.toJson(delta),
        )
    }

    @Test
    fun acumuladorSumaSoloEnMovimientoYRota() {
        val acc = HealthFunnelAccumulator()
        acc.tick(moving = false, nowMs = 1_000_000L)
        acc.tick(moving = true, nowMs = 1_030_000L) // 30 s moviendo
        acc.tick(moving = true, nowMs = 1_060_000L) // +30 s
        val delta = acc.consume(counters(3, 0, emptyMap(), 3, 2), windowSeconds = 300)
        assertEquals(60L, delta.movingSeconds)
        // Segunda ventana arranca limpia.
        acc.tick(moving = false, nowMs = 1_090_000L)
        val next = acc.consume(counters(3, 0, emptyMap(), 3, 3), windowSeconds = 300)
        assertEquals(0L, next.movingSeconds)
        assertEquals(1L, next.acked)
    }
}
