package com.dmujeres.traccar.diagnostics

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

    // ==== Clases de silencio por pantalla apagada (F0) =======================
    //
    // Clasificación con EVIDENCIA, nunca por reglas adivinadas:
    // - SCREEN_OFF_TRACKING_OK: fixes durante la ventana apagada.
    // - SCREEN_OFF_NO_CALLBACK: sin callback; se mantiene como no-callback
    //   honesto hasta tener evidencia adicional (regla del master prompt:
    //   motion MOVING + FGS vivo NO declara OEM freeze automáticamente).
    // - SCREEN_OFF_PROCESS_FROZEN: SOLO con frozenSeconds > 0 (evidencia del
    //   propio proceso: ticker de 1 s con saltos de elapsedRealtime). Es
    //   SUSPECT, nunca "confirmed".
    // - SCREEN_OFF_NETWORK_DOWN: sin red durante la ventana.
    // - SCREEN_OFF_UNKNOWN: sin evidencia suficiente.

    data class ScreenOffLayers(
        val screenOff: Boolean,
        val nowMs: Long,
        val screenOffAtMs: Long,
        val lastCallbackAt: Long,
        val processAlive: Boolean,
        val fgsAlive: Boolean,
        val motionState: String, // STATIONARY | MOVING | UNKNOWN
        val networkAvailable: Boolean,
        val frozenSeconds: Int,  // evidencia in-app de congelamiento (0 = ninguna)
        val fixesDuringOff: Int,
    )

    /**
     * Clasifica el estado de tracking durante una ventana con pantalla apagada.
     * OEM_FREEZE_SUSPECT solo si existe evidencia de congelamiento medida.
     */
    fun classifyScreenOff(l: ScreenOffLayers): String {
        if (!l.screenOff) return SCREEN_OFF_UNKNOWN
        if (l.fixesDuringOff > 0) return SCREEN_OFF_TRACKING_OK
        if (!l.processAlive) return SCREEN_OFF_UNKNOWN
        if (l.frozenSeconds > 0) return SCREEN_OFF_PROCESS_FROZEN
        if (!l.fgsAlive) return SCREEN_OFF_NO_CALLBACK
        if (!l.networkAvailable) return SCREEN_OFF_NETWORK_DOWN
        // Movimiento con FGS vivo pero sin callbacks: NO se declara OEM freeze
        // automáticamente (falta evidencia); queda como no-callback honesto.
        return SCREEN_OFF_NO_CALLBACK
    }

    /** Sugerencia de causa OEM: SOLO con evidencia de congelamiento. */
    fun oemFreezeSuspect(frozenSeconds: Int, screenOff: Boolean): Boolean =
        frozenSeconds > 0 && screenOff

    const val SCREEN_OFF_TRACKING_OK = "SCREEN_OFF_TRACKING_OK"
    const val SCREEN_OFF_NO_CALLBACK = "SCREEN_OFF_NO_CALLBACK"
    const val SCREEN_OFF_PROCESS_FROZEN = "SCREEN_OFF_PROCESS_FROZEN"
    const val SCREEN_OFF_NETWORK_DOWN = "SCREEN_OFF_NETWORK_DOWN"
    const val SCREEN_OFF_UNKNOWN = "SCREEN_OFF_UNKNOWN"
    const val OEM_FREEZE_SUSPECT = "OEM_FREEZE_SUSPECT"
}
