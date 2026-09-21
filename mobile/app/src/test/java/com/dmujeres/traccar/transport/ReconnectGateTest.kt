package com.dmujeres.traccar.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Puerta única de reconexión: backoff exponencial + jitter, con techo y
 * debounces. Pura JVM.
 */
class ReconnectGateTest {

    @Test
    fun primerReintentoRapido() {
        // Sin jitter: 5 s. Con jitter ±25 %: [3750, 6250].
        assertEquals(5_000L, ReconnectGate.connectDelayMs(0, random01 = 0.5))
        assertEquals(3_750L, ReconnectGate.connectDelayMs(0, random01 = 0.0))
        assertEquals(6_250L, ReconnectGate.connectDelayMs(0, random01 = 1.0))
    }

    @Test
    fun backoffExponencialConTecho() {
        assertEquals(10_000L, ReconnectGate.connectDelayMs(1, random01 = 0.5))
        assertEquals(20_000L, ReconnectGate.connectDelayMs(2, random01 = 0.5))
        assertEquals(40_000L, ReconnectGate.connectDelayMs(3, random01 = 0.5))
        // Techo 5 min aunque los fallos se acumulen durante días.
        assertEquals(300_000L, ReconnectGate.connectDelayMs(10, random01 = 0.5))
        assertEquals(300_000L, ReconnectGate.connectDelayMs(100, random01 = 1.0))
    }

    @Test
    fun viaBackoffRespetaVentanaYDebounce() {
        val allowed = 100_000L
        val last = allowed - 10_000L
        assertTrue(ReconnectGate.shouldAttempt(allowed, allowed, last))
        // Dentro del debounce aunque la ventana ya abrió: no (anti-flap).
        assertFalse(ReconnectGate.shouldAttempt(allowed + 1_000L, allowed, allowed))
        // Ventana aún cerrada: no.
        assertFalse(ReconnectGate.shouldAttempt(allowed - 1L, allowed, last))
    }

    @Test
    fun viaInmediataSoloDebounce() {
        val last = 50_000L
        assertFalse(ReconnectGate.shouldAttemptImmediate(last + 1_000L, last))
        assertTrue(ReconnectGate.shouldAttemptImmediate(last + 2_000L, last))
    }
}
