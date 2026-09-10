package com.dmujeres.traccar.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backoff con jitter ±25% y techo 5 min (puro, determinista con random01).
 * random01 = 0.5 -> sin jitter; 0.0 -> -25%; 1.0 -> +25%.
 */
class DispatchBackoffTest {

    @Test
    fun jitteredSinJitterEsBase() {
        assertEquals(10_000L, DispatchPolicy.jitteredDelay(10_000L, random01 = 0.5))
        assertEquals(30_000L, DispatchPolicy.jitteredDelay(30_000L, random01 = 0.5))
    }

    @Test
    fun jitterRango25PorCiento() {
        assertEquals(7_500L, DispatchPolicy.jitteredDelay(10_000L, random01 = 0.0))
        assertEquals(12_500L, DispatchPolicy.jitteredDelay(10_000L, random01 = 1.0))
        assertEquals(22_500L, DispatchPolicy.jitteredDelay(30_000L, random01 = 0.0))
        assertEquals(37_500L, DispatchPolicy.jitteredDelay(30_000L, random01 = 1.0))
    }

    @Test
    fun jitterRespetaTecho5Min() {
        assertEquals(300_000L, DispatchPolicy.jitteredDelay(1_000_000L, random01 = 0.5))
        assertEquals(300_000L, DispatchPolicy.jitteredDelay(1_000_000L, random01 = 1.0))
        // Base ya acotada + jitter no supera el techo.
        assertTrue(DispatchPolicy.jitteredDelay(300_000L, random01 = 1.0) <= 300_000L)
    }

    @Test
    fun dispatchBackoffExponencialSinJitter() {
        assertEquals(5_000L, DispatchPolicy.dispatchBackoffMs(0, random01 = 0.5))
        assertEquals(10_000L, DispatchPolicy.dispatchBackoffMs(1, random01 = 0.5))
        assertEquals(20_000L, DispatchPolicy.dispatchBackoffMs(2, random01 = 0.5))
        assertEquals(40_000L, DispatchPolicy.dispatchBackoffMs(3, random01 = 0.5))
    }

    @Test
    fun dispatchBackoffConJitter() {
        // attempts=1 -> base 10s; ±25% -> 7.5s / 12.5s
        assertEquals(7_500L, DispatchPolicy.dispatchBackoffMs(1, random01 = 0.0))
        assertEquals(12_500L, DispatchPolicy.dispatchBackoffMs(1, random01 = 1.0))
    }

    @Test
    fun dispatchBackoffTecho5Min() {
        // 5s * 2^8 = 1.28M -> techo 300k
        assertEquals(300_000L, DispatchPolicy.dispatchBackoffMs(8, random01 = 0.5))
        assertEquals(300_000L, DispatchPolicy.dispatchBackoffMs(8, random01 = 1.0))
        assertEquals(300_000L, DispatchPolicy.dispatchBackoffMs(100, random01 = 0.5))
        assertEquals(300_000L, DispatchPolicy.dispatchBackoffMs(7, random01 = 0.5).coerceAtMost(300_000L))
    }

    @Test
    fun connectRetryBase30sConJitter() {
        assertEquals(30_000L, DispatchPolicy.connectRetryDelayMs(random01 = 0.5))
        assertEquals(22_500L, DispatchPolicy.connectRetryDelayMs(random01 = 0.0))
        assertEquals(37_500L, DispatchPolicy.connectRetryDelayMs(random01 = 1.0))
    }

    @Test
    fun connectRetryRespetaTecho() {
        assertEquals(
            300_000L,
            DispatchPolicy.connectRetryDelayMs(baseMs = 1_000_000L, random01 = 1.0)
        )
    }

    @Test
    fun jitterEvitaThunderingHerd() {
        val bajo = DispatchPolicy.dispatchBackoffMs(2, random01 = 0.0)
        val alto = DispatchPolicy.dispatchBackoffMs(2, random01 = 1.0)
        assertTrue(bajo < alto)
        // Rango esperado: base 20s -> 15s..25s
        assertEquals(15_000L, bajo)
        assertEquals(25_000L, alto)
    }

    @Test
    fun attemptsNegativosComoCero() {
        assertEquals(5_000L, DispatchPolicy.dispatchBackoffMs(-5, random01 = 0.5))
    }

    @Test
    fun constantesDeRobustez() {
        assertEquals(5_000L, DispatchPolicy.DISPATCH_BASE_MS)
        assertEquals(30_000L, DispatchPolicy.CONNECT_RETRY_BASE_MS)
        assertEquals(300_000L, DispatchPolicy.MAX_BACKOFF_MS)
        assertEquals(0.25, DispatchPolicy.JITTER_FRACTION, 0.0)
    }
}
