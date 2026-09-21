package com.dmujeres.traccar.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests JVM del registro del token: el prefijo hash es determinista
 * (registro idempotente: mismo token → mismo prefijo) y NUNCA se persiste
 * ni se loguea el token completo.
 */
class FcmTokenRegistrarTest {

    private val token = "fak3-FCM-token-para-tests-jvm-0123456789"

    @Test
    fun prefijoEsDeterminista() {
        assertEquals(FcmTokenRegistrar.prefixOf(token), FcmTokenRegistrar.prefixOf(token))
    }

    @Test
    fun prefijoNoExponeElToken() {
        val prefix = FcmTokenRegistrar.prefixOf(token)
        assertEquals(12, prefix.length)
        assertFalse(prefix.contains(token))
        assertFalse(token.contains(prefix))
    }

    @Test
    fun tokensDistintosDanPrefijosDistintos() {
        assertNotEquals(
            FcmTokenRegistrar.prefixOf(token),
            FcmTokenRegistrar.prefixOf("$token-rotado"),
        )
    }
}
