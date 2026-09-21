package com.dmujeres.traccar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedEstimatorTest {

    @Test
    fun dopplerTrustedWhenPresent() {
        assertEquals(11f, SpeedEstimator.effectiveMps(11f, 2f)!!, 0.001f)
    }

    @Test
    fun fallsBackToImpliedWhenDopplerZero() {
        // Caso ZTE: Doppler en 0 en marcha → manda la geometría.
        assertEquals(10.5f, SpeedEstimator.effectiveMps(0f, 10.5f)!!, 0.001f)
        assertEquals(10.5f, SpeedEstimator.effectiveMps(null, 10.5f)!!, 0.001f)
    }

    @Test
    fun nullWhenNothing() {
        assertNull(SpeedEstimator.effectiveMps(null, null))
        assertNull(SpeedEstimator.effectiveMps(0f, null))
    }

    @Test
    fun impliedNeedsPositiveDt() {
        assertNull(SpeedEstimator.impliedMps(-2.2, -79.88, 1000L, -2.2, -79.88, 1000L))
        assertNull(SpeedEstimator.impliedMps(-2.2, -79.88, 2000L, -2.2, -79.88, 1000L))
    }

    @Test
    fun impliedCityDriving() {
        // 110 m en 10 s ≈ 11 m/s (≈40 km/h).
        val implied = SpeedEstimator.impliedMps(-2.20, -79.88, 0L, -2.19901, -79.88, 10_000L)
        assertEquals(11.0f, implied!!, 0.5f)
    }

    @Test
    fun impliedCappedByMaxDoesNotInventSpeed() {
        // REGRESSION JOSEPH: salto de reloj/teleport con implícita absurda no
        // debe inventar velocidad: con tope 45 m/s cae a desconocida.
        assertNull(SpeedEstimator.effectiveMps(0f, 150f, 45f))
        assertEquals("unknown", SpeedEstimator.speedSource(0f, 150f, 45f))
        // Dentro del tope sí respalda al Doppler atascado.
        assertEquals(25f, SpeedEstimator.effectiveMps(0f, 25f, 45f)!!, 0.001f)
        assertEquals("implied", SpeedEstimator.speedSource(0f, 25f, 45f))
        assertEquals("doppler", SpeedEstimator.speedSource(11f, 2f, 45f))
        assertEquals("unknown", SpeedEstimator.speedSource(null, null, 45f))
        assertEquals("unknown", SpeedEstimator.speedSource(0f, null, 45f))
    }

    // ── choose() con accuracy del Doppler (quality-aware) ──

    @Test
    fun speed1_dopplerTrustedWithGoodAccuracy() {
        val choice = SpeedEstimator.choose(11f, 1.5f, 9f, 45f)
        assertEquals(11f, choice!!.mps, 0.001f)
        assertEquals("doppler", choice.source)
    }

    @Test
    fun speed2_stuckDopplerWithBadAccuracyFallsBackToImplied() {
        // Doppler=0 atascado + accuracy mala: manda la geometría.
        val choice = SpeedEstimator.choose(0f, 10f, 12f, 45f)
        assertEquals(12f, choice!!.mps, 0.001f)
        assertEquals("implied", choice.source)
    }

    @Test
    fun speed3_bothNullGiveUnknown() {
        assertNull(SpeedEstimator.choose(null, null, null, 45f))
        assertNull(SpeedEstimator.choose(0f, 1f, null, 45f))
        assertNull(SpeedEstimator.choose(0f, 1f, 150f, 45f))
    }

    @Test
    fun speed4_nullAccuracyKeepsDopplerCompat() {
        // Sin accuracy reportada: doppler > 0.1 sigue viajando como doppler.
        val choice = SpeedEstimator.choose(11f, null, 9f, 45f)
        assertEquals(11f, choice!!.mps, 0.001f)
        assertEquals("doppler", choice.source)
        // accuracy buena (<=4) también es doppler; mala con doppler>0.1 y sin
        // implied cae al doppler solo si accuracy es null; con accuracy mala y
        // implied inválida queda unknown.
        assertNull(SpeedEstimator.choose(11f, 10f, 150f, 45f))
    }

    @Test
    fun speed5_accuracyThresholdBoundary() {
        assertEquals("doppler", SpeedEstimator.choose(3f, 4f, 0f, 45f)?.source)
        assertEquals("implied", SpeedEstimator.choose(3f, 4.1f, 0f, 45f)?.source)
    }
}
