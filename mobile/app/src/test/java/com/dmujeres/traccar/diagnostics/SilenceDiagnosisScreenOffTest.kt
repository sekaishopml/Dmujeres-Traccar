package com.dmujeres.traccar.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de clasificación de silencio con pantalla apagada: la regla del
 * master prompt exige EVIDENCIA — motion MOVING + FGS vivo sin callbacks NO
 * declara OEM freeze; frozenSeconds medido SÍ da OEM_FREEZE_SUSPECT.
 */
class SilenceDiagnosisScreenOffTest {

    private fun base(now: Long = 1_000_000L) = SilenceDiagnosis.ScreenOffLayers(
        screenOff = true,
        nowMs = now,
        screenOffAtMs = now - 90_000L,
        lastCallbackAt = now - 5 * 60_000L,
        processAlive = true,
        fgsAlive = true,
        motionState = "MOVING",
        networkAvailable = true,
        frozenSeconds = 0,
        fixesDuringOff = 0,
    )

    @Test
    fun fixesDuranteApagadoEsTrackingOk() {
        val r = SilenceDiagnosis.classifyScreenOff(base().copy(fixesDuringOff = 3))
        assertEquals(SilenceDiagnosis.SCREEN_OFF_TRACKING_OK, r)
    }

    @Test
    fun sinCallbackConFgsYProcesoVivosEsNoCallback() {
        // Movimiento + FGS vivo + sin callbacks: NO declara OEM_FREEZE.
        val r = SilenceDiagnosis.classifyScreenOff(base())
        assertEquals(SilenceDiagnosis.SCREEN_OFF_NO_CALLBACK, r)
    }

    @Test
    fun procesoMuertoEsUnknownHastaEvidencia() {
        val r = SilenceDiagnosis.classifyScreenOff(base().copy(processAlive = false))
        assertEquals(SilenceDiagnosis.SCREEN_OFF_UNKNOWN, r)
    }

    @Test
    fun freezeMedidoDaProcessFrozen() {
        val r = SilenceDiagnosis.classifyScreenOff(base().copy(frozenSeconds = 41))
        assertEquals(SilenceDiagnosis.SCREEN_OFF_PROCESS_FROZEN, r)
    }

    @Test
    fun sinRedDuranteApagadoEsNetworkDown() {
        val r = SilenceDiagnosis.classifyScreenOff(base().copy(networkAvailable = false))
        assertEquals(SilenceDiagnosis.SCREEN_OFF_NETWORK_DOWN, r)
    }

    @Test
    fun pantallaEncendidaEsUnknown() {
        val r = SilenceDiagnosis.classifyScreenOff(base().copy(screenOff = false))
        assertEquals(SilenceDiagnosis.SCREEN_OFF_UNKNOWN, r)
    }

    @Test
    fun oemFreezeSuspectSoloConFreezeMedido() {
        assertTrue(SilenceDiagnosis.oemFreezeSuspect(frozenSeconds = 41, screenOff = true))
        assertFalse(SilenceDiagnosis.oemFreezeSuspect(frozenSeconds = 0, screenOff = true))
        assertFalse(SilenceDiagnosis.oemFreezeSuspect(frozenSeconds = 41, screenOff = false))
    }
}
