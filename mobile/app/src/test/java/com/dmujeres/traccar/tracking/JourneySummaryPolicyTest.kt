package com.dmujeres.traccar.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Caracteriza los datos del resumen de cierre (`finishedJourneyText`). */
class JourneySummaryPolicyTest {

    @Test
    fun noOpenJourneyMeansNoSummary() {
        assertNull(JourneySummaryPolicy.from(0L, 1_000L, 5.0, 3, 2))
        assertNull(JourneySummaryPolicy.from(-1L, 1_000L, 5.0, 3, 2))
    }

    @Test
    fun openJourneyEchoesExactValues() {
        val summary = JourneySummaryPolicy.from(
            startAtMs = 1_700_000_000_000L,
            elapsedMs = 3_600_000L,
            distanceM = 12_345.6,
            points = 120,
            confirmedPoints = 118,
        )
        requireNotNull(summary)
        assertEquals(3_600_000L, summary.durationMs)
        assertEquals(12_345.6, summary.distanceM, 0.0)
        assertEquals(120L, summary.points)
        assertEquals(118L, summary.confirmedPoints)
    }

    @Test
    fun zeroPointsIsStillASummary() {
        val summary = JourneySummaryPolicy.from(10L, 0L, 0.0, 0, 0)
        requireNotNull(summary)
        assertEquals(0L, summary.points)
    }
}
