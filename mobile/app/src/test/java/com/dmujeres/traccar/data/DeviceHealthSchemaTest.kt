package com.dmujeres.traccar.data

import com.dmujeres.traccar.diagnostics.DiagnosticsCollector
import com.dmujeres.traccar.diagnostics.DiagnosticsSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del esquema deviceHealth (F0): whitelist EXACTA — sin coordenadas,
 * sin PII, sin tokens. El snapshot debe contener SOLO device compatibility.
 */
class DeviceHealthSchemaTest {

    private fun sources() = DiagnosticsSources(
        versionCode = 110,
        versionName = "1.1.0",
        journeyActive = true,
        journeyElapsedMs = 600_000L,
        journeyStartAt = 1_000L,
        pendingCount = 2,
        bufferMax = 5000,
        bufferPolicy = "drop-oldest",
        mqttStatus = "connected",
        lastAckAt = 1_100L,
        lastFixAt = 1_200L,
        reconnects24h = 0,
        netCause = "ok",
        cellular = true,
        airplane = false,
        battery = 80,
        batteryExempt = true,
        idleMs = 0,
        crashes24h = 0,
        anrs24h = 0,
        stuckStops24h = 0,
        clockSteps24h = 0,
        speedStuck24h = 0,
        lastStartError = "",
        manufacturer = "ZTE",
        model = "Z2450",
        androidVersion = "14",
        screenOn = true,
        motionState = "STATIONARY",
        oemKey = "zte",
        oemConfirmed = true,
        readinessVerdict = "READY",
        continuityState = "PASS",
        continuityCause = "",
        recoveryState = "ok",
    )

    @Test
    fun deviceHealthWhitelistExacta() {
        val report = DiagnosticsCollector.compact(sources())
        val device = report["deviceHealth"] as Map<*, *>
        // La whitelist es exacta PERO las claves en blanco se omiten
        // (nonBlank): sin continuityCause la clave no viaja.
        assertEquals(
            setOf(
                "manufacturer", "model", "androidVersion", "screenOn", "motion",
                "oem", "oemConfirmed", "readiness", "continuity", "recovery",
            ),
            device.keys,
        )
    }

    @Test
    fun sinCoordenadasNiPiiNiSecretos() {
        val json = DiagnosticsCollector.toJson(DiagnosticsCollector.compact(sources()))
        // whitelist negativa: nunca estas claves
        assertFalse(json.contains("latitude"))
        assertFalse(json.contains("longitude"))
        assertFalse(json.contains("password"))
        assertFalse(json.contains("token"))
        assertFalse(json.contains("credentials"))
    }

    @Test
    fun camposDeCompatibilidadPresentes() {
        val report = DiagnosticsCollector.compact(sources())
        val device = report["deviceHealth"] as Map<*, *>
        assertEquals("ZTE", device["manufacturer"])
        assertEquals("Z2450", device["model"])
        assertEquals(true, device["oemConfirmed"])
        assertEquals("READY", device["readiness"])
        assertEquals("STATIONARY", device["motion"])
    }

    @Test
    fun valoresEnBlancoSeOmiten() {
        val s = sources().copy(manufacturer = "", oemKey = null)
        val device = DiagnosticsCollector.compact(s)["deviceHealth"] as Map<*, *>
        assertFalse(device.containsKey("manufacturer"))
        assertFalse(device.containsKey("oem"))
    }
}
