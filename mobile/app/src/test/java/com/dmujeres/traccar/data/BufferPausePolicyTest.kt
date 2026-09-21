package com.dmujeres.traccar.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R8: la captura en stop_capture debe reanudar apenas hay espacio real. */
class BufferPausePolicyTest {

    @Test
    fun reanudaConMargenFijoNoCon80PorCiento() {
        val max = 100_000
        // Con el tope lleno NO reanuda.
        assertFalse(BufferPausePolicy.shouldResume(max, max))
        // A 99 400 (margen 500 → límite 99 500) reanuda: antes con 80 % habría
        // seguido pausado hasta bajar de 80 000 (horas con la captura apagada).
        assertTrue(BufferPausePolicy.shouldResume(max - 600, max))
        assertFalse(BufferPausePolicy.shouldResume(max - 400, max))
    }

    @Test
    fun topeInvalidoNoReanudaSolo() {
        assertFalse(BufferPausePolicy.shouldResume(0, 0))
        assertFalse(BufferPausePolicy.shouldResume(0, -1))
    }
}
