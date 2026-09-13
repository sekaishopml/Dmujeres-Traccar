package com.dmujeres.traccar.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Robustez de testConnection y constantes del dispatch paginado.
 * Puro JVM: no toca red ni Android (solo helpers del companion).
 */
class MqttRobustnessTest {

    @Test
    fun testClientIdConSufijoFijo() {
        assertEquals(
            "dmj-test-user-abcd",
            MqttManager.buildTestClientId("user", "abcd")
        )
    }

    @Test
    fun testClientIdSanitizaUsuario() {
        // Solo letras/dígitos, como el connect() original.
        assertEquals(
            "dmj-test-user12-abcd",
            MqttManager.buildTestClientId("us@er!12", "abcd")
        )
    }

    @Test
    fun testClientIdTruncaA16() {
        val largo = "abcdefghijklmnopqrstuvwxyz0123456789"
        val id = MqttManager.buildTestClientId(largo, "abcd")
        // "dmj-test-" + 16 chars + "-" + sufijo
        assertEquals("dmj-test-abcdefghijklmnop-abcd", id)
    }

    @Test
    fun testClientIdSufijosDistintosDanIdsDistintos() {
        assertNotEquals(
            MqttManager.buildTestClientId("user", "aaaa"),
            MqttManager.buildTestClientId("user", "bbbb")
        )
    }

    @Test
    fun randomSuffixFormatoHex() {
        repeat(20) {
            val s = MqttManager.randomSuffix()
            assertTrue("sufijo vacío", s.isNotEmpty())
            assertTrue("sufijo debe ser hex: $s", s.all { it in '0'..'9' || it in 'a'..'f' })
            assertTrue("sufijo mínimo 4 chars: $s", s.length >= 4)
        }
    }

    @Test
    fun timeoutEs12Segundos() {
        assertEquals(12_000L, MqttManager.TEST_CONNECTION_TIMEOUT_MS)
    }

    @Test
    fun batchSizeEs100() {
        assertEquals(100, MqttManager.DISPATCH_BATCH_SIZE)
    }

    @Test
    fun guardColgado20sSeLibera() {
        // Paho nunca respondió (socket colgado): el watchdog libera la cuña.
        val now = 1_000_000L
        assertTrue(StaleConnectingPolicy.clearIfStale(true, now - 20_000L, now))
    }

    @Test
    fun guardCon5sNoSeLibera() {
        // Un intento EN CURSO legítimo (15 s > Paho timeout 10 s) no se toca.
        val now = 1_000_000L
        assertTrue(!StaleConnectingPolicy.clearIfStale(true, now - 5_000L, now))
    }

    @Test
    fun guardNoStaleSiNoConectandoOsinMarca() {
        val now = 1_000_000L
        // Sin connecting activo: nada que liberar aunque la marca sea vieja.
        assertTrue(!StaleConnectingPolicy.clearIfStale(false, now - 60_000L, now))
        // Sin marca (0): nunca se considera colgado.
        assertTrue(!StaleConnectingPolicy.clearIfStale(true, 0L, now))
    }

    @Test
    fun guardColgadoJustoEnElUmbralNoSeLibera() {
        // 15 s exactos NO superan el umbral (> watchdogMs), 1 ms más sí.
        val now = 1_000_000L
        assertTrue(!StaleConnectingPolicy.clearIfStale(true, now - 15_000L, now))
        assertTrue(StaleConnectingPolicy.clearIfStale(true, now - 15_001L, now))
    }
}
