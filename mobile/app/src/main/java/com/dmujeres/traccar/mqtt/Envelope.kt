package com.dmujeres.traccar.mqtt

import org.json.JSONObject
import java.time.Instant
import java.util.UUID

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
        network: String,
        // Marca de calidad anti-drift: true cuando accuracy en [80, 500).
        // Se omite si es false para no crecer el payload de fixes buenos.
        lowQuality: Boolean = false,
        // Origen del fix: "gps" | "network" | "fused" | "unknown". El server marca
        // attributes.provider y el panel excluye "network" de la geometría de ruta.
        provider: String? = null,
        // Segundos desde que se generó el fix hasta el enqueue (0 = fresco).
        fixAgeSec: Long? = null,
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
        if (lowQuality) payload.put("lowQuality", true)
        if (!provider.isNullOrBlank()) payload.put("provider", provider)
        if (fixAgeSec != null && fixAgeSec > 0L) payload.put("fixAgeSec", fixAgeSec)

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
        journeyStatus: String? = null,
        journeyId: Long = 0L,
        rttMs: Int = -1,
        signal: Int = -1,
        // Causa de pérdida de red (Fase 1): opcionales, solo se serializan si conocidos.
        // schema:1 tolera extras; el campo `network` existente no cambia.
        netCause: String? = null,
        validated: Boolean? = null,
        wifiEnabled: Boolean? = null,
        airplane: Boolean? = null,
        // Certeza anti-trampas con READ_PHONE_STATE (opcional): solo si conocidos.
        dataEnabled: Boolean? = null,
        simPresent: Boolean? = null,
        service: String? = null,
        netConf: String? = null,
        // Observabilidad anti "cero capturas en silencio" + permisos/GPS para que
        // el servidor distinga "GPS apagado" de "filtro mata todo". Opcionales
        // (solo se serializan si conocidos) para no romper schema:1.
        fixReceived: Long? = null,
        fixRejected: Long? = null,
        fixEnqueued: Long? = null,
        permFine: Boolean? = null,
        permBackground: Boolean? = null,
        gpsEnabled: Boolean? = null,
        // Adquisición GPS activa (opcionales, solo se serializan si conocidos):
        // satélites GNSS en vista/usados en fix + si el polling one-shot está
        // disparando. `pollActive` solo viaja en true para no engordar presence.
        gnssUsed: Int? = null,
        gnssTotal: Int? = null,
        pollActive: Boolean? = null,
    ): String {
        val payload = JSONObject()
        payload.put("pending", pending)
        payload.put("battery", battery)
        payload.put("network", network)
        payload.put("vendor", vendor)
        payload.put("model", model)
        payload.put("appVersion", appVersion)
        payload.put("gps", gps)
        // Calidad de conexión (opcionales; el server ignora campos extra en payload).
        if (rttMs >= 0) payload.put("rttMs", rttMs)
        if (signal >= 0) payload.put("signal", signal)
        if (!netCause.isNullOrBlank()) payload.put("netCause", netCause)
        if (validated != null) payload.put("validated", validated)
        if (wifiEnabled != null) payload.put("wifiEnabled", wifiEnabled)
        if (airplane != null) payload.put("airplane", airplane)
        if (dataEnabled != null) payload.put("dataEnabled", dataEnabled)
        if (simPresent != null) payload.put("simPresent", simPresent)
        if (!service.isNullOrBlank()) payload.put("service", service)
        if (!netConf.isNullOrBlank()) payload.put("netConf", netConf)
        if (fixReceived != null) payload.put("fixReceived", fixReceived)
        if (fixRejected != null) payload.put("fixRejected", fixRejected)
        if (fixEnqueued != null) payload.put("fixEnqueued", fixEnqueued)
        if (permFine != null) payload.put("permFine", permFine)
        if (permBackground != null) payload.put("permBackground", permBackground)
        if (gpsEnabled != null) payload.put("gpsEnabled", gpsEnabled)
        if (gnssUsed != null) payload.put("gnssUsed", gnssUsed)
        if (gnssTotal != null) payload.put("gnssTotal", gnssTotal)
        if (pollActive == true) payload.put("pollActive", true)
        if (journeyStatus == "started") payload.put("journeyStarted", true)
        if (journeyStatus == "ended") payload.put("journeyEnded", true)
        if (journeyId > 0L) payload.put("journeyId", journeyId)
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

    /** ID estable en Room; la parte aleatoria evita colisiones entre dispositivos con el mismo hash. */
    fun newMessageId(deviceId: String, sequence: Long): String {
        val devicePart = Integer.toUnsignedString(deviceId.hashCode(), 16).padStart(8, '0')
        val sequencePart = java.lang.Long.toHexString(sequence).padStart(12, '0')
        val randomPart = UUID.randomUUID().toString().replace("-", "")
        return "dmj-$devicePart-$sequencePart-$randomPart"
    }
}
