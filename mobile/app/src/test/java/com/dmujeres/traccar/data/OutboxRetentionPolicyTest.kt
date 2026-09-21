package com.dmujeres.traccar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retención dura del outbox: 100 000 posiciones o 7 días, lo que ocurra primero.
 * Pura JVM. Justificación en [OutboxRetentionPolicy].
 */
class OutboxRetentionPolicyTest {

    @Test
    fun topesDeRetencion() {
        assertEquals(100_000, OutboxRetentionPolicy.MAX_POSITIONS)
        assertEquals(7L * 24L * 60L * 60L * 1000L, OutboxRetentionPolicy.MAX_AGE_MS)
    }

    @Test
    fun expiracionPorBordes() {
        val now = 10_000_000_000L
        val cutoff = OutboxRetentionPolicy.expiredCutoffMs(now)
        assertEquals(now - OutboxRetentionPolicy.MAX_AGE_MS, cutoff)
        assertTrue(OutboxRetentionPolicy.isExpired(cutoff - 1, now))
        assertFalse(OutboxRetentionPolicy.isExpired(cutoff + 1, now))
        // enqueuedAt = 0 (legacy) nunca se considera expirado por edad.
        assertFalse(OutboxRetentionPolicy.isExpired(0L, now))
    }

    @Test
    fun overflowIgualQueDiscardCount() {
        assertEquals(0, OutboxRetentionPolicy.overflowEvictCount(99_999))
        assertEquals(1, OutboxRetentionPolicy.overflowEvictCount(100_000))
        assertEquals(6, OutboxRetentionPolicy.overflowEvictCount(100_005))
    }

    @Test
    fun effectiveMaxSaneaElDefaultAntiguo() {
        // Instalaciones con el default viejo (5 000 ≈ 14 h) quedan cubiertas
        // para 24-72 h offline sin migración de prefs.
        assertEquals(100_000, OutboxRetentionPolicy.effectiveMax(5_000))
        assertEquals(100_000, OutboxRetentionPolicy.effectiveMax(10))
        assertEquals(100_000, OutboxRetentionPolicy.effectiveMax(100_000))
        assertEquals(150_000, OutboxRetentionPolicy.effectiveMax(150_000))
    }

    @Test
    fun jornada72hCabeEnRetencion() {
        // 72 h @10s (defecto) = 25 920; 24 h @10s = 8 640.
        assertEquals(25_920L, OutboxRetentionPolicy.estimatePositions(72.0, 10.0))
        assertEquals(8_640L, OutboxRetentionPolicy.estimatePositions(24.0, 10.0))
        assertTrue(OutboxRetentionPolicy.fitsRetention(72.0, 10.0))
        assertTrue(OutboxRetentionPolicy.fitsRetention(24.0, 3.0))
        // 7 días @10s = 60 480 cabe; 7 días @3s (mínimo) = 201 600 no cabe:
        // en ese extremo documentado rige el tope por count (con alerta).
        assertTrue(OutboxRetentionPolicy.fitsRetention(7.0 * 24.0, 10.0))
        assertFalse(OutboxRetentionPolicy.fitsRetention(7.0 * 24.0, 3.0))
    }
}
