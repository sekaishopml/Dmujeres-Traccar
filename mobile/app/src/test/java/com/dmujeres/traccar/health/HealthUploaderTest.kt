package com.dmujeres.traccar.health

import com.dmujeres.traccar.data.HealthSnapshot
import com.dmujeres.traccar.oem.DeviceCapabilityProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * FASE 7: contrato del cuerpo de salud móvil — sin coordenadas, sin PII, sin
 * tokens; y campos que el servidor exige (wallMs/eventType/healthState).
 */
class HealthUploaderTest {

    private fun profile() = DeviceCapabilityProfile(
        manufacturer = "ZTE",
        model = "Z2450",
        device = "ZTE-Z2450",
        androidVersion = "14",
        sdk = 34,
        rom = "MyOS 14",
        hasGnss = true,
        hasAccelerometer = true,
        hasGyroscope = false,
        hasRotationVector = false,
        batteryOptimizationExempt = false,
        standbyBucket = DeviceCapabilityProfile.STANDBY_RARE,
        backgroundRestricted = false,
        supportsAutostartGuide = true,
        supportsBatteryGuide = true,
        supportsVendorSettings = true,
        knownBackgroundRestriction = true,
        knownFreezerBehavior = true,
    )

    private fun snapshot() = HealthSnapshot(
        id = 7,
        sessionId = "sess-1",
        deviceId = "qa-f0",
        wallMs = 1_700_000_000_000L,
        wallBucket = 1_700_000_000_000L / 60_000L,
        elapsedMs = 12_345L,
        eventType = "HEARTBEAT",
        reason = "",
        fgs = true,
        motion = "STATIONARY",
        network = "wifi",
        outbox = 2,
        healthState = "LIVE",
    )

    @Test
    fun `cuerpo incluye perfil y snapshots con contrato exacto`() {
        val body = JSONObject(HealthUploader.buildBody(listOf(snapshot()), profile(), "1.1.2"))
        val device = body.getJSONObject("device")
        assertEquals("ZTE", device.getString("manufacturer"))
        assertEquals("34", device.getString("androidSdk"))
        assertEquals("DEGRADED", device.getString("capabilityStatus"))
        val item = body.getJSONArray("snapshots").getJSONObject(0)
        assertEquals("sess-1", item.getString("sessionId"))
        assertEquals("HEARTBEAT", item.getString("eventType"))
        assertEquals("LIVE", item.getString("healthState"))
        assertEquals(2, item.getInt("outbox"))
    }

    @Test
    fun `sin coordenadas ni PII`() {
        val raw = HealthUploader.buildBody(listOf(snapshot()), profile(), "1.1.2")
        assertFalse(raw.contains("lat"))
        assertFalse(raw.contains("lon"))
        assertFalse(raw.contains("fcmToken"))
        assertFalse(raw.contains("apiKey"))
        assertFalse(raw.contains("password"))
    }

    @Test
    fun `perfil sin GNSS se declara UNSUPPORTED`() {
        val body = JSONObject(
            HealthUploader.buildBody(emptyList(), profile().copy(hasGnss = false), "1.1.2"),
        )
        assertEquals("UNSUPPORTED", body.getJSONObject("device").getString("capabilityStatus"))
    }

    @Test
    fun `restriccion dura se declara DEGRADED`() {
        val body = JSONObject(
            HealthUploader.buildBody(
                emptyList(),
                profile().copy(backgroundRestricted = true, knownBackgroundRestriction = false),
                "1.1.2",
            ),
        )
        assertEquals("DEGRADED", body.getJSONObject("device").getString("capabilityStatus"))
    }
}
