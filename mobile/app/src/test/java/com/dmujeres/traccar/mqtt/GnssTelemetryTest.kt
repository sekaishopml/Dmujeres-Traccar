package com.dmujeres.traccar.mqtt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telemetría de adquisición activa en presence: `gnssUsed`/`gnssTotal`/
 * `pollActive` son opcionales (schema:1 tolera extras) y solo viajan si
 * conocidos (`pollActive` solo en true para no engordar cada heartbeat).
 */
class GnssTelemetryTest {

    private fun presence(
        gnssUsed: Int? = null,
        gnssTotal: Int? = null,
        pollActive: Boolean? = null,
    ): JSONObject = JSONObject(
        Envelope.buildPresence(
            messageId = "m", deviceId = "d", sequence = 1L,
            pending = 0, battery = 80, network = "wifi",
            vendor = "v", model = "m", appVersion = "1.0.59", gps = "on",
            gnssUsed = gnssUsed, gnssTotal = gnssTotal, pollActive = pollActive,
        ),
    ).getJSONObject("payload")

    @Test
    fun presenceIncludesGnssWhenKnown() {
        val payload = presence(gnssUsed = 2, gnssTotal = 5)
        assertEquals(2, payload.getInt("gnssUsed"))
        assertEquals(5, payload.getInt("gnssTotal"))
    }

    @Test
    fun presenceIncludesPollActiveWhenFiring() {
        val payload = presence(pollActive = true)
        assertTrue(payload.getBoolean("pollActive"))
    }

    @Test
    fun presenceOmitsGnssAndPollWhenUnknown() {
        val payload = presence()
        assertFalse(payload.has("gnssUsed"))
        assertFalse(payload.has("gnssTotal"))
        assertFalse(payload.has("pollActive"))
    }

    @Test
    fun presenceOmitsPollActiveWhenFalse() {
        // false también se omite: el polling quieto no aporta señal.
        val payload = presence(pollActive = false)
        assertFalse(payload.has("pollActive"))
    }

    @Test
    fun presenceZeroSatellitesStillSerialized() {
        // 0 en vista es dato (bajo techo), no "desconocido".
        val payload = presence(gnssUsed = 0, gnssTotal = 0)
        assertTrue(payload.has("gnssUsed"))
        assertTrue(payload.has("gnssTotal"))
        assertEquals(0, payload.getInt("gnssUsed"))
        assertEquals(0, payload.getInt("gnssTotal"))
    }
}
