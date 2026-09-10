package com.dmujeres.traccar.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El mínimo remoto del buffer debe cubrir jornadas largas sin señal: con un
 * mínimo bajo, drop_oldest tiraba posiciones viejas en rutas de 8 h.
 * Solo la función pura del companion (sin Android) para test JVM.
 */
class RemoteBufferPolicyTest {

    @Test
    fun minimoCubreJornadaDe8Horas() {
        // 8 h a 10 s = 2880 puntos; el mínimo debe cubrirlo con margen.
        assertTrue(AppConfig.REMOTE_BUFFER_MIN >= 2880)
        assertEquals(5000, AppConfig.REMOTE_BUFFER_MIN)
    }

    @Test
    fun saneaValoresBajosAlMinimo() {
        assertEquals(5000, AppConfig.clampRemoteBufferMax(1000))
        assertEquals(5000, AppConfig.clampRemoteBufferMax(0))
        assertEquals(5000, AppConfig.clampRemoteBufferMax(-50))
    }

    @Test
    fun respetaValoresAltos() {
        assertEquals(8000, AppConfig.clampRemoteBufferMax(8000))
        assertEquals(5000, AppConfig.clampRemoteBufferMax(5000))
    }
}
