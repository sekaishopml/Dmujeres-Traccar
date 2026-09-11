package com.dmujeres.traccar.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lógica pura del widget (stateFor + formato "Xh Ym"): sin Android, JVM directo.
 */
class JourneyWidgetStateTest {

    @Test
    fun underOneHourNeverShowsZeroHours() {
        assertEquals("0m", JourneyWidgetState.formatDuration(0L))
        assertEquals("1m", JourneyWidgetState.formatDuration(60_000L))
        assertEquals("59m", JourneyWidgetState.formatDuration(59 * 60_000L))
        assertFalse(JourneyWidgetState.formatDuration(45 * 60_000L).startsWith("0h"))
        assertEquals("45m", JourneyWidgetState.formatDuration(45 * 60_000L))
    }

    @Test
    fun oneHourAndMoreShowHoursAndMinutes() {
        assertEquals("1h 0m", JourneyWidgetState.formatDuration(3_600_000L))
        assertEquals("2h 5m", JourneyWidgetState.formatDuration(2 * 3_600_000L + 5 * 60_000L))
        assertEquals("8h 30m", JourneyWidgetState.formatDuration(8 * 3_600_000L + 30 * 60_000L))
    }

    @Test
    fun negativeDurationClampsToZero() {
        assertEquals("0m", JourneyWidgetState.formatDuration(-12_345L))
    }

    @Test
    fun durationIsCappedAt24Hours() {
        val capped = JourneyWidgetState.formatDuration(100L * 3_600_000L)
        assertEquals("24h 0m", capped)
    }

    @Test
    fun wordyTitleFormatForLegible2x2() {
        // Título grande del widget 2x2: "15 h 42 min" / "42 min", nunca "0 h".
        assertEquals("0 min", JourneyWidgetState.formatDurationWords(0L))
        assertEquals("42 min", JourneyWidgetState.formatDurationWords(42 * 60_000L))
        assertEquals("59 min", JourneyWidgetState.formatDurationWords(59 * 60_000L))
        assertEquals("1 h 30 min", JourneyWidgetState.formatDurationWords(90 * 60_000L))
        assertEquals("15 h 42 min", JourneyWidgetState.formatDurationWords(15 * 3_600_000L + 42 * 60_000L))
    }

    @Test
    fun wordyTitleIsClampedAndCapped() {
        assertEquals("0 min", JourneyWidgetState.formatDurationWords(-12_345L))
        assertEquals("24 h 0 min", JourneyWidgetState.formatDurationWords(100L * 3_600_000L))
    }

    @Test
    fun activeJourneyMapsToEndActionInRojo() {
        val state = JourneyWidgetState.stateFor(
            trackingEnabled = true,
            journeyActive = true,
            elapsedMs = 90 * 60_000L,
            online = true,
        )
        assertTrue(state.active)
        assertFalse(state.primaryActionStart)
        assertEquals(JourneyColorToken.Rojo, state.color)
        assertEquals(90 * 60_000L, state.durationMs)
        assertEquals("1h 30m", JourneyWidgetState.formatDuration(state.durationMs))
    }

    @Test
    fun idleMapsToStartActionInVerde() {
        val state = JourneyWidgetState.stateFor(
            trackingEnabled = false,
            journeyActive = false,
            elapsedMs = 0L,
            online = true,
        )
        assertFalse(state.active)
        assertTrue(state.primaryActionStart)
        assertEquals(JourneyColorToken.Verde, state.color)
        assertFalse(state.waitingHint)
        assertEquals(0L, state.durationMs)
    }

    @Test
    fun trackingWithoutJourneyKeepsStartAndShowsWaitingHint() {
        val state = JourneyWidgetState.stateFor(
            trackingEnabled = true,
            journeyActive = false,
            elapsedMs = 0L,
            online = false,
        )
        assertFalse(state.active)
        assertTrue(state.primaryActionStart)
        assertEquals(JourneyColorToken.Verde, state.color)
        assertTrue(state.waitingHint)
    }

    @Test
    fun activeNeverEmitsStartEvenIfInconsistentInputs() {
        // journeyActive manda sobre trackingEnabled=false (defensivo).
        val state = JourneyWidgetState.stateFor(
            trackingEnabled = false,
            journeyActive = true,
            elapsedMs = -1L,
            online = true,
        )
        assertTrue(state.active)
        assertFalse(state.primaryActionStart)
        assertEquals(0L, state.durationMs)
    }
}
