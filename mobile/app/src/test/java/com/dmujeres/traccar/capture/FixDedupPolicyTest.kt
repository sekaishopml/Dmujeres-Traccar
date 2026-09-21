package com.dmujeres.traccar.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dedupe L1: reintentos del FLP no deben duplicar fixes en la cola. */
class FixDedupPolicyTest {

    @Test
    fun mismoFixDosVecesSeAceptaUnaSolaVez() {
        val dedup = FixDedupPolicy()
        assertTrue(dedup.accept(1_000L, -2.20, -79.88))
        assertFalse(dedup.accept(1_000L, -2.20, -79.88))
        assertEquals(1, dedup.size())
    }

    @Test
    fun mismasCoordsConNanosDistintosEsFixNuevo() {
        val dedup = FixDedupPolicy()
        assertTrue(dedup.accept(1_000L, -2.20, -79.88))
        assertTrue(dedup.accept(2_000L, -2.20, -79.88))
        assertEquals(2, dedup.size())
    }

    @Test
    fun mismasNanosConCoordsDistintasEsFixNuevo() {
        val dedup = FixDedupPolicy()
        assertTrue(dedup.accept(1_000L, -2.20, -79.88))
        assertTrue(dedup.accept(1_000L, -2.21, -79.88))
        assertEquals(2, dedup.size())
    }

    @Test
    fun coordsQueRedondeanAlMismoMetroSonDuplicado() {
        // Sub-metro (< 5 decimales): misma clave de dedupe.
        val dedup = FixDedupPolicy()
        assertTrue(dedup.accept(1_000L, -2.2000004, -79.8800001))
        assertFalse(dedup.accept(1_000L, -2.2000001, -79.8800004))
    }

    @Test
    fun laVentanaDesalojaEnOrdenFifo() {
        val dedup = FixDedupPolicy(window = 2)
        assertTrue(dedup.accept(1L, 1.0, 1.0))
        assertTrue(dedup.accept(2L, 2.0, 2.0))
        assertTrue(dedup.accept(3L, 3.0, 3.0))
        assertEquals(2, dedup.size())
        // La primera clave fue desalojada: vuelve a aceptarse como nueva.
        assertTrue(dedup.accept(1L, 1.0, 1.0))
    }
}
