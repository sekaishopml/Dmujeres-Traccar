package com.dmujeres.traccar.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Matemática pura de los buckets diarios de salud (epochDay, auto-reset al
 * rodar el día). Solo companion: sin Android, testeable en JVM (mismo patrón
 * que RemoteBufferPolicyTest).
 */
class HealthBucketTest {

    @Test
    fun epochDayDeMedianocheYMediodia() {
        val day = 86_400_000L
        assertEquals(0L, AppConfig.epochDayOf(0L))
        assertEquals(20_000L, AppConfig.epochDayOf(20_000L * day))
        // Mediodía UTC sigue siendo el mismo día.
        assertEquals(20_000L, AppConfig.epochDayOf(20_000L * day + day / 2))
        // Justo la frontera: día siguiente.
        assertEquals(20_001L, AppConfig.epochDayOf(20_001L * day))
        // Antes de 1970: floorDiv (no truncado hacia cero).
        assertEquals(-1L, AppConfig.epochDayOf(-1L))
    }

    @Test
    fun roundTripDelBucket() {
        val stored = AppConfig.encodeDailyBucket(123L, 5)
        assertEquals("123:5", stored)
        assertEquals(5, AppConfig.decodeDailyBucket(stored, 123L))
    }

    @Test
    fun rolloverDelDiaReiniciaElContador() {
        val yesterday = AppConfig.encodeDailyBucket(122L, 9)
        // Mismo bucket leído HOY → 0 (auto-reset, sin limpieza explícita).
        assertEquals(0, AppConfig.decodeDailyBucket(yesterday, 123L))
        // Y al revés: el valor de hoy no se lee como si fuera de ayer.
        val today = AppConfig.encodeDailyBucket(123L, 9)
        assertEquals(0, AppConfig.decodeDailyBucket(today, 122L))
    }

    @Test
    fun bucketCorruptoOAusenteValeCero() {
        assertEquals(0, AppConfig.decodeDailyBucket(null, 123L))
        assertEquals(0, AppConfig.decodeDailyBucket("", 123L))
        assertEquals(0, AppConfig.decodeDailyBucket("basura", 123L))
        assertEquals(0, AppConfig.decodeDailyBucket("123", 123L))
        assertEquals(0, AppConfig.decodeDailyBucket("123:nope", 123L))
        assertEquals(0, AppConfig.decodeDailyBucket("123:-3", 123L)) // valor negativo → 0
    }

    @Test
    fun escribeNegaTivosComoCero() {
        assertEquals("7:0", AppConfig.encodeDailyBucket(7L, -4))
    }
}
