package com.dmujeres.traccar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** R8: un fix con reloj adelantado se rechaza (rompe orden/velocidad). */
class FixClockFutureTest {

    @Test
    fun fixFuturoSeRechazaConRazonExplicita() {
        val nowWall = 1_000_000_000_000L
        val decision = FixFilter.evaluate(
            lat = -2.17,
            lon = -79.9,
            accuracyM = 10f,
            wallTimeMs = nowWall + 10 * 60_000L,
            elapsedNanos = 5_000_000_000L,
            nowElapsedNanos = 6_000_000_000L,
            window = emptyList(),
            nowWallMs = nowWall,
        )
        assertTrue(decision is FixFilter.Decision.Reject)
        assertEquals("clock_future", (decision as FixFilter.Decision.Reject).reason)
    }

    @Test
    fun fixDentroDeToleranciaNoSeRechazaPorReloj() {
        val nowWall = 1_000_000_000_000L
        val decision = FixFilter.evaluate(
            lat = -2.17,
            lon = -79.9,
            accuracyM = 10f,
            wallTimeMs = nowWall + 30_000L,
            elapsedNanos = 5_000_000_000L,
            nowElapsedNanos = 6_000_000_000L,
            window = emptyList(),
            nowWallMs = nowWall,
        )
        // Primer fix bueno → Accept (el reloj dentro de tolerancia no interfiere).
        assertTrue(decision is FixFilter.Decision.Accept)
    }
}
