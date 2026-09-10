package com.dmujeres.traccar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RttMeterTest {

    @Before
    fun resetMeter() {
        RttMeter.reset()
    }

    @Test
    fun firstSampleSetsInitialValue() {
        assertEquals(-1L, RttMeter.current())

        assertEquals(500L, RttMeter.update(500L))
        assertEquals(500L, RttMeter.current())
    }

    @Test
    fun nonPositiveSamplesAreIgnored() {
        assertEquals(-1L, RttMeter.update(0L))
        assertEquals(-1L, RttMeter.current())

        RttMeter.update(400L)
        assertEquals(400L, RttMeter.update(-50L))
        assertEquals(400L, RttMeter.current())
    }

    @Test
    fun ewmaConvergesTowardRepeatedValue() {
        RttMeter.update(100L)
        // Con alpha 0.3, tras 30 muestras de 1000 el residuo inicial es 0.7^30 ≈ 0.002 %.
        repeat(30) { RttMeter.update(1000L) }

        assertTrue("EWMA no converge: ${RttMeter.current()}", Math.abs(RttMeter.current() - 1000L) <= 2L)
    }

    @Test
    fun ewmaTracksTrend() {
        RttMeter.update(1000L)
        val afterOneSpike = RttMeter.update(100L)
        // 0.3*100 + 0.7*1000 = 730: se mueve hacia la muestra nueva, no la copia.
        assertEquals(730L, afterOneSpike)
        assertTrue(afterOneSpike > 100L && afterOneSpike < 1000L)
    }

    @Test
    fun concurrentUpdatesStayWithinSampleBounds() {
        val threads = 8
        val updatesPerThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                ready.countDown()
                ready.await()
                repeat(updatesPerThread) { i ->
                    RttMeter.update(1L + (i % 1000))
                }
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        val result = RttMeter.current()
        assertTrue("EWMA fuera de rango: $result", result in 1L..1000L)
    }
}
