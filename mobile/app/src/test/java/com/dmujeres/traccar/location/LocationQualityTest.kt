package com.dmujeres.traccar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQualityTest {

    @Test
    fun quality1_goodFixScoresHigh() {
        val score = LocationQuality.calculateConfidenceScore(
            horizontalAccuracyM = 5.0,
            speedAccuracyMps = 1f,
            gnssUsableRatio = 0.6,
            fixAgeSec = 10.0,
            impliedSpeedMps = null,
            dopplerValid = true,
        )
        // 40 + 20 + 15 + 15 + 10 + 5 = 105 → clamp 100.
        assertTrue(score >= 70)
        assertEquals(100, score)
    }

    @Test
    fun quality2_poorAccuracyScoresLow() {
        val score = LocationQuality.calculateConfidenceScore(
            horizontalAccuracyM = 200.0,
            speedAccuracyMps = null,
            gnssUsableRatio = null,
            fixAgeSec = null,
            impliedSpeedMps = null,
            dopplerValid = false,
        )
        // 40 - 20 = 20.
        assertTrue(score < 40)
        assertEquals(20, score)
    }

    @Test
    fun quality3_staleFixScoresLowerThanFresh() {
        val fresh = LocationQuality.calculateConfidenceScore(
            horizontalAccuracyM = 20.0,
            speedAccuracyMps = 3f,
            gnssUsableRatio = 0.5,
            fixAgeSec = 5.0,
            impliedSpeedMps = null,
            dopplerValid = false,
        )
        val stale = LocationQuality.calculateConfidenceScore(
            horizontalAccuracyM = 20.0,
            speedAccuracyMps = 3f,
            gnssUsableRatio = 0.5,
            fixAgeSec = 300.0,
            impliedSpeedMps = null,
            dopplerValid = false,
        )
        assertEquals(40 + 12 + 8 + 15 + 10, fresh)
        assertEquals(40 + 12 + 8 + 15 - 15, stale)
        assertTrue(stale < fresh)
    }

    @Test
    fun quality4_unknownFieldsAreNullNotZero() {
        // Pura: el mapeo de campos unknown no inventa 0. El from() Android
        // (con Location real) queda cubierto por compilación.
        val score = LocationQuality.calculateConfidenceScore(
            horizontalAccuracyM = null,
            speedAccuracyMps = null,
            gnssUsableRatio = null,
            fixAgeSec = null,
            impliedSpeedMps = null,
            dopplerValid = false,
        )
        // Solo la base: desconocido no suma ni castiga (salvo suelo 0).
        assertEquals(40, score)
    }

    @Test
    fun quality5_gnssRatioNeedsRealData() {
        assertNull(LocationQuality.usableRatio(null, 12))
        assertNull(LocationQuality.usableRatio(6, null))
        assertNull(LocationQuality.usableRatio(6, 0))
        assertEquals(0.5, LocationQuality.usableRatio(6, 12)!!, 1e-9)
        // used > total se recorta al rango válido.
        assertEquals(1.0, LocationQuality.usableRatio(20, 12)!!, 1e-9)
    }

    @Test
    fun quality6_scoreIsClampedToUnitRange() {
        val floor = LocationQuality.calculateConfidenceScore(
            horizontalAccuracyM = 499.0,
            speedAccuracyMps = 50f,
            gnssUsableRatio = null,
            fixAgeSec = 999.0,
            impliedSpeedMps = null,
            dopplerValid = false,
        )
        // Peor caso real con los pesos (base 40, −20 accuracy, −15 edad): 5.
        // El clamp 0 es defensivo: los pesos no pueden bajar de 5.
        assertEquals(5, floor)
        val ceiling = LocationQuality.calculateConfidenceScore(
            horizontalAccuracyM = 1.0,
            speedAccuracyMps = 0.5f,
            gnssUsableRatio = 1.0,
            fixAgeSec = 1.0,
            impliedSpeedMps = 3f,
            dopplerValid = true,
        )
        assertEquals(100, ceiling)
    }

    @Test
    fun quality7_dataClassDefaultsForUnknown() {
        val q = LocationQuality(
            horizontalAccuracyM = null,
            speedAccuracyMps = null,
            bearingAccuracyDeg = null,
            altitudeAccuracyM = null,
            fixAgeSec = null,
            provider = "fused",
            elapsedRealtimeValid = false,
            mock = false,
            gnssUsed = null,
            gnssTotal = null,
            lowQuality = false,
            reason = null,
            confidenceScore = 40,
        )
        assertNull(q.horizontalAccuracyM)
        assertNull(q.speedAccuracyMps)
        assertNull(q.gnssUsed)
        assertFalse(q.mock)
        assertFalse(q.lowQuality)
        assertEquals("fused", q.provider)
    }
}
