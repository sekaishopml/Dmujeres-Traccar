package com.dmujeres.traccar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixFilterTest {

    private fun windowOf(vararg fixes: FixFilter.RecentFix): List<FixFilter.RecentFix> = fixes.toList()

    private fun fix(
        lat: Double = 19.4326,
        lon: Double = -99.1332,
        acc: Float = 10f,
        timeMs: Long = 1_000_000L,
        elapsed: Long = 1_000_000_000_000L,
    ) = FixFilter.RecentFix(lat, lon, acc, timeMs, elapsed)

    @Test
    fun firstFixGoodAccepts() {
        val decision = FixFilter.evaluate(
            lat = 19.4326, lon = -99.1332, accuracyM = 30f,
            wallTimeMs = 1_000_000L, elapsedNanos = 1_000_000_000_000L,
            nowElapsedNanos = 1_000_000_000_000L,
            window = emptyList(),
        )
        assertTrue(decision is FixFilter.Decision.Accept)
        assertFalse((decision as FixFilter.Decision.Accept).lowQuality)
    }

    @Test
    fun firstFixBadRejectsWithoutFounding() {
        val decision = FixFilter.evaluate(
            lat = 19.4326, lon = -99.1332, accuracyM = 200f,
            wallTimeMs = 1_000_000L, elapsedNanos = 1_000_000_000_000L,
            nowElapsedNanos = 1_000_000_000_000L,
            window = emptyList(),
        )
        assertTrue(decision is FixFilter.Decision.Reject)
        assertEquals("first_fix_bad", (decision as FixFilter.Decision.Reject).reason)
    }

    @Test
    fun firstFixBoundary150Rejects() {
        val decision = FixFilter.evaluate(
            lat = 19.4326, lon = -99.1332, accuracyM = 150f,
            wallTimeMs = 1_000_000L, elapsedNanos = 1_000_000_000_000L,
            nowElapsedNanos = 1_000_000_000_000L,
            window = emptyList(),
        )
        assertEquals("first_fix_bad", (decision as FixFilter.Decision.Reject).reason)
    }

    @Test
    fun accuracyCeilingRejects() {
        val w = windowOf(fix())
        val decision = FixFilter.evaluate(
            lat = 19.4326, lon = -99.1332, accuracyM = 500f,
            wallTimeMs = 2_000_000L, elapsedNanos = 1_001_000_000_000L,
            nowElapsedNanos = 1_001_000_000_000L,
            window = w,
        )
        assertEquals("accuracy_ceiling", (decision as FixFilter.Decision.Reject).reason)
    }

    @Test
    fun staleFixRejects() {
        val w = windowOf(fix(timeMs = 1_000_000L, elapsed = 1_000_000_000_000L))
        val now = 1_000_000_000_000L + FixFilter.STALE_AFTER_NANOS + 1_000_000_000L
        val decision = FixFilter.evaluate(
            lat = 19.4326, lon = -99.1332, accuracyM = 10f,
            wallTimeMs = 2_000_000L, elapsedNanos = 1_000_000_000_000L,
            nowElapsedNanos = now,
            window = w,
        )
        assertEquals("stale", (decision as FixFilter.Decision.Reject).reason)
    }

    @Test
    fun duplicateWallTimeWithBadAccuracyRejects() {
        val w = windowOf(fix(timeMs = 1_000_000L, acc = 10f, elapsed = 1_000_000_000_000L))
        val decision = FixFilter.evaluate(
            lat = 19.4326, lon = -99.1332, accuracyM = 50f,
            wallTimeMs = 1_000_000L, // duplicado
            elapsedNanos = 1_001_000_000_000L, nowElapsedNanos = 1_001_000_000_000L,
            window = w,
        )
        assertEquals("stale_relay", (decision as FixFilter.Decision.Reject).reason)
    }

    @Test
    fun duplicateWallTimeSameCoordsRejectsEvenIfGood() {
        val w = windowOf(fix(timeMs = 1_000_000L, acc = 10f, elapsed = 1_000_000_000_000L))
        val decision = FixFilter.evaluate(
            lat = 19.4326, lon = -99.1332, accuracyM = 10f,
            wallTimeMs = 1_000_000L,
            elapsedNanos = 1_001_000_000_000L, nowElapsedNanos = 1_001_000_000_000L,
            window = w,
        )
        // Re-entrega cacheada del mismo punto de red: ni con accuracy buena se encola.
        assertEquals("stale_relay", (decision as FixFilter.Decision.Reject).reason)
    }

    @Test
    fun duplicateWallTimeWithMovedCoordsAccepts() {
        val w = windowOf(fix(timeMs = 1_000_000L, acc = 10f, elapsed = 1_000_000_000_000L))
        val decision = FixFilter.evaluate(
            lat = 19.4336, lon = -99.1332, accuracyM = 10f, // ~111 m más al norte
            wallTimeMs = 1_000_000L,
            elapsedNanos = 1_001_000_000_000L, nowElapsedNanos = 1_001_000_000_000L,
            window = w,
        )
        assertTrue(decision is FixFilter.Decision.Accept)
    }

    @Test
    fun retrogradeWallTimeSameCoordsRejects() {
        val w = windowOf(fix(timeMs = 2_000_000L, acc = 10f, elapsed = 2_000_000_000_000L))
        val bad = FixFilter.evaluate(
            lat = 19.4326, lon = -99.1332, accuracyM = 30f,
            wallTimeMs = 1_000_000L, // retrocede
            elapsedNanos = 2_001_000_000_000L, nowElapsedNanos = 2_001_000_000_000L,
            window = w,
        )
        assertEquals("stale_relay", (bad as FixFilter.Decision.Reject).reason)
    }

    @Test
    fun degradedFixRejectedWhenGoodInWindow() {
        val w = windowOf(fix(acc = 10f))
        val decision = FixFilter.evaluate(
            lat = 19.4327, lon = -99.1333, accuracyM = 120f,
            wallTimeMs = 1_010_000L, elapsedNanos = 1_010_000_000_000L,
            nowElapsedNanos = 1_010_000_000_000L,
            window = w,
        )
        assertEquals("degraded", (decision as FixFilter.Decision.Reject).reason)
    }

    @Test
    fun badStreakErasesGoodMemoryAfterWindowFills() {
        // Ventana honesta: 6 evaluados malos seguidos expulsan al bueno.
        val bads = (0 until 6).map { i ->
            fix(acc = 120f, timeMs = 2_000_000L + i * 10_000L, elapsed = 2_000_000_000_000L + i * 10_000_000_000L)
        }
        val decision = FixFilter.evaluate(
            lat = 19.4327, lon = -99.1333, accuracyM = 120f,
            wallTimeMs = 3_000_000L, elapsedNanos = 3_000_000_000_000L,
            nowElapsedNanos = 3_000_000_000_000L,
            window = bads,
        )
        // Sin ningún bueno en TODA la ventana, el degradado ya no aplica: se acepta marcado.
        assertTrue(decision is FixFilter.Decision.Accept)
        assertTrue((decision as FixFilter.Decision.Accept).lowQuality)
    }

    @Test
    fun lowQualityFlagRange() {
        assertFalse(FixFilter.isLowQuality(10f))
        assertFalse(FixFilter.isLowQuality(79f))
        assertTrue(FixFilter.isLowQuality(80f))
        assertTrue(FixFilter.isLowQuality(150f))
        assertTrue(FixFilter.isLowQuality(499f))
        assertFalse(FixFilter.isLowQuality(500f))

        val degraded = FixFilter.evaluate(
            lat = 19.4326, lon = -99.1332, accuracyM = 100f,
            wallTimeMs = 1_010_000L, elapsedNanos = 1_010_000_000_000L,
            nowElapsedNanos = 1_010_000_000_000L,
            window = windowOf(fix(acc = 120f, timeMs = 1_000_000L)),
        )
        // Sin bueno en ventana (120 > 20), 100 m se acepta pero marcado.
        assertTrue(degraded is FixFilter.Decision.Accept)
        assertTrue((degraded as FixFilter.Decision.Accept).lowQuality)
    }

    @Test
    fun impliedSpeedRejectsTeleport() {
        val w = windowOf(fix(lat = 19.4326, lon = -99.1332, acc = 10f, timeMs = 1_000_000L))
        // ~11 km en 10 s = 1100 m/s >> 45 m/s.
        val decision = FixFilter.evaluate(
            lat = 19.5326, lon = -99.1332, accuracyM = 10f,
            wallTimeMs = 1_010_000L, elapsedNanos = 1_010_000_000_000L,
            nowElapsedNanos = 1_010_000_000_000L,
            window = w,
        )
        assertEquals("implied_speed", (decision as FixFilter.Decision.Reject).reason)
    }

    @Test
    fun consistentMotionToleratesPeak() {
        // Ventana con movimiento sostenido > 30 m/s: dos fixes a ~400 m en 10 s (40 m/s).
        val a = fix(lat = 19.4326, lon = -99.1332, acc = 10f, timeMs = 1_000_000L)
        // 400 m al norte ≈ 0.0036° lat.
        val b = fix(lat = 19.4362, lon = -99.1332, acc = 10f, timeMs = 1_010_000L)
        val w = windowOf(a, b)
        // Salto de ~600 m en 10 s = 60 m/s > 45, pero con movimiento consistente se tolera.
        val decision = FixFilter.evaluate(
            lat = 19.4416, lon = -99.1332, accuracyM = 10f,
            wallTimeMs = 1_020_000L, elapsedNanos = 1_020_000_000_000L,
            nowElapsedNanos = 1_020_000_000_000L,
            window = w,
        )
        assertTrue(decision is FixFilter.Decision.Accept)
    }

    @Test
    fun invalidCoordsReject() {
        val decision = FixFilter.evaluate(
            lat = 95.0, lon = -99.1332, accuracyM = 10f,
            wallTimeMs = 1_000_000L, elapsedNanos = 1_000_000_000_000L,
            nowElapsedNanos = 1_000_000_000_000L,
            window = emptyList(),
        )
        assertTrue(decision is FixFilter.Decision.Reject)
    }
}
