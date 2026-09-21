package com.dmujeres.traccar.readiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del DeviceReadinessPolicy.
 *
 * SEMÁNTICA VIGENTE (decisión de producto, ajuste aprobado): el veredicto
 * depende de los checks públicos + OEM confirmado; la prueba de CONTINUIDAD
 * vive en Diagnóstico y queda REGISTRADA como informativa — nunca bloquea ni
 * disfraza: NOT_VERIFIABLE ≠ PASS y su estado se imprime tal cual.
 */
class DeviceReadinessPolicyTest {

    private fun passContinuity(nowMs: Long = 1_000_000L) = ContinuityOutcome(
        state = CheckState.PASS,
        cause = "",
        fixesDuringOff = 2,
        screenOffMs = nowMs - 90_000L,
        windowMs = 90_000L,
        frozenSeconds = 0,
        atMs = nowMs,
        note = "captura continuó con pantalla apagada",
    )

    private fun baseInput(
        nowMs: Long = 1_000_000L,
        oemGuidePresent: Boolean = false,
        oemConfirmed: Boolean = false,
        continuity: ContinuityOutcome = passContinuity(nowMs),
        recovery: RecoveryOutcome = RecoveryOutcome(CheckState.NOT_RUN, 0, ""),
    ) = DeviceReadinessPolicy.Input(
        locationEnabled = true,
        fineGranted = true,
        backgroundGranted = true,
        fslGranted = true,
        notificationsGranted = true,
        fgsRequirementsOk = true,
        batteryExempt = true,
        standbyBucket = DeviceReadinessPolicy.BUCKET_ACTIVE,
        backgroundRestricted = false,
        sdkInt = 34,
        oemKey = null,
        oemGuidePresent = oemGuidePresent,
        oemConfirmed = oemConfirmed,
        continuity = continuity,
        recovery = recovery,
        nowMs = nowMs,
    )

    @Test
    fun `1 location OFF produce FAILED y NOT_READY`() {
        val r = DeviceReadinessPolicy.evaluate(
            baseInput().copy(locationEnabled = false),
        )
        assertEquals(DeviceReadinessPolicy.VERDICT_NOT_READY, r.verdict)
        assertTrue(r.notReadyBecause.contains("location"))
    }

    @Test
    fun `2 permisos faltantes producen FAILED y NOT_READY`() {
        val r = DeviceReadinessPolicy.evaluate(baseInput().copy(fineGranted = false))
        assertEquals(DeviceReadinessPolicy.VERDICT_NOT_READY, r.verdict)
        assertTrue(r.notReadyBecause.contains("permissions"))
    }

    @Test
    fun `3 FGS invalido produce FAILED`() {
        val r = DeviceReadinessPolicy.evaluate(baseInput().copy(fgsRequirementsOk = false))
        assertEquals(DeviceReadinessPolicy.VERDICT_NOT_READY, r.verdict)
        assertTrue(r.notReadyBecause.contains("fgs"))
    }

    @Test
    fun `4 bucket 45 produce FAILED y NOT_READY aunque todo lo demas PASS`() {
        val r = DeviceReadinessPolicy.evaluate(
            baseInput().copy(standbyBucket = DeviceReadinessPolicy.BUCKET_RESTRICTED),
        )
        assertEquals(DeviceReadinessPolicy.VERDICT_NOT_READY, r.verdict)
        assertTrue(r.notReadyBecause.contains("standby"))
        assertEquals(
            CheckState.FAILED,
            r.checks[CheckKey.STANDBY_BUCKET]?.state,
        )
    }

    @Test
    fun `5 bucket 40 es WARNING y NO bloquea`() {
        val r = DeviceReadinessPolicy.evaluate(
            baseInput().copy(standbyBucket = DeviceReadinessPolicy.BUCKET_RARE),
        )
        assertEquals(DeviceReadinessPolicy.VERDICT_READY, r.verdict)
        assertEquals(
            CheckState.WARNING,
            r.checks[CheckKey.STANDBY_BUCKET]?.state,
        )
    }

    @Test
    fun `6 backgroundRestricted sin exempt produce FAILED`() {
        val r = DeviceReadinessPolicy.evaluate(
            baseInput().copy(
                batteryExempt = false,
                backgroundRestricted = true,
            ),
        )
        assertEquals(CheckState.FAILED, r.checks[CheckKey.BACKGROUND_RESTRICTED]?.state)
        assertEquals(DeviceReadinessPolicy.VERDICT_NOT_READY, r.verdict)
    }

    @Test
    fun `7 backgroundRestricted con exempt y bucket sano es WARNING`() {
        val r = DeviceReadinessPolicy.evaluate(
            baseInput().copy(backgroundRestricted = true),
        )
        assertEquals(CheckState.WARNING, r.checks[CheckKey.BACKGROUND_RESTRICTED]?.state)
        assertEquals(DeviceReadinessPolicy.VERDICT_READY, r.verdict)
    }

    @Test
    fun `8 battery sin exempt con bucket sano es WARNING y NO bloquea`() {
        val r = DeviceReadinessPolicy.evaluate(
            baseInput().copy(batteryExempt = false),
        )
        assertEquals(CheckState.WARNING, r.checks[CheckKey.BATTERY_COMBINED]?.state)
        assertEquals(DeviceReadinessPolicy.VERDICT_READY, r.verdict)
    }

