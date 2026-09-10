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
}
