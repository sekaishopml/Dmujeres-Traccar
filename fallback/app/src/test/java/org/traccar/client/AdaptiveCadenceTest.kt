package org.traccar.client

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveCadenceTest {

    @Test
    fun `en movimiento pide fino, con precision alta y sin batching`() {
        val request = AdaptiveCadence.request(moving = true, configuredAccuracy = null)
        assertEquals(10_000L, request.intervalMs)
        assertEquals(10f, request.minDistanceM, 0.0f)
        assertEquals(0L, request.maxUpdateDelayMs)
        assertEquals(AdaptiveCadence.Accuracy.HIGH, request.accuracy)
    }

    @Test
    fun `parado pide grueso, balanceado y SIN batching`() {
        val request = AdaptiveCadence.request(moving = false, configuredAccuracy = "high")
        assertEquals(120_000L, request.intervalMs)
        assertEquals(10f, request.minDistanceM, 0.0f)
        // Sin batching: el doze estiraba los puntos de 2 min a 10-60 min.
        assertEquals(0L, request.maxUpdateDelayMs)
        assertEquals(AdaptiveCadence.Accuracy.BALANCED, request.accuracy)
    }

    @Test
    fun `la precision remota manda en movimiento`() {
        assertEquals(AdaptiveCadence.Accuracy.LOW, AdaptiveCadence.request(true, "low").accuracy)
        assertEquals(AdaptiveCadence.Accuracy.BALANCED, AdaptiveCadence.request(true, "medium").accuracy)
    }

    @Test
    fun `precision invalida cae al default`() {
        assertEquals(AdaptiveCadence.Accuracy.HIGH, AdaptiveCadence.request(true, "raro").accuracy)
        assertEquals(AdaptiveCadence.Accuracy.HIGH, AdaptiveCadence.request(true, null).accuracy)
    }

    @Test
    fun `el intervalo de reporte sigue al estado`() {
        assertEquals(10_000L, AdaptiveCadence.intervalMs(true))
        assertEquals(120_000L, AdaptiveCadence.intervalMs(false))
    }

    @Test
    fun `la frecuencia del panel manda en movimiento dentro de 10 a 60 s`() {
        assertEquals(30_000L, AdaptiveCadence.movingIntervalMs(30L))
        assertEquals(60_000L, AdaptiveCadence.movingIntervalMs(300L))
        assertEquals(10_000L, AdaptiveCadence.movingIntervalMs(5L))
        assertEquals(10_000L, AdaptiveCadence.movingIntervalMs(null))
    }

    @Test
    fun `la frecuencia del panel tambien alinea el reporte en movimiento`() {
        val request = AdaptiveCadence.request(true, "high", 45L)
        assertEquals(45_000L, request.intervalMs)
        // Parado no la usa: siempre 120 s para ahorrar.
        val stopped = AdaptiveCadence.request(false, "high", 45L)
        assertEquals(120_000L, stopped.intervalMs)
    }
}