    @Test
    fun `9 battery sin exempt con bucket 45 produce FAILED`() {
        val r = DeviceReadinessPolicy.evaluate(
            baseInput().copy(
                batteryExempt = false,
                standbyBucket = DeviceReadinessPolicy.BUCKET_RESTRICTED,
            ),
        )
        assertEquals(CheckState.FAILED, r.checks[CheckKey.BATTERY_COMBINED]?.state)
        assertEquals(DeviceReadinessPolicy.VERDICT_NOT_READY, r.verdict)
    }

    @Test
    fun `10 OEM sin confirmar produce NOT_READY incluso con checks publicos PASS`() {
        val r = DeviceReadinessPolicy.evaluate(
            baseInput(oemGuidePresent = true, oemConfirmed = false),
        )
        assertEquals(DeviceReadinessPolicy.VERDICT_NOT_READY, r.verdict)
        assertTrue(r.notReadyBecause.contains("oem"))
        assertEquals(
            CheckState.NOT_VERIFIABLE,
            r.checks[CheckKey.OEM_CONFIG]?.state,
        )
    }

    @Test
    fun `11 OEM confirmado deja CONFIRMED nunca PASS`() {
        val r = DeviceReadinessPolicy.evaluate(
            baseInput(oemGuidePresent = true, oemConfirmed = true),
        )
        assertEquals(
            CheckState.CONFIRMED,
            r.checks[CheckKey.OEM_CONFIG]?.state,
        )
        // NOT_VERIFIABLE ≠ PASS: el check NO se convierte en PASS.
        assertFalse(r.checks[CheckKey.OEM_CONFIG]?.state == CheckState.PASS)
    }

    @Test
    fun `12 generic sin guia OEM produce PASS y puede ser READY`() {
        val r = DeviceReadinessPolicy.evaluate(baseInput())
        assertEquals(CheckState.PASS, r.checks[CheckKey.OEM_CONFIG]?.state)
        assertEquals(DeviceReadinessPolicy.VERDICT_READY, r.verdict)
    }

    @Test
    fun `CASO A continuity PASS y recovery NOT_RUN producen READY`() {
        val r = DeviceReadinessPolicy.evaluate(
            baseInput(
                oemGuidePresent = true,
                oemConfirmed = true,
                continuity = passContinuity(),
                recovery = RecoveryOutcome(CheckState.NOT_RUN, 0, ""),
            ),
        )
        assertEquals(DeviceReadinessPolicy.VERDICT_READY, r.verdict)
        assertTrue(r.readyBecause.contains("continuity-diag"))
    }

    @Test
    fun `CASO B continuity FAILED con recovery PASS queda READY con check informativo`() {
        // Decisión de producto: la continuidad vive en Diagnóstico y NO bloquea;
        // su estado FAILED queda registrado en el check para el técnico.
        val r = DeviceReadinessPolicy.evaluate(
            baseInput(
                oemGuidePresent = true,
                oemConfirmed = true,
                continuity = ContinuityOutcome(
                    state = CheckState.FAILED,
                    cause = "OEM_FREEZE",
                    fixesDuringOff = 2,
                    screenOffMs = 900_000L,
                    windowMs = 600_000L,
                    frozenSeconds = 120,
                    atMs = 1_000_000L,
                    note = "proceso congelado",
                ),
                recovery = RecoveryOutcome(CheckState.PASS, 1, "reactivado"),
            ),
        )
        assertEquals(DeviceReadinessPolicy.VERDICT_READY, r.verdict)
        assertEquals(
            CheckState.FAILED,
            r.checks[CheckKey.CONTINUITY_TEST]?.state,
        )
        assertEquals("OEM_FREEZE", r.continuity.cause)
    }

    @Test
    fun `CASO C continuity PASS con recovery FAILED produce READY con riesgo`() {
        val r = DeviceReadinessPolicy.evaluate(
            baseInput(
                continuity = passContinuity(),
                recovery = RecoveryOutcome(CheckState.FAILED, 2, "RECOVERY_BLOCKED"),
            ),
        )
        assertEquals(DeviceReadinessPolicy.VERDICT_READY, r.verdict)
        assertTrue(
            r.readyBecause.any { it.contains("recovery") },
        )
    }

    @Test
    fun `CASO D continuity INCONCLUSIVE queda informativo sin bloquear`() {
        val r = DeviceReadinessPolicy.evaluate(
            baseInput(
                oemGuidePresent = true,
                oemConfirmed = true,
                continuity = ContinuityOutcome(
                    state = CheckState.INCONCLUSIVE,
                    cause = "",
                    fixesDuringOff = 0,
                    screenOffMs = 900_000L,
                    windowMs = 0L,
                    frozenSeconds = 0,
                    atMs = 1_000_000L,
                    note = "sin red",
                ),
            ),
        )
        assertEquals(DeviceReadinessPolicy.VERDICT_READY, r.verdict)
        assertEquals(
            CheckState.INCONCLUSIVE,
            r.checks[CheckKey.CONTINUITY_TEST]?.state,
        )
    }

    @Test
    fun `17 continuity NOT_RUN queda informativo sin bloquear`() {
        val r = DeviceReadinessPolicy.evaluate(
            baseInput(
                oemGuidePresent = true,
                oemConfirmed = true,
                continuity = ContinuityOutcome(
                    state = CheckState.NOT_RUN,
                    cause = "",
                    fixesDuringOff = 0,
                    screenOffMs = 0L,
                    windowMs = 0L,
                    frozenSeconds = 0,
                    atMs = 0L,
                    note = "herramienta de diagnóstico",
                ),
            ),
        )
        assertEquals(DeviceReadinessPolicy.VERDICT_READY, r.verdict)
        assertEquals(
            CheckState.NOT_RUN,
            r.checks[CheckKey.CONTINUITY_TEST]?.state,
        )
    }
}
