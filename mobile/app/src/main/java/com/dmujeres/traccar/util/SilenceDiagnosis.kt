package com.dmujeres.traccar.util

/**
 * Diagnóstico de silencio POR CAPA (Fase 9, puro sin Android): dado el estado
 * de los relojes del pipeline (los mismos que lee la fila "pipeline" de
 * DiagnosticsActivity), devuelve la causa prioritaria del silencio.
 *
 * Reglas en orden de prioridad (primera que aplica gana):
 * A) !journeyActive                        → "DETENIDO"
 * B) callbackAt viejo (> 10 min)           → "GPS sin callbacks"
 * C) fixRejected creció con callback fresco → "GPS con rechazos"
 * D) acceptedAt fresco && ackAt > 3 min && httpAt > 2 min → "Sin confirmación (red/servidor)"
 * E) ackAt fresco                          → "OK (captura y envío)"
 * F) mqttAt viejo && httpAt fresco         → "MQTT caído (posiciones OK)"
 * G) else (callback fresco && ackAt viejo) → "Sin red: capturando offline"
 *
 * "Fresco" = timestamp dentro de su ventana [FRESH_*_MS]; 0 = nunca (viejo).
 */
object SilenceDiagnosis {

    const val FRESH_CALLBACK_MS = 10 * 60_000L
    const val FRESH_ACCEPTED_MS = 10 * 60_000L
    const val FRESH_ACK_MS = 3 * 60_000L
    const val FRESH_HTTP_MS = 2 * 60_000L
    const val FRESH_MQTT_MS = 15 * 60_000L

    data class SilenceLayers(
        val callbackAt: Long,
        val acceptedAt: Long,
        val storedAt: Long,
        val ackAt: Long,
        val httpAt: Long,
        val mqttAt: Long,
        val fixReceived: Long,
        val fixRejected: Long,
        val journeyActive: Boolean,
        val nowMs: Long,
    )

    fun diagnose(l: SilenceLayers): String {
        if (!l.journeyActive) return "DETENIDO"
        val callbackFresh = fresh(l.callbackAt, FRESH_CALLBACK_MS, l.nowMs)
        val acceptedFresh = fresh(l.acceptedAt, FRESH_ACCEPTED_MS, l.nowMs)
        val ackFresh = fresh(l.ackAt, FRESH_ACK_MS, l.nowMs)
        val httpFresh = fresh(l.httpAt, FRESH_HTTP_MS, l.nowMs)
        val mqttFresh = fresh(l.mqttAt, FRESH_MQTT_MS, l.nowMs)
        val rejectedRecent = l.fixRejected > 0L && callbackFresh && !acceptedFresh
        return when {
            !callbackFresh -> "GPS sin callbacks"
            rejectedRecent -> "GPS con rechazos"
            acceptedFresh && !ackFresh && !httpFresh -> "Sin confirmación (red/servidor)"
            ackFresh -> "OK (captura y envío)"
            !mqttFresh && httpFresh -> "MQTT caído (posiciones OK)"
            else -> "Sin red: capturando offline"
        }
    }

    private fun fresh(at: Long, windowMs: Long, nowMs: Long): Boolean =
        at > 0L && nowMs - at < windowMs
}
