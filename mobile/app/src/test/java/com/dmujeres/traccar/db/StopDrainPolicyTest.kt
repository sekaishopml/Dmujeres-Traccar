package com.dmujeres.traccar.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drenaje al cerrar jornada: vaciar o deadline, nunca abortar al primer fallo
 * ni descartar. Puro JVM.
 */
class StopDrainPolicyTest {

    @Test
    fun deadlineEs4Minutos() {
        assertEquals(240_000L, StopDrainPolicy.TIMEOUT_MS)
    }

    @Test
    fun sinProgresoEsperaEnVezDeAbortar() {
        // El bug: `if (flushed <= 0) return` colapsaba la ventana de 90 s a un
        // solo intento. Ahora el flush fallido espera y reintenta.
        assertEquals(StopDrainPolicy.RETRY_DELAY_MS, StopDrainPolicy.retryDelayAfter(0))
        assertTrue(StopDrainPolicy.retryDelayAfter(0) > 0L)
    }

    @Test
    fun conProgresoReintentaDeInmediato() {
        assertEquals(0L, StopDrainPolicy.retryDelayAfter(1))
        assertEquals(0L, StopDrainPolicy.retryDelayAfter(50))
    }

    @Test
    fun respetaDeadline() {
        val deadline = 1_000_000L
        assertFalse(StopDrainPolicy.timedOut(deadline - 1, deadline))
        assertTrue(StopDrainPolicy.timedOut(deadline, deadline))
        assertTrue(StopDrainPolicy.timedOut(deadline + 1, deadline))
    }
}
