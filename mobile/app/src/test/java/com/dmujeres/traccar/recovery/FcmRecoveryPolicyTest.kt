package com.dmujeres.traccar.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests PURA del FCM Recovery Android (JVM): validación de payload, prioridad,
 * dedupe y degradación. NUNCA se declara éxito aquí (eso lo decide el server
 * con evidencia end-to-end).
 */
class FcmRecoveryPolicyTest {

    private val now = 1_760_000_000_000L

    private fun data(
        type: String = FcmRecoveryPolicy.TYPE_PROBE,
        attemptId: String = "fcm-abc-123",
        deviceId: String = "qa-f0",
        issuedAt: Long = now - 1_000L,
    ) = mapOf(
        "type" to type,
        "recoveryAttemptId" to attemptId,
        "deviceId" to deviceId,
        "issuedAt" to issuedAt.toString(),
    )

    @Test
    fun payloadValidoPasa() {
        val v = FcmRecoveryPolicy.validate(data(), "qa-f0", "", now)
        assertTrue(v.valid)
        assertEquals("fcm-abc-123", v.attemptId)
    }

    @Test
    fun tipoIncorrectoBloqueaInvalidPayload() {
        val v = FcmRecoveryPolicy.validate(data(type = "OTRO"), "qa-f0", "", now)
        assertFalse(v.valid)
        assertEquals("BLOCKED_INVALID_PAYLOAD", v.reason)
    }

    @Test
    fun attemptIdVacioBloquea() {
        val v = FcmRecoveryPolicy.validate(data(attemptId = ""), "qa-f0", "", now)
        assertFalse(v.valid)
        assertEquals("BLOCKED_INVALID_PAYLOAD", v.reason)
    }

    @Test
    fun deviceMismatchBloquea() {
        val v = FcmRecoveryPolicy.validate(data(deviceId = "otro"), "qa-f0", "", now)
        assertFalse(v.valid)
        assertEquals("BLOCKED_DEVICE_MISMATCH", v.reason)
    }

    @Test
    fun issuedAtViejoBloquea() {
        val v = FcmRecoveryPolicy.validate(
            data(issuedAt = now - FcmRecoveryPolicy.MAX_PROBE_AGE_MS - 1), "qa-f0", "", now,
        )
        assertFalse(v.valid)
        assertEquals("BLOCKED_INVALID_PAYLOAD", v.reason)
    }

    @Test
    fun issuedAtFuturoExcesivoBloquea() {
        val v = FcmRecoveryPolicy.validate(
            data(issuedAt = now + FcmRecoveryPolicy.MAX_PROBE_FUTURE_MS + 1), "qa-f0", "", now,
        )
        assertFalse(v.valid)
    }

    @Test
    fun probeDuplicadoBloquea() {
        val v = FcmRecoveryPolicy.validate(data(attemptId = "fcm-dup"), "qa-f0", "fcm-dup", now)
        assertFalse(v.valid)
        assertEquals("BLOCKED_DUPLICATE", v.reason)
    }

    @Test
    fun prioridadClasificada() {
        assertEquals(FcmRecoveryPolicy.PRIORITY_HIGH, FcmRecoveryPolicy.classifyPriority(1))
        assertEquals(FcmRecoveryPolicy.PRIORITY_NORMAL, FcmRecoveryPolicy.classifyPriority(2))
        assertEquals(FcmRecoveryPolicy.PRIORITY_UNKNOWN, FcmRecoveryPolicy.classifyPriority(0))
    }

    @Test
    fun normalEsDegradadaHighNo() {
        assertTrue(FcmRecoveryPolicy.isDegraded(FcmRecoveryPolicy.PRIORITY_NORMAL))
        assertTrue(FcmRecoveryPolicy.isDegraded(FcmRecoveryPolicy.PRIORITY_UNKNOWN))
        assertTrue(FcmRecoveryPolicy.isDegraded(FcmRecoveryPolicy.PRIORITY_DEGRADED))
        assertFalse(FcmRecoveryPolicy.isDegraded(FcmRecoveryPolicy.PRIORITY_HIGH))
    }

    // ---- classifyDelivery: degradación real original vs entregada ------------

    @Test
    fun originalHighEntregadaNormalEsDegraded() {
        assertEquals(
            FcmRecoveryPolicy.PRIORITY_DEGRADED,
            FcmRecoveryPolicy.classifyDelivery(1, 2),
        )
    }

    @Test
    fun originalHighEntregadaHighEsHigh() {
        assertEquals(
            FcmRecoveryPolicy.PRIORITY_HIGH,
            FcmRecoveryPolicy.classifyDelivery(1, 1),
        )
    }

    @Test
    fun originalNormalEntregadaNormalEsNormal() {
        assertEquals(
            FcmRecoveryPolicy.PRIORITY_NORMAL,
            FcmRecoveryPolicy.classifyDelivery(2, 2),
        )
    }

    @Test
    fun originalNormalEntregadaHighEsHigh() {
        // Una mejora de entrega no es degradación: manda la entregada.
        assertEquals(
            FcmRecoveryPolicy.PRIORITY_HIGH,
            FcmRecoveryPolicy.classifyDelivery(2, 1),
        )
    }

    @Test
    fun prioridadDesconocidaEnOriginalOEntregadaEsUnknown() {
        assertEquals(FcmRecoveryPolicy.PRIORITY_UNKNOWN, FcmRecoveryPolicy.classifyDelivery(0, 1))
        assertEquals(FcmRecoveryPolicy.PRIORITY_UNKNOWN, FcmRecoveryPolicy.classifyDelivery(1, 0))
        assertEquals(FcmRecoveryPolicy.PRIORITY_UNKNOWN, FcmRecoveryPolicy.classifyDelivery(0, 0))
    }

    // ---- classifyVerify: timeout solo para FGS; GPS tardío = PENDING --------

    @Test
    fun sinFgsEsTimeoutReal() {
        assertEquals(
            FcmRecoveryPolicy.VERIFY_TIMEOUT_NO_FGS,
            FcmRecoveryPolicy.classifyVerify(fgsRunning = false, fixFresh = false),
        )
        // Sin FGS no importa si hubo fix: el timeout de FGS manda.
        assertEquals(
            FcmRecoveryPolicy.VERIFY_TIMEOUT_NO_FGS,
            FcmRecoveryPolicy.classifyVerify(fgsRunning = false, fixFresh = true),
        )
    }

    @Test
    fun fgsVivoConFixEsGpsConfirmado() {
        assertEquals(
            FcmRecoveryPolicy.VERIFY_GPS_CONFIRMED,
            FcmRecoveryPolicy.classifyVerify(fgsRunning = true, fixFresh = true),
        )
    }

    @Test
    fun fgsVivoSinFixEsPendingNoTimeout() {
        val verdict = FcmRecoveryPolicy.classifyVerify(fgsRunning = true, fixFresh = false)
        assertEquals(FcmRecoveryPolicy.VERIFY_GPS_PENDING, verdict)
        assertTrue(verdict != FcmRecoveryPolicy.VERIFY_TIMEOUT_NO_FGS)
    }

    @Test
    fun issuedAtInvalidoBloquea() {
        val v = FcmRecoveryPolicy.validate(mapOf(
            "type" to FcmRecoveryPolicy.TYPE_PROBE,
            "recoveryAttemptId" to "x",
            "deviceId" to "qa-f0",
            "issuedAt" to "no-numero",
        ), "qa-f0", "", now)
        assertFalse(v.valid)
    }
}
