package com.dmujeres.traccar.mqtt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Observabilidad en presence (punto 3): contadores fixReceived/fixRejected/
 * fixEnqueued + permFine/permBackground/gpsEnabled para que el servidor
 * distinga "GPS apagado" de "filtro mata todo".
 */
class PresenceObservabilityTest {

    @Test
    fun presenceIncludesFixCountersAndPermissions() {
        val body = JSONObject(
            Envelope.buildPresence(
                messageId = "m", deviceId = "d", sequence = 1L,
                pending = 0, battery = 80, network = "wifi",
                vendor = "v", model = "m", appVersion = "1.0.58", gps = "on",
                fixReceived = 100L, fixRejected = 90L, fixEnqueued = 10L,
                permFine = true, permBackground = true, gpsEnabled = true,
            ),
        )
        val payload = body.getJSONObject("payload")
        assertEquals(100L, payload.getLong("fixReceived"))
        assertEquals(90L, payload.getLong("fixRejected"))
        assertEquals(10L, payload.getLong("fixEnqueued"))
        assertEquals(true, payload.getBoolean("permFine"))
        assertEquals(true, payload.getBoolean("permBackground"))
        assertEquals(true, payload.getBoolean("gpsEnabled"))
    }

    @Test
    fun presenceDistinguishesGpsOffFromFilterKillsAll() {
        // GPS apagado: gps=off + gpsEnabled=false, cero recibidos.
        val gpsOff = JSONObject(
            Envelope.buildPresence(
                messageId = "m", deviceId = "d", sequence = 2L,
                pending = 0, battery = 80, network = "wifi",
                vendor = "v", model = "m", appVersion = "1.0.58", gps = "off",
                fixReceived = 0L, fixRejected = 0L, fixEnqueued = 0L,
                permFine = true, permBackground = true, gpsEnabled = false,
            ),
        ).getJSONObject("payload")
        assertEquals("off", gpsOff.getString("gps"))
        assertEquals(false, gpsOff.getBoolean("gpsEnabled"))

        // Filtro mata todo: GPS on pero recibidos>0, rechazados==recibidos, encolados==0.
        val filterKills = JSONObject(
            Envelope.buildPresence(
                messageId = "m", deviceId = "d", sequence = 3L,
                pending = 0, battery = 80, network = "wifi",
                vendor = "v", model = "m", appVersion = "1.0.58", gps = "on",
                fixReceived = 50L, fixRejected = 50L, fixEnqueued = 0L,
                permFine = true, permBackground = true, gpsEnabled = true,
            ),
        ).getJSONObject("payload")
        assertEquals("on", filterKills.getString("gps"))
        assertEquals(true, filterKills.getBoolean("gpsEnabled"))
        assertEquals(50L, filterKills.getLong("fixReceived"))
        assertEquals(50L, filterKills.getLong("fixRejected"))
        assertEquals(0L, filterKills.getLong("fixEnqueued"))
    }

    @Test
    fun presenceOmitsObservabilityWhenUnknown() {
        // Compatibilidad: sin los nuevos campos no se serializan (schema:1 tolera extras).
        val payload = JSONObject(
            Envelope.buildPresence(
                messageId = "m", deviceId = "d", sequence = 4L,
                pending = 0, battery = 80, network = "wifi",
                vendor = "v", model = "m", appVersion = "1.0.58", gps = "on",
            ),
        ).getJSONObject("payload")
        assertFalse(payload.has("fixReceived"))
        assertFalse(payload.has("fixRejected"))
        assertFalse(payload.has("fixEnqueued"))
        assertFalse(payload.has("permFine"))
        assertFalse(payload.has("permBackground"))
        assertFalse(payload.has("gpsEnabled"))
    }

    @Test
    fun presenceReportsMissingPermissions() {
        val payload = JSONObject(
            Envelope.buildPresence(
                messageId = "m", deviceId = "d", sequence = 5L,
                pending = 0, battery = 80, network = "none",
                vendor = "v", model = "m", appVersion = "1.0.58", gps = "off",
                fixReceived = 0L, fixRejected = 0L, fixEnqueued = 0L,
                permFine = false, permBackground = false, gpsEnabled = false,
            ),
        ).getJSONObject("payload")
        assertEquals(false, payload.getBoolean("permFine"))
        assertEquals(false, payload.getBoolean("permBackground"))
        assertEquals(false, payload.getBoolean("gpsEnabled"))
    }

    @Test
    fun zeroCountersAreStillSerialized() {
        // 0 no es "desconocido": debe viajar para distinguir "cero capturas" de "sin dato".
        val payload = JSONObject(
            Envelope.buildPresence(
                messageId = "m", deviceId = "d", sequence = 6L,
                pending = 0, battery = 80, network = "wifi",
                vendor = "v", model = "m", appVersion = "1.0.58", gps = "on",
                fixReceived = 0L, fixRejected = 0L, fixEnqueued = 0L,
                permFine = true, permBackground = true, gpsEnabled = true,
            ),
        ).getJSONObject("payload")
        assertTrue(payload.has("fixReceived"))
        assertTrue(payload.has("fixRejected"))
        assertTrue(payload.has("fixEnqueued"))
        assertEquals(0L, payload.getLong("fixReceived"))
    }
}
