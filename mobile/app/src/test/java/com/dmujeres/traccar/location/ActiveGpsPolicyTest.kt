package com.dmujeres.traccar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adquisición GPS activa: decisión de poll por tiempos (monotónicos),
 * min-distance adaptativa por velocidad y parseo GNSS simulado.
 */
class ActiveGpsPolicyTest {

    private fun s(seconds: Long): Long = seconds * 1_000_000_000L

    private fun shouldPoll(
        nowS: Long,
        fixS: Long = 0L,
        startS: Long = 1L,
        pollS: Long = 0L,
        failures: Int = 0,
        battery: Int = 80,
        active: Boolean = true,
    ): Boolean = ActivePollPolicy.shouldPoll(
        trackingActive = active,
        nowElapsedNanos = s(nowS),
        lastFixElapsedNanos = if (fixS <= 0L) 0L else s(fixS),
        startElapsedNanos = s(startS),
        lastPollAttemptElapsedNanos = if (pollS <= 0L) 0L else s(pollS),
        consecutiveFailures = failures,
        batteryPct = battery,
    )

    // ---- 1. POLLING ACTIVO POR TIEMPOS ----

    @Test
    fun noPollWithin90sOfFix() {
        // Fix hace 89 s → aún no toca.
        assertFalse(shouldPoll(nowS = 100L, fixS = 11L))
        // Fix hace justo 90 s → todavía no (exige > 90 s).
        assertFalse(shouldPoll(nowS = 100L, fixS = 10L))
        // Fix hace 91 s, nunca se polleó → dispara.
        assertTrue(shouldPoll(nowS = 100L, fixS = 9L))
    }

    @Test
    fun noFixSinceStartUsesStartAsReference() {
        // Sin fix en el arranque: 89 s desde start → no; 91 s → sí.
        assertFalse(shouldPoll(nowS = 90L, startS = 1L))
        assertTrue(shouldPoll(nowS = 92L, startS = 1L))
    }

    @Test
    fun fixResetsBackoff() {
        // Aunque hubo 5 fallos y un poll hace 200 s, un fix de hace 10 s manda.
        assertFalse(shouldPoll(nowS = 1000L, fixS = 990L, pollS = 800L, failures = 5))
    }

    @Test
    fun backoff90s3min5min() {
        // 1 fallo: reintento a los 90 s.
        assertFalse(shouldPoll(nowS = 1000L, fixS = 10L, pollS = 911L, failures = 1))
        assertTrue(shouldPoll(nowS = 1000L, fixS = 10L, pollS = 910L, failures = 1))
        // 2 fallos: reintento a los 3 min.
        assertFalse(shouldPoll(nowS = 1000L, fixS = 10L, pollS = 821L, failures = 2))
        assertTrue(shouldPoll(nowS = 1000L, fixS = 10L, pollS = 820L, failures = 2))
        // 3+ fallos: reintento a los 5 min (techo, no crece más).
        assertFalse(shouldPoll(nowS = 1000L, fixS = 10L, pollS = 701L, failures = 3))
        assertTrue(shouldPoll(nowS = 1000L, fixS = 10L, pollS = 700L, failures = 3))
        assertFalse(shouldPoll(nowS = 1000L, fixS = 10L, pollS = 701L, failures = 10))
        assertTrue(shouldPoll(nowS = 1000L, fixS = 10L, pollS = 700L, failures = 10))
    }

    @Test
    fun retryDelayConstants() {
        assertEquals(90_000_000_000L, ActivePollPolicy.retryDelayNanos(0))
        assertEquals(90_000_000_000L, ActivePollPolicy.retryDelayNanos(1))
        assertEquals(180_000_000_000L, ActivePollPolicy.retryDelayNanos(2))
        assertEquals(300_000_000_000L, ActivePollPolicy.retryDelayNanos(3))
        assertEquals(300_000_000_000L, ActivePollPolicy.retryDelayNanos(99))
    }

    @Test
    fun noPollWhenJourneyInactive() {
        // Jornada no activa: nunca, aunque lleve 1 h sin fix.
        assertFalse(shouldPoll(nowS = 3700L, fixS = 10L, active = false))
        assertFalse(shouldPoll(nowS = 3700L, startS = 1L, active = false))
    }

    @Test
    fun lowBatteryPollsEvery10min() {
        // Batería 14 %: 5 min desde el último intento → aún no.
        assertFalse(shouldPoll(nowS = 1000L, fixS = 10L, pollS = 700L, failures = 0, battery = 14))
        // 10 min desde el último intento → sí.
        assertTrue(shouldPoll(nowS = 1000L, fixS = 10L, pollS = 400L, failures = 0, battery = 14))
        // El backoff normal no aplica con batería baja (90 s no bastan).
        assertFalse(shouldPoll(nowS = 1000L, fixS = 10L, pollS = 910L, failures = 1, battery = 10))
        // Umbral: 15 % ya es política normal; 14 % es baja; 0 = desconocida (normal).
        assertTrue(ActivePollPolicy.isLowBattery(14))
        assertFalse(ActivePollPolicy.isLowBattery(15))
        assertFalse(ActivePollPolicy.isLowBattery(0))
        assertFalse(ActivePollPolicy.isLowBattery(-1))
        assertTrue(shouldPoll(nowS = 1000L, fixS = 10L, pollS = 910L, failures = 0, battery = 15))
        assertTrue(shouldPoll(nowS = 1000L, fixS = 10L, pollS = 910L, failures = 0, battery = 0))
    }

