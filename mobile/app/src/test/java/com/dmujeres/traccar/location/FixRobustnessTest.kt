package com.dmujeres.traccar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Robustez anti "cero capturas en silencio":
 * 1. Anti-livelock R1 (ventana vacía + primer fix <150).
 * 2. lastFixAt solo tras insert OK.
 * 5. isValidLocation con razón para Log.w.
 */
class FixRobustnessTest {

    // ---- 1. ANTI-LIVELOCK R1 ----

    @Test
    fun emptyWindowNeeds10RejectsToForce() {
        assertFalse(FixFilter.shouldForceEmptyWindowAccept(0, 0L))
        assertFalse(FixFilter.shouldForceEmptyWindowAccept(9, 0L))
        assertTrue(FixFilter.shouldForceEmptyWindowAccept(10, 0L))
        assertTrue(FixFilter.shouldForceEmptyWindowAccept(11, 0L))
    }

    @Test
    fun emptyWindowForcesAfter5MinEvenWithFewRejects() {
        assertFalse(FixFilter.shouldForceEmptyWindowAccept(1, 0L))
        assertFalse(FixFilter.shouldForceEmptyWindowAccept(1, 5 * 60_000L - 1))
        assertTrue(FixFilter.shouldForceEmptyWindowAccept(1, 5 * 60_000L))
        assertTrue(FixFilter.shouldForceEmptyWindowAccept(5, 10 * 60_000L))
    }

    @Test
    fun recoveringJourneyDoesNotClearWindow() {
        // Si recoveringJourney, NO hacer recentFixes.clear().
        assertFalse(FixFilter.shouldClearWindowOnStart(true))
        assertTrue(FixFilter.shouldClearWindowOnStart(false))
    }

    @Test
    fun firstFixBadLoopWouldForceAcceptAfterThreshold() {
        // Escenario forense: todo da >=150 con ventana vacía → first_fix_bad eterno.
        val decision = FixFilter.evaluate(
            lat = 19.4326, lon = -99.1332, accuracyM = 200f,
            wallTimeMs = 1_000_000L, elapsedNanos = 1_000_000_000_000L,
            nowElapsedNanos = 1_000_000_000_000L,
            window = emptyList(),
        )
        assertTrue(decision is FixFilter.Decision.Reject)
        assertEquals("first_fix_bad", (decision as FixFilter.Decision.Reject).reason)
        // Tras 10 rechazos consecutivos con ventana vacía se funda + Accept(lowQuality=true).
        assertTrue(FixFilter.shouldForceEmptyWindowAccept(10, 0L))
    }

    // ---- 2. lastFixAt DESPUÉS de insert OK ----

    @Test
    fun lastFixAdvancesOnlyOnInsertOk() {
        // insertWithinLimit: >=0 encoló, -1 sin espacio.
        assertFalse(FixFilter.shouldAdvanceLastFix(-1))
        assertTrue(FixFilter.shouldAdvanceLastFix(0))
        assertTrue(FixFilter.shouldAdvanceLastFix(1))
        assertTrue(FixFilter.shouldAdvanceLastFix(5))
    }

    @Test
    fun insertMinusOneMeansLastFixMustNotAdvance() {
        // Test explícito del enunciado: insert -1 → lastFixAt no avanza.
        val insertResult = -1
        assertFalse(
            "insert -1 no debe avanzar lastFixAt (enmascara fallo DB/buffer)",
            FixFilter.shouldAdvanceLastFix(insertResult),
        )
    }

    // ---- 5. LOG isValidLocation ----

    @Test
    fun validLocationHasNoReason() {
        assertNull(FixFilter.invalidLocationReason(19.4326, -99.1332, 10f))
        assertTrue(FixFilter.isValidLocation(19.4326, -99.1332, 10f))
    }

    @Test
    fun invalidLatHasReasonForLog() {
        assertNotNull(FixFilter.invalidLocationReason(95.0, -99.1332, 10f))
        assertNotNull(FixFilter.invalidLocationReason(Double.NaN, -99.1332, 10f))
        assertFalse(FixFilter.isValidLocation(95.0, -99.1332, 10f))
    }

    @Test
    fun invalidLonHasReasonForLog() {
        assertNotNull(FixFilter.invalidLocationReason(19.4326, -200.0, 10f))
        assertFalse(FixFilter.isValidLocation(19.4326, -200.0, 10f))
    }

    @Test
    fun invalidAccuracyHasReasonForLog() {
        assertNotNull(FixFilter.invalidLocationReason(19.4326, -99.1332, -1f))
        assertNotNull(FixFilter.invalidLocationReason(19.4326, -99.1332, Float.NaN))
        assertFalse(FixFilter.isValidLocation(19.4326, -99.1332, -1f))
    }

    @Test
    fun accuracyCeilingHasReasonForLog() {
        assertNotNull(FixFilter.invalidLocationReason(19.4326, -99.1332, 500f))
        assertNotNull(FixFilter.invalidLocationReason(19.4326, -99.1332, 800f))
        assertFalse(FixFilter.isValidLocation(19.4326, -99.1332, 500f))
        // 499 sí es válido (se marca lowQuality, no se tira).
        assertNull(FixFilter.invalidLocationReason(19.4326, -99.1332, 499f))
        assertTrue(FixFilter.isValidLocation(19.4326, -99.1332, 499f))
    }
}
