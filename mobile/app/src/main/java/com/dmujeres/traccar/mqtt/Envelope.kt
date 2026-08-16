package com.dmujeres.traccar.mqtt

import org.json.JSONObject
import java.time.Instant
import java.util.Locale

/**
 * Envelope v1 del contrato (docs/mqtt/protocol-v1.md). El orden de campos es fijo porque
 * el servidor deduplica con un hash canónico por campos, no por los bytes del transporte.
 */
object Envelope {

    fun nowIso(): String = Instant.now().toString()

    fun buildPosition(
        messageId: String,
        deviceId: String,
        sequence: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        speed: Double,
        bearing: Double,
        altitude: Double,
        observedAt: String,
        pending: Int,
        battery: Int,
        network: String
    ): String {
        val payload = JSONObject()
        payload.put("latitude", latitude)
        payload.put("longitude", longitude)
        payload.put("accuracy", accuracy)
        payload.put("speed", speed)
        payload.put("bearing", bearing)
        payload.put("altitude", altitude)
        payload.put("pending", pending)
        payload.put("battery", battery)
        payload.put("network", network)

        val body = JSONObject()
        body.put("schema", 1)
        body.put("type", "position")
        body.put("messageId", messageId)
        body.put("deviceId", deviceId)
        body.put("sequence", sequence)
        body.put("sentAt", nowIso())
        body.put("observedAt", observedAt)
        body.put("payload", payload)
        return body.toString()
    }

    /** Genera un messageId único por posición (no cambia en reintentos: se guarda en Room). */
    /** Heartbeat de presencia con telemetría (parking interior / sin fix de GPS). */
    fun buildPresence(
        messageId: String,
        deviceId: String,
        sequence: Long,
        pending: Int,
        battery: Int,
        network: String,
        vendor: String,
        model: String,
        appVersion: String,
        gps: String,
        journeyStatus: String? = null
    ): String {
        val payload = JSONObject()
        payload.put("pending", pending)
        payload.put("battery", battery)
        payload.put("network", network)
        payload.put("vendor", vendor)
        payload.put("model", model)
        payload.put("appVersion", appVersion)
        payload.put("gps", gps)
        if (journeyStatus == "started") payload.put("journeyStarted", true)
        if (journeyStatus == "ended") payload.put("journeyEnded", true)
        val body = JSONObject()
        body.put("schema", 1)
        body.put("type", "presence")
        body.put("messageId", messageId)
        body.put("deviceId", deviceId)
        body.put("sequence", sequence)
        body.put("sentAt", nowIso())
        body.put("observedAt", nowIso())
        body.put("payload", payload)
        return body.toString()
    }

    fun newMessageId(deviceId: String, sequence: Long): String {
        val devicePart = Integer.toUnsignedString(deviceId.hashCode(), 16).padStart(8, '0')
        val timePart = java.lang.Long.toHexString(System.currentTimeMillis()).padStart(12, '0')
        val sequencePart = java.lang.Long.toHexString(sequence).padStart(4, '0')
        return ("01JAND" + devicePart + timePart + sequencePart).take(26)
    }
}
