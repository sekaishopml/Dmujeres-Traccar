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
    fun staleRecoveredBatchIsAcceptedLowQuality() {
        // R8.2 (H1): fixes entregados en lote tras congelado OEM (staleness
        // >120 s pero <=30 min) se ACEPTAN lowQuality — es ruta ya medida.
        val w = windowOf(fix(timeMs = 1_000_000L, elapsed = 1_000_000_000_000L))
        val now = 1_000_000_000_000L + FixFilter.STALE_AFTER_NANOS + 1_000_000_000L
        val decision = FixFilter.evaluate(
            lat = 19.4326, lon = -99.1332, accuracyM = 10f,
            wallTimeMs = 2_000_000L, elapsedNanos = 1_000_000_000_000L,
            nowElapsedNanos = now,
            window = w,
        )
        assertTrue(decision is FixFilter.Decision.Accept)
        assertTrue((decision as FixFilter.Decision.Accept).lowQuality)
    }

    @Test
    fun staleVeryOldStillRejects() {
        // Techo defensivo: >30 min de edad monotónica = basura FLP vieja.
        val w = windowOf(fix(timeMs = 1_000_000L, elapsed = 1_000_000_000_000L))
        val now = 1_000_000_000_000L + FixFilter.STALE_RECOVER_MAX_NANOS + 1_000_000_000L
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
        // R8.2 (H2): sin evidencia de movimiento (implícita ~0.3 m/s) sigue
        // rechazándose en quietud. (La pata anterior del test daba ~1.5 m/s:
        // justo el umbral, ahora se acepta lowQuality en movimiento.)
        val w = windowOf(fix(acc = 10f))
        val decision = FixFilter.evaluate(
            lat = 19.43265, lon = -99.13320, accuracyM = 120f,
            wallTimeMs = 1_010_000L, elapsedNanos = 1_010_000_000_000L,
            nowElapsedNanos = 1_010_000_000_000L,
            window = w,
        )
        assertEquals("degraded", (decision as FixFilter.Decision.Reject).reason)
    }

    @Test
    fun degradedFixInMotionIsAcceptedLowQuality() {
        // R8.2 (H2): en movimiento (implícita >=1.5 m/s) un fix degradado se
        // encola lowQuality — el server lo marca HIDE conservando la fila.
        val w = windowOf(fix(acc = 10f, timeMs = 1_000_000L, elapsed = 1_000_000_000_000L))
        val decision = FixFilter.evaluate(
            lat = 19.4327, lon = -99.1333, accuracyM = 120f,
            wallTimeMs = 1_010_000L, elapsedNanos = 1_010_000_000_000L,
            nowElapsedNanos = 1_010_000_000_000L,
            window = w,
        )
        assertTrue(decision is FixFilter.Decision.Accept)
        assertTrue((decision as FixFilter.Decision.Accept).lowQuality)
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
    fun longSilenceIsReacquisitionNotTeleport() {
        // REGRESSION JOSEPH: mismo salto imposible, pero 2 h después del último
        // fix (Doze/túnel). Juzgar velocidad contra una referencia de hace horas
        // no tiene sentido: se acepta como re-adquisición (siguen vigentes las
        // demás reglas). Sin esto, el primer fix tras despertar se rechaza y,
        // con fixes escasos, la ruta tarda horas en reaparecer.
        val w = windowOf(fix(lat = 19.4326, lon = -99.1332, acc = 10f, timeMs = 1_000_000L))
        val twoHoursLater = 1_000_000L + 2 * 3_600_000L
        val decision = FixFilter.evaluate(
            lat = 19.5326, lon = -99.1332, accuracyM = 10f,
            wallTimeMs = twoHoursLater,
            elapsedNanos = twoHoursLater * 1_000_000L,
            nowElapsedNanos = twoHoursLater * 1_000_000L,
            window = w,
        )
        assertTrue(decision is FixFilter.Decision.Accept)
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

    // ── Regla OR Traccar (frecuencia/distancia/ángulo) ──

    @Test
    fun orRuleFirstFixAccepts() {
        assertTrue(FixFilter.acceptByRule(null, -2.2, -79.88, 1_000L, 5_000L))
    }

    @Test
    fun orRuleTimeAccepts() {
        val ref = FixFilter.AcceptedRef(-2.2, -79.88, 0L, Double.NaN)
        assertTrue(FixFilter.acceptByRule(ref, -2.20001, -79.88, 6_000L, 5_000L))
    }

    @Test
    fun orRuleDistanceAccepts() {
        // ~30 m al norte en 2 s (< frecuencia 5 s): manda la distancia 24 m.
        val ref = FixFilter.AcceptedRef(-2.2, -79.88, 0L, Double.NaN)
        assertTrue(FixFilter.acceptByRule(ref, -2.19973, -79.88, 2_000L, 5_000L))
    }

    @Test
    fun orRuleQuietStraightRejects() {
        // 5 m en 2 s sin giro: redundante, se filtra.
        val ref = FixFilter.AcceptedRef(-2.2, -79.88, 0L, 0.0)
        assertFalse(FixFilter.acceptByRule(ref, -2.19996, -79.88, 2_000L, 5_000L))
    }

    @Test
    fun orRuleTurnAccepts() {
        // Venía al norte (rumbo 0), gira al este (~90°) con pata de 20 m en 4 s.
        val ref = FixFilter.AcceptedRef(-2.2, -79.88, 0L, 0.0)
        assertTrue(FixFilter.acceptByRule(ref, -2.2, -79.87982, 4_000L, 5_000L))
    }

    @Test
    fun orRuleShortLegTurnRejects() {
        // Giro de 90° pero pata de 3 m en 3 s (< frecuencia 5 s): jitter, no curva.
        val ref = FixFilter.AcceptedRef(-2.2, -79.88, 0L, 0.0)
        assertFalse(FixFilter.acceptByRule(ref, -2.2, -79.879973, 3_000L, 5_000L))
    }

    @Test
    fun bearingNorthEastSouthWest() {
        assertEquals(0.0, FixFilter.bearingDeg(0.0, 0.0, 1.0, 0.0), 0.5)
        assertEquals(90.0, FixFilter.bearingDeg(0.0, 0.0, 0.0, 1.0), 0.5)
        assertEquals(180.0, FixFilter.bearingDeg(1.0, 0.0, 0.0, 0.0), 0.5)
        assertEquals(270.0, FixFilter.bearingDeg(0.0, 1.0, 0.0, 0.0), 0.5)
    }

    @Test
    fun angleDiffWraps() {
        assertEquals(20.0, FixFilter.angleDiffDeg(350.0, 10.0), 0.001)
        assertEquals(180.0, FixFilter.angleDiffDeg(0.0, 180.0), 0.001)
        assertEquals(0.0, FixFilter.angleDiffDeg(45.0, 45.0), 0.001)
    }

    @Test
    fun heartbeatDueAfterMinuteStill() {
        assertFalse(FixFilter.heartbeatDue(1_000L, 30_000L))
        assertFalse(FixFilter.heartbeatDue(1_000L, 61_000L))
        assertTrue(FixFilter.heartbeatDue(1_000L, 61_001L))
    }
}
