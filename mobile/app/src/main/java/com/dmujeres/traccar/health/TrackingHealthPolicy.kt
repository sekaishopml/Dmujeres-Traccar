package com.dmujeres.traccar.health

/**
 * Política pura (JVM) del Tracking Health Engine: consolida la información ya
 * colectada (DiagnosticsCollector/SilenceDiagnosis/MotionSensor/presencia) en
 * un único estado de salud con significado operativo.
 *
 * Separaciones obligatorias (no mezclar):
 * - "no tengo GPS" ≠ "la aplicación murió" ≠ "la red está caída" ≠ "el
 *   servidor no recibe".
 *
 * Estados:
 * - LIVE       : captura y envío al día.
 * - DEGRADED   : algo del pipeline se retrasa (sin ACK, captura vieja) pero
 *                el proceso está vivo y emitiendo evidencia.
 * - SILENT     : sin evidencia reciente del proceso (ni heartbeat ni callback
 *                ni fix) — NO implica proceso muerto: puede ser Doze/freezer.
 * - RECOVERY   : recuperación en marcha (intento del guardián pendiente o
 *                prueba de continuidad corriendo).
 * - OFFLINE    : jornada terminada o tracking deshabilitado por el usuario.
 * - UNKNOWN    : sin datos suficientes para clasificar.
 *
 * La decisión usa el MISMO reloj honesto que SilenceDiagnosis: frescura por
 * capas, no ausencia arbitraria de posiciones.
 */
object TrackingHealthPolicy {

    const val STATE_LIVE = "LIVE"
    const val STATE_DEGRADED = "DEGRADED"
    const val STATE_SILENT = "SILENT"
    const val STATE_RECOVERY = "RECOVERY"
    const val STATE_OFFLINE = "OFFLINE"
    const val STATE_UNKNOWN = "UNKNOWN"

    data class Layers(
        val trackingEnabled: Boolean,
        val serviceRunning: Boolean,
        val journeyActive: Boolean,
        val nowMs: Long,
        val lastCallbackAt: Long,   // último callback crudo del FLP
        val lastAcceptedAt: Long,   // último fix aceptado por el filtro
        val lastHeartbeatAt: Long,  // último heartbeat local persistido
        val lastAckAt: Long,        // último ACK del servidor
        val lastRecoveryAt: Long,   // último intento de recuperación
        val recoveryPending: Boolean, // RecoveryJournal intento sin confirmar
        val networkAvailable: Boolean,
        val screenOn: Boolean,
    )

    fun evaluate(l: Layers): String {
        if (!l.trackingEnabled || !l.journeyActive) return STATE_OFFLINE
        if (l.recoveryPending) return STATE_RECOVERY
        // "SILENT" solo si NO hay NINGUNA señal reciente del proceso (callback
        // crudo, fix aceptado o heartbeat local). NO significa proceso muerto:
        // puede ser Doze/freezer OEM — el diagnóstico de causa vive en
        // SilenceDiagnosis, no aquí.
        val processEvidenceFresh = fresh(l.lastCallbackAt, 10 * 60_000L, l.nowMs) ||
            fresh(l.lastAcceptedAt, 10 * 60_000L, l.nowMs) ||
            fresh(l.lastHeartbeatAt, 10 * 60_000L, l.nowMs)
        if (!processEvidenceFresh) return STATE_SILENT
        // Con evidencia de proceso: LIVE si captura y ACK frescos; DEGRADED si
        // algo del pipeline se retrasa.
        val captureFresh = fresh(l.lastAcceptedAt, 3 * 60_000L, l.nowMs) ||
            fresh(l.lastCallbackAt, 3 * 60_000L, l.nowMs)
        val ackFresh = fresh(l.lastAckAt, 3 * 60_000L, l.nowMs)
        return when {
            captureFresh && ackFresh -> STATE_LIVE
            captureFresh && !l.networkAvailable -> STATE_DEGRADED
            else -> STATE_DEGRADED
        }
    }

    /** Causa probable de un SILENT/DEGRADED (para diagnóstico, no para la UI del colaborador). */
    fun probableCause(l: Layers): String = when {
        !l.networkAvailable && fresh(l.lastCallbackAt, 10 * 60_000L, l.nowMs) -> "NETWORK_DOWN"
        !l.screenOn && !fresh(l.lastCallbackAt, 5 * 60_000L, l.nowMs) -> "SCREEN_OFF_NO_CALLBACK"
        else -> "UNKNOWN"
    }

    private fun fresh(at: Long, windowMs: Long, nowMs: Long): Boolean =
        at > 0L && nowMs - at < windowMs
}
