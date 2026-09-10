package com.dmujeres.traccar.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class EnvelopeTest {

    private fun presenceJson(rttMs: Int = -1, signal: Int = -1): JSONObject =
        JSONObject(
            Envelope.buildPresence(
                messageId = "dmj-4152c2b0-000000004814-abc",
                deviceId = "worker-01",
                sequence = 1L,
                pending = 0,
                battery = 88,
                network = "wifi",
                vendor = "Google",
                model = "Pixel 8",
                appVersion = "1.0.53",
                gps = "on",
                rttMs = rttMs,
                signal = signal,
            )
        )

    @Test
    fun presenceIncludesRttAndSignalWhenKnown() {
        val payload = presenceJson(rttMs = 345, signal = 3).getJSONObject("payload")

        assertEquals(345, payload.getInt("rttMs"))
        assertEquals(3, payload.getInt("signal"))
        assertEquals("wifi", payload.getString("network"))
        assertEquals(88, payload.getInt("battery"))
    }

    @Test
    fun presenceOmitsUnknownRttAndSignal() {
        val payload = presenceJson().getJSONObject("payload")

        assertFalse(payload.has("rttMs"))
        assertFalse(payload.has("signal"))
        assertFalse(payload.has("journeyStarted"))
        assertFalse(payload.has("journeyEnded"))
        assertFalse(payload.has("netCause"))
        assertFalse(payload.has("validated"))
        assertFalse(payload.has("wifiEnabled"))
        assertFalse(payload.has("airplane"))
        assertFalse(payload.has("dataEnabled"))
        assertFalse(payload.has("simPresent"))
        assertFalse(payload.has("service"))
        assertFalse(payload.has("netConf"))
    }

    @Test
    fun presenceIncludesNetCauseWhenKnown() {
        val body = JSONObject(
            Envelope.buildPresence(
                messageId = "dmj-4152c2b0-000000004814-abc",
                deviceId = "worker-01",
                sequence = 2L,
                pending = 3,
                battery = 77,
                network = "none",
                vendor = "Google",
                model = "Pixel 8",
                appVersion = "1.0.54",
                gps = "on",
                netCause = "wifi_off_user",
                validated = false,
                wifiEnabled = false,
                airplane = false,
            )
        )
        val payload = body.getJSONObject("payload")
        assertEquals("wifi_off_user", payload.getString("netCause"))
        assertEquals(false, payload.getBoolean("validated"))
        assertEquals(false, payload.getBoolean("wifiEnabled"))
        assertEquals(false, payload.getBoolean("airplane"))
        // El campo network existente no cambia.
        assertEquals("none", payload.getString("network"))
        assertEquals(1, body.getInt("schema"))
        assertEquals("presence", body.getString("type"))
    }

    @Test
    fun presenceNetCauseAirplaneSerializes() {
        val payload = JSONObject(
            Envelope.buildPresence(
                messageId = "m", deviceId = "d", sequence = 3L,
                pending = 0, battery = 50, network = "none",
                vendor = "v", model = "m", appVersion = "1.0.54", gps = "on",
                netCause = "airplane", validated = false,
                wifiEnabled = true, airplane = true,
            )
        ).getJSONObject("payload")
        assertEquals("airplane", payload.getString("netCause"))
        assertEquals(true, payload.getBoolean("airplane"))
    }

    @Test
    fun presenceIncludesTelephonyCertaintyWhenKnown() {
        val body = JSONObject(
            Envelope.buildPresence(
                messageId = "m", deviceId = "d", sequence = 4L,
                pending = 0, battery = 50, network = "none",
                vendor = "v", model = "m", appVersion = "1.0.55", gps = "on",
                netCause = "mobile_data_off_user", validated = false,
                wifiEnabled = true, airplane = false,
                dataEnabled = false, simPresent = true,
                service = "in_service", netConf = "confirmed",
            )
        )
        val payload = body.getJSONObject("payload")
        assertEquals(false, payload.getBoolean("dataEnabled"))
        assertEquals(true, payload.getBoolean("simPresent"))
        assertEquals("in_service", payload.getString("service"))
        assertEquals("confirmed", payload.getString("netConf"))
        assertEquals("mobile_data_off_user", payload.getString("netCause"))
        assertEquals(1, body.getInt("schema"))
        assertEquals("presence", body.getString("type"))
    }

    @Test
    fun presenceIncludesSuspectedConfWithoutTelephony() {
        // Sin permiso READ_PHONE_STATE: telefonía omitida, pero netConf=suspected sí viaja.
        val payload = JSONObject(
            Envelope.buildPresence(
                messageId = "m", deviceId = "d", sequence = 5L,
                pending = 1, battery = 60, network = "none",
                vendor = "v", model = "m", appVersion = "1.0.55", gps = "on",
                netCause = "no_coverage_suspected", validated = false,
                wifiEnabled = true, airplane = false,
                netConf = "suspected",
            )
        ).getJSONObject("payload")
        assertEquals("suspected", payload.getString("netConf"))
        assertFalse(payload.has("dataEnabled"))
        assertFalse(payload.has("simPresent"))
        assertFalse(payload.has("service"))
    }

    @Test
    fun presenceIncludesSimMissing() {
        val payload = JSONObject(
            Envelope.buildPresence(
                messageId = "m", deviceId = "d", sequence = 6L,
                pending = 0, battery = 70, network = "none",
                vendor = "v", model = "m", appVersion = "1.0.55", gps = "on",
                netCause = "sim_missing", validated = false,
                wifiEnabled = true, airplane = false,
                dataEnabled = true, simPresent = false,
                service = "unknown", netConf = "confirmed",
            )
        ).getJSONObject("payload")
        assertEquals("sim_missing", payload.getString("netCause"))
        assertEquals(false, payload.getBoolean("simPresent"))
        assertEquals("confirmed", payload.getString("netConf"))
    }

    @Test
    fun positionOmitsLowQualityWhenGood() {
        val body = JSONObject(
            Envelope.buildPosition(
                messageId = "m", deviceId = "d", sequence = 1L,
                latitude = 19.4326, longitude = -99.1332, accuracy = 10.0,
                speed = 0.0, bearing = 0.0, altitude = 0.0,
                observedAt = "2026-01-01T00:00:00Z", pending = 0, battery = 80,
                network = "wifi", lowQuality = false,
            )
        )
        assertFalse(body.getJSONObject("payload").has("lowQuality"))
    }

    @Test
    fun positionMarksLowQualityWhenDegraded() {
        val body = JSONObject(
            Envelope.buildPosition(
                messageId = "m", deviceId = "d", sequence = 2L,
                latitude = 19.4326, longitude = -99.1332, accuracy = 120.0,
                speed = 0.0, bearing = 0.0, altitude = 0.0,
                observedAt = "2026-01-01T00:00:00Z", pending = 0, battery = 80,
                network = "wifi", lowQuality = true,
            )
        )
        assertTrue(body.getJSONObject("payload").getBoolean("lowQuality"))
    }

    @Test
    fun positionCarriesProviderAndFixAge() {
        val body = JSONObject(
            Envelope.buildPosition(
                messageId = "m", deviceId = "d", sequence = 3L,
                latitude = 19.4326, longitude = -99.1332, accuracy = 10.0,
                speed = 0.0, bearing = 0.0, altitude = 0.0,
                observedAt = "2026-01-01T00:00:00Z", pending = 0, battery = 80,
                network = "wifi", provider = "network", fixAgeSec = 42L,
            )
        )
        val payload = body.getJSONObject("payload")
        assertEquals("network", payload.getString("provider"))
        assertEquals(42L, payload.getLong("fixAgeSec"))
    }

    @Test
    fun positionOmitsProviderAndFixAgeWhenAbsent() {
        val body = JSONObject(
            Envelope.buildPosition(
                messageId = "m", deviceId = "d", sequence = 4L,
                latitude = 19.4326, longitude = -99.1332, accuracy = 10.0,
                speed = 0.0, bearing = 0.0, altitude = 0.0,
                observedAt = "2026-01-01T00:00:00Z", pending = 0, battery = 80,
                network = "wifi", provider = null, fixAgeSec = 0L,
            )
        )
        val payload = body.getJSONObject("payload")
        assertFalse(payload.has("provider"))
        assertFalse(payload.has("fixAgeSec"))
    }

    @Test
    fun messageIdKeepsSequenceAndDoesNotCollide() {
        val first = Envelope.newMessageId("worker-01", 18452)
        val second = Envelope.newMessageId("worker-01", 18453)

        assertNotEquals(first, second)
        assertTrue(first.contains("4152c2b0"))
        assertTrue(first.contains("000000004814"))
        assertTrue(second.contains("000000004815"))
        assertTrue(first.length <= 64)
    }

    @Test
    fun messageIdsRemainUniqueAcrossAQueueBatch() {
        val ids = (1L..10_000L).map { Envelope.newMessageId("worker-01", it) }

        assertTrue(ids.toSet().size == ids.size)
        assertTrue(ids.all { it.length in 16..64 })
    }
}
