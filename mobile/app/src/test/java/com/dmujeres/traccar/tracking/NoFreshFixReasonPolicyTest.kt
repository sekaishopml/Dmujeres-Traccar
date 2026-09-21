package com.dmujeres.traccar.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoFreshFixReasonPolicyTest {

    @Test
    fun `proveedor fallando gana sobre cualquier otro motivo`() {
        assertEquals(
            NoFreshFixReasonPolicy.Reason.PROVIDER_FAIL,
            NoFreshFixReasonPolicy.classify("MOVING", 8, providerFail = true),
        )
        assertEquals(
            NoFreshFixReasonPolicy.Reason.PROVIDER_FAIL,
            NoFreshFixReasonPolicy.classify("STATIONARY", null, providerFail = true),
        )
    }

    @Test
    fun `quieto es esperado no un fallo`() {
        assertEquals(
            NoFreshFixReasonPolicy.Reason.STATIONARY,
            NoFreshFixReasonPolicy.classify("STATIONARY", 0, providerFail = false),
        )
    }

    @Test
    fun `en movimiento sin satelites es falta de cielo`() {
        assertEquals(
            NoFreshFixReasonPolicy.Reason.NO_SKY,
            NoFreshFixReasonPolicy.classify("MOVING", 0, providerFail = false),
        )
    }

    @Test
    fun `en movimiento con satelites pero sin fix fresco es despertar tardio`() {
        assertEquals(
            NoFreshFixReasonPolicy.Reason.PROCESS_WOKE_LATE,
            NoFreshFixReasonPolicy.classify("MOVING", 6, providerFail = false),
        )
    }

    @Test
    fun `movimiento desconocido o sin dato de satelites no inventa causa`() {
        assertEquals(
            NoFreshFixReasonPolicy.Reason.UNKNOWN,
            NoFreshFixReasonPolicy.classify("MOVING", null, providerFail = false),
        )
        assertEquals(
            NoFreshFixReasonPolicy.Reason.UNKNOWN,
            NoFreshFixReasonPolicy.classify("UNKNOWN", 5, providerFail = false),
        )
    }

    @Test
    fun `solo NO_FRESH_FIX lleva sufijo de motivo`() {
        val reason = NoFreshFixReasonPolicy.reasonFor("NO_FRESH_FIX", "MOVING", 4, providerFail = false)
        assertEquals("NO_FRESH_FIX:PROCESS_WOKE_LATE", reason)
        assertEquals("TRACKING_ACTIVE", NoFreshFixReasonPolicy.reasonFor("TRACKING_ACTIVE", "MOVING", 4, false))
        assertEquals("NETWORK_OFFLINE", NoFreshFixReasonPolicy.reasonFor("NETWORK_OFFLINE", "STATIONARY", null, false))
    }

    @Test
    fun `la razon cabe en el limite del servidor de 64 caracteres`() {
        val longest = NoFreshFixReasonPolicy.Reason.values().maxOf { it.name.length }
        val maxReason = "NO_FRESH_FIX:".length + longest
        assertTrue("razón más larga = $maxReason caracteres", maxReason <= 64)
    }
}
