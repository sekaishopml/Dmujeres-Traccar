package com.dmujeres.traccar.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1/§20: políticas OTA puras (versión, URL, instalación segura). */
class OtaPolicyTest {

    // ── OtaVersionPolicy ──

    @Test
    fun versionCodeEsAutoridad() {
        assertEquals(
            OtaVersionPolicy.Decision.UPDATE_AVAILABLE,
            OtaVersionPolicy.decide(115, 116, "1.1.6", "1.1.5"),
        )
        assertEquals(
            OtaVersionPolicy.Decision.UP_TO_DATE,
            OtaVersionPolicy.decide(116, 115, "1.0.9", "1.1.6"), // nunca downgrade
        )
        assertEquals(
            OtaVersionPolicy.Decision.UP_TO_DATE,
            OtaVersionPolicy.decide(115, 115, "1.1.5", "1.1.5"),
        )
    }

    @Test
    fun sinVersionCodeUsaNombreSinDowngrade() {
        assertEquals(
            OtaVersionPolicy.Decision.UPDATE_AVAILABLE,
            OtaVersionPolicy.decide(9, null, "1.0.17", "1.0.9"),
        )
        assertEquals(
            OtaVersionPolicy.Decision.UP_TO_DATE,
            OtaVersionPolicy.decide(17, null, "1.0.9", "1.0.17"),
        )
        assertEquals(
            OtaVersionPolicy.Decision.INVALID_METADATA,
            OtaVersionPolicy.decide(115, null, null, "1.1.5"),
        )
    }

    @Test
    fun minVersionCodeMarcaObligatoria() {
        assertTrue(OtaVersionPolicy.isMandatory(110, 112))
        assertFalse(OtaVersionPolicy.isMandatory(112, 112))
        assertFalse(OtaVersionPolicy.isMandatory(112, null))
        // Instalado por debajo del mínimo: se ofrece actualización aunque el
        // nombre no compare (caso degradado).
        assertEquals(
            OtaVersionPolicy.Decision.UPDATE_AVAILABLE,
            OtaVersionPolicy.decide(110, null, "1.1.5", "1.1.5", minVersionCode = 112),
        )
    }

    // ── OtaUrlPolicy ──

    @Test
    fun httpsSiemprePermitido() {
        assertTrue(OtaUrlPolicy.isAllowed("https://ota.example.com/app.apk", release = true))
        assertTrue(OtaUrlPolicy.isAllowed("https://ota.example.com/app.apk", release = false))
    }

    @Test
    fun httpEnReleaseSoloHostLegado() {
        assertTrue(OtaUrlPolicy.isAllowed("http://68.168.20.219:999/app.apk", release = true))
        assertFalse(OtaUrlPolicy.isAllowed("http://otro-host.com/app.apk", release = true))
        assertFalse(OtaUrlPolicy.isAllowed("http://10.0.0.5:999/app.apk", release = true))
    }

    @Test
    fun httpEnDebugPermitidoParaDesarrollo() {
        assertTrue(OtaUrlPolicy.isAllowed("http://10.0.2.2:999/app.apk", release = false))
        assertTrue(OtaUrlPolicy.isAllowed("http://localhost:999/app.apk", release = false))
    }

    @Test
    fun urlsInvalidasRechazadas() {
        assertFalse(OtaUrlPolicy.isAllowed(null, release = false))
        assertFalse(OtaUrlPolicy.isAllowed("", release = false))
        assertFalse(OtaUrlPolicy.isAllowed("ftp://host/app.apk", release = false))
        assertFalse(OtaUrlPolicy.isAllowed("no-es-url", release = false))
    }

    // ── OtaInstallPolicy ──

    @Test
    fun instalacionSePostergaConRecoveryOJornadaRecienIniciada() {
        assertEquals(
            OtaInstallPolicy.Decision.POSTPONE,
            OtaInstallPolicy.canInstallNow(recoveryActive = true, journeyJustStarted = false),
        )
        assertEquals(
            OtaInstallPolicy.Decision.POSTPONE,
            OtaInstallPolicy.canInstallNow(recoveryActive = false, journeyJustStarted = true),
        )
        assertEquals(
            OtaInstallPolicy.Decision.INSTALL_NOW,
            OtaInstallPolicy.canInstallNow(recoveryActive = false, journeyJustStarted = false),
        )
    }

    @Test
    fun ventanaDeJornadaRecienIniciada() {
        val now = 1_000_000L
        assertTrue(OtaInstallPolicy.journeyJustStarted(now - 30_000, now))
        assertFalse(OtaInstallPolicy.journeyJustStarted(now - 10 * 60_000, now))
        assertFalse(OtaInstallPolicy.journeyJustStarted(0L, now))
    }
}
