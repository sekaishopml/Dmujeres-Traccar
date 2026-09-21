package com.dmujeres.traccar.recovery

/**
 * Política PURA (JVM) del FCM Recovery en Android. Valida el payload mínimo,
 * clasifica la prioridad y decide el bloqueo por duplicado. El ACK de etapas
 * y la ejecución real viven en FcmRecoveryMessagingService.
 */
object FcmRecoveryPolicy {

    const val TYPE_PROBE = "TRACKING_RECOVERY_PROBE"

    const val PRIORITY_HIGH = "HIGH"
    const val PRIORITY_NORMAL = "NORMAL"
    const val PRIORITY_DEGRADED = "DEGRADED"
    const val PRIORITY_UNKNOWN = "UNKNOWN"

    /** Resultado puro de una verificación post-arranque (sin efectos). */
    const val VERIFY_TIMEOUT_NO_FGS = "VERIFY_TIMEOUT_NO_FGS"
    const val VERIFY_GPS_CONFIRMED = "VERIFY_GPS_CONFIRMED"
    const val VERIFY_GPS_PENDING = "VERIFY_GPS_PENDING"

    /** Antigüedad máxima del probe aceptada (relojes móviles pueden desfasar). */
    const val MAX_PROBE_AGE_MS = 24L * 3_600_000L
    /** Tolerancia de futuro (skew del servidor). */
    const val MAX_PROBE_FUTURE_MS = 3_600_000L

    data class Validation(
        val valid: Boolean,
        val reason: String,
        val attemptId: String,
        val priority: String,
    )

    /**
     * Valida el DATA message del server.
     * @param data payload completo del mensaje
     * @param ownDeviceId identity del dispositivo (username, igual que el canal HTTP)
     * @param lastAttemptId último recoveryAttemptId procesado (dedupe)
     * @param nowMs reloj actual
     */
    fun validate(
        data: Map<String, String>,
        ownDeviceId: String,
        lastAttemptId: String,
        nowMs: Long,
    ): Validation {
        val type = data["type"].orEmpty()
        val attemptId = data["recoveryAttemptId"].orEmpty()
        val deviceId = data["deviceId"].orEmpty()
        val issuedAt = data["issuedAt"]?.toLongOrNull() ?: 0L

        if (type != TYPE_PROBE) {
            return Validation(false, "BLOCKED_INVALID_PAYLOAD", attemptId, PRIORITY_UNKNOWN)
        }
        if (attemptId.isBlank() || attemptId.length > 128) {
            return Validation(false, "BLOCKED_INVALID_PAYLOAD", attemptId, PRIORITY_UNKNOWN)
        }
        if (deviceId.isBlank() || deviceId != ownDeviceId) {
            return Validation(false, "BLOCKED_DEVICE_MISMATCH", attemptId, PRIORITY_UNKNOWN)
        }
        if (issuedAt <= 0L || nowMs - issuedAt > MAX_PROBE_AGE_MS
            || issuedAt - nowMs > MAX_PROBE_FUTURE_MS
        ) {
            return Validation(false, "BLOCKED_INVALID_PAYLOAD", attemptId, PRIORITY_UNKNOWN)
        }
        if (attemptId == lastAttemptId) {
            return Validation(false, "BLOCKED_DUPLICATE", attemptId, PRIORITY_UNKNOWN)
        }
        return Validation(true, "", attemptId, PRIORITY_UNKNOWN)
    }

    /** Prioridad FCM (RemoteMessage.getPriority/getOriginalPriority): 1=HIGH, 2=NORMAL. */
    fun classifyPriority(remotePriority: Int): String = when (remotePriority) {
        1 -> PRIORITY_HIGH
        2 -> PRIORITY_NORMAL
        else -> PRIORITY_UNKNOWN
    }

    /**
     * Clasificación REAL de la entrega comparando prioridad original (la pedida
     * por el emisor) vs entregada (la efectiva en el dispositivo). FCM puede
     * bajar HIGH→NORMAL (Doze/cuotas): eso es DEGRADED. Una entrega HIGH de un
     * original NORMAL es una mejora, no degradación.
     *
     * @param originalPriority RemoteMessage.getOriginalPriority()
     * @param deliveredPriority RemoteMessage.getPriority()
     */
    fun classifyDelivery(originalPriority: Int, deliveredPriority: Int): String {
        val original = classifyPriority(originalPriority)
        val delivered = classifyPriority(deliveredPriority)
        return when {
            original == PRIORITY_UNKNOWN || delivered == PRIORITY_UNKNOWN -> PRIORITY_UNKNOWN
            original == PRIORITY_HIGH && delivered != PRIORITY_HIGH -> PRIORITY_DEGRADED
            else -> delivered
        }
    }

    /**
     * Decisión pura de una verificación del recovery:
     * - sin FGS vivo → timeout real de FGS;
     * - FGS vivo + fix fresco → GPS confirmado (ACK, sin declarar SUCCESS);
     * - FGS vivo + sin fix fresco → PENDING: NUNCA es fallo; el server cierra
     *   con su evidenceTimeoutSeconds si el fix no llega.
     */
    fun classifyVerify(fgsRunning: Boolean, fixFresh: Boolean): String = when {
        !fgsRunning -> VERIFY_TIMEOUT_NO_FGS
        fixFresh -> VERIFY_GPS_CONFIRMED
        else -> VERIFY_GPS_PENDING
    }

    /**
     * Un probe sin prioridad HIGH efectiva puede intentar recovery, pero se
     * registra: Android puede rechazar el arranque del FGS desde background si
     * no hay excepción de alta prioridad vigente.
     */
    fun isDegraded(priority: String): Boolean = priority != PRIORITY_HIGH
}
