package com.dmujeres.traccar.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferDrainPolicyTest {

    @Test
    fun continuesWhileProgress() {
        assertTrue(BufferDrainPolicy.continueDraining(lastConfirmed = 50, batchesDone = 1))
        assertTrue(BufferDrainPolicy.continueDraining(lastConfirmed = 3, batchesDone = 199))
    }

    @Test
    fun stopsWhenStalled() {
        // Lote sin confirmar: backlog vacío o enlace caído, no insistir.
        assertFalse(BufferDrainPolicy.continueDraining(lastConfirmed = 0, batchesDone = 1))
    }

    @Test
    fun stopsAtCap() {
        // 200 lotes × 50 = 10 000 puntos/evento (≈72 h offline en ~3 eventos).
        assertFalse(BufferDrainPolicy.continueDraining(lastConfirmed = 50, batchesDone = 200))
        assertFalse(BufferDrainPolicy.continueDraining(lastConfirmed = 50, batchesDone = 201))
    }
}
