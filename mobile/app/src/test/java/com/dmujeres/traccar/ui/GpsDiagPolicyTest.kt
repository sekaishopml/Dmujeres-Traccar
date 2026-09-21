package com.dmujeres.traccar.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DIAGNÓSTICO GPS ÚTIL: indoor vs primera señal vs buscando (polling inferido).
 * Puro (JVM, sin Android): replica la decisión de DiagnosticsActivity sin
 * tocar TrackingService.
 */
class GpsDiagPolicyTest {

    @Test
    fun indoorWhenSkyBlockedWithoutRecentFix() {
        val info = GpsDiagPolicy.describe(
            hasGnssData = true,
            skyBlocked = true,
            hasRecentFix = false,
            hasAnyFix = false,
            trackingActive = true,
            withoutFixMs = 200_000L,
        )
        assertTrue(info.indoor)
        assertFalse(info.firstWait)
        assertTrue(info.searching)
    }

    @Test
    fun indoorClearsWithRecentFix() {
        // Con fix reciente no es "bajo techo" aunque el último evento GNSS
        // haya sido 0 (el fix manda).
        val info = GpsDiagPolicy.describe(
            hasGnssData = true,
            skyBlocked = false,
            hasRecentFix = true,
            hasAnyFix = true,
            trackingActive = true,
            withoutFixMs = 10_000L,
        )
        assertFalse(info.indoor)
        assertFalse(info.firstWait)
        assertFalse(info.searching)
    }

    @Test
    fun firstWaitWithoutGnssDataAndWithoutRecentFix() {
        val info = GpsDiagPolicy.describe(
            hasGnssData = false,
            skyBlocked = false,
            hasRecentFix = false,
            hasAnyFix = false,
            trackingActive = true,
            withoutFixMs = 200_000L,
        )
        assertFalse(info.indoor)
        assertTrue(info.firstWait)
        assertTrue(info.searching)
    }

    @Test
    fun noFirstWaitWhenGnssDataPresent() {
        val info = GpsDiagPolicy.describe(
            hasGnssData = true,
            skyBlocked = false,
            hasRecentFix = false,
            hasAnyFix = true,
            trackingActive = false,
            withoutFixMs = 200_000L,
        )
        assertFalse(info.firstWait)
        // Sin jornada no se infiere búsqueda aunque lleve rato sin fix.
        assertFalse(info.searching)
    }

    @Test
    fun searchingOnlyAfter90sWithJourneyActive() {
        // 89 s sin fix → aún no; 91 s → buscando.
        assertFalse(
            GpsDiagPolicy.isSearching(trackingActive = true, withoutFixMs = 89_000L),
        )
        assertFalse(
            GpsDiagPolicy.isSearching(trackingActive = true, withoutFixMs = 90_000L),
        )
        assertTrue(
            GpsDiagPolicy.isSearching(trackingActive = true, withoutFixMs = 91_000L),
        )
        // Sin jornada nunca, aunque lleve 1 h sin fix.
        assertFalse(
            GpsDiagPolicy.isSearching(trackingActive = false, withoutFixMs = 3_600_000L),
        )
    }

    @Test
    fun withoutFixMsUsesLastFixOrJourneyStart() {
        // Con fix: desde el fix.
        assertEquals(
            50_000L,
            GpsDiagPolicy.withoutFixMs(lastFixAt = 1_000L, journeyStartAt = 500L, now = 51_000L),
        )
        // Sin fix pero con jornada: desde el inicio (esperando primera señal).
        assertEquals(
            100_000L,
            GpsDiagPolicy.withoutFixMs(lastFixAt = 0L, journeyStartAt = 1_000L, now = 101_000L),
        )
        // Sin nada: 0 (no inferir).
        assertEquals(
            0L,
            GpsDiagPolicy.withoutFixMs(lastFixAt = 0L, journeyStartAt = 0L, now = 101_000L),
        )
    }

    @Test
    fun hasRecentFixWindowIs5min() {
        assertTrue(GpsDiagPolicy.hasRecentFix(lastFixAt = 1_000L, now = 1_000L + 4 * 60_000L))
        assertFalse(GpsDiagPolicy.hasRecentFix(lastFixAt = 1_000L, now = 1_000L + 5 * 60_000L))
        assertFalse(GpsDiagPolicy.hasRecentFix(lastFixAt = 0L, now = 100_000L))
    }
}