    @Test
    fun monotonicClockEdgeCases() {
        // Reloj inválido o referencias inválidas → nunca dispara.
        assertFalse(
            ActivePollPolicy.shouldPoll(
                trackingActive = true, nowElapsedNanos = 0L,
                lastFixElapsedNanos = 0L, startElapsedNanos = s(1L),
                lastPollAttemptElapsedNanos = 0L, consecutiveFailures = 0, batteryPct = 80,
            ),
        )
        assertFalse(
            ActivePollPolicy.shouldPoll(
                trackingActive = true, nowElapsedNanos = s(1000L),
                lastFixElapsedNanos = 0L, startElapsedNanos = 0L,
                lastPollAttemptElapsedNanos = 0L, consecutiveFailures = 0, batteryPct = 80,
            ),
        )
    }

    // ---- 3. MIN-DISTANCE ADAPTATIVA ----

    @Test
    fun stationaryUsesZeroMeters() {
        val mode = AdaptiveDistancePolicy.nextMode(
            AdaptiveDistancePolicy.Mode.STATIONARY, 0.5f, hasRecentFix = true,
        )
        assertEquals(AdaptiveDistancePolicy.Mode.STATIONARY, mode)
        assertEquals(0f, AdaptiveDistancePolicy.distanceFor(mode))
    }

    @Test
    fun movingUses24Meters() {
        val mode = AdaptiveDistancePolicy.nextMode(
            AdaptiveDistancePolicy.Mode.STATIONARY, 6f, hasRecentFix = true,
        )
        assertEquals(AdaptiveDistancePolicy.Mode.MOVING, mode)
        assertEquals(24f, AdaptiveDistancePolicy.distanceFor(mode))
        assertEquals(FixFilter.MIN_UPDATE_DISTANCE_M, AdaptiveDistancePolicy.distanceFor(mode))
    }

    @Test
    fun hysteresisKeepsModeInMiddleZone() {
        // Zona 1-5 m/s: se conserva el modo (no re-registrar a cada fix).
        assertEquals(
            AdaptiveDistancePolicy.Mode.MOVING,
            AdaptiveDistancePolicy.nextMode(AdaptiveDistancePolicy.Mode.MOVING, 3f, true),
        )
        assertEquals(
            AdaptiveDistancePolicy.Mode.STATIONARY,
            AdaptiveDistancePolicy.nextMode(AdaptiveDistancePolicy.Mode.STATIONARY, 3f, true),
        )
        // Bordes: > 5 para arrancar, < 1 para parar.
        assertEquals(
            AdaptiveDistancePolicy.Mode.STATIONARY,
            AdaptiveDistancePolicy.nextMode(AdaptiveDistancePolicy.Mode.STATIONARY, 5f, true),
        )
        assertEquals(
            AdaptiveDistancePolicy.Mode.MOVING,
            AdaptiveDistancePolicy.nextMode(AdaptiveDistancePolicy.Mode.MOVING, 1f, true),
        )
        assertEquals(
            AdaptiveDistancePolicy.Mode.STATIONARY,
            AdaptiveDistancePolicy.nextMode(AdaptiveDistancePolicy.Mode.MOVING, 0.99f, true),
        )
    }

    @Test
    fun staleOrUnknownSpeedMeansStationary() {
        // Sin fix reciente (aunque el último iba rápido) → 0 m.
        assertEquals(
            AdaptiveDistancePolicy.Mode.STATIONARY,
            AdaptiveDistancePolicy.nextMode(AdaptiveDistancePolicy.Mode.MOVING, 10f, false),
        )
        // Sin dato de speed (fix sin hasSpeed) → quieto.
        assertEquals(
            AdaptiveDistancePolicy.Mode.STATIONARY,
            AdaptiveDistancePolicy.nextMode(AdaptiveDistancePolicy.Mode.MOVING, null, true),
        )
        assertEquals(
            AdaptiveDistancePolicy.Mode.STATIONARY,
            AdaptiveDistancePolicy.nextMode(AdaptiveDistancePolicy.Mode.STATIONARY, Float.NaN, true),
        )
    }

    // ---- 2. GNSS REAL (parseo simulado) ----

    @Test
    fun gnssSummarizeCountsUsedInFix() {
        val counts = GnssSummary.summarize(listOf(true, true, false, false, false))
        assertEquals(5, counts.total)
        assertEquals(2, counts.used)
        val empty = GnssSummary.summarize(emptyList())
        assertEquals(0, empty.total)
        assertEquals(0, empty.used)
        val allUsed = GnssSummary.summarize(listOf(true, true, true))
        assertEquals(3, allUsed.total)
        assertEquals(3, allUsed.used)
    }

    @Test
    fun skyBlockedDiffersFromGpsOff() {
        // 0 en vista + sin fix = bajo techo/sin cielo.
        assertTrue(GnssSummary.isSkyBlocked(totalInView = 0, hasRecentFix = false))
        // Con fix (aunque sea de red) o con satélites a la vista no es ese caso.
        assertFalse(GnssSummary.isSkyBlocked(totalInView = 0, hasRecentFix = true))
        assertFalse(GnssSummary.isSkyBlocked(totalInView = 4, hasRecentFix = false))
        assertFalse(GnssSummary.isSkyBlocked(totalInView = 4, hasRecentFix = true))
    }
}
