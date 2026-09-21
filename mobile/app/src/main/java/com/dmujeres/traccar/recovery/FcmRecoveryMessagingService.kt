package com.dmujeres.traccar.recovery

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.tracking.TrackingService
import com.dmujeres.traccar.platform.SentryLog

/**
 * F2: receptor del probe de recovery FCM. Flujo: validar payload → prioridad →
 * dedupe → ACK RECEIVED → arrancar el recovery existente (TrackingService) →
 * ACK de etapas conforme se confirman (STARTED/FGS_ACTIVE/TRACKING_ACTIVE/
 * GPS_CONFIRMED). El SUCCESS lo decide el SERVIDOR con evidencia real
 * (posición posterior + ACK); aquí NUNCA se declara éxito.
 *
 * Prioridad: se compara la entregada con la original (classifyDelivery). La
 * degradación HIGH→NORMAL se registra explícitamente.
 *
 * Trabajo pesado delegado: el arranque del servicio es asíncrono, FcmAck tiene
 * executor daemon propio (nunca red en este callback) y las verificaciones
 * posteriores corren con Handler (sin bloquear onMessageReceived).
 */
class FcmRecoveryMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "FcmRecovery"
        private const val VERIFY_DELAY_MS = 6_000L
        /** Re-verificación única para GPS tardío (FGS vivo, sin fix a los 6 s). */
        private const val GPS_RECHECK_DELAY_MS = 30_000L
        private const val FIX_FRESH_MS = 60_000L
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val config = AppConfig(this)
        // Compara la prioridad ORIGINAL pedida por el emisor con la ENTREGADA:
        // FCM puede degradar HIGH→NORMAL en Doze/cuotas. La clasificación real
        // (HIGH/NORMAL/DEGRADED/UNKNOWN) es la que se persiste y reporta.
        val originalPriority = FcmRecoveryPolicy.classifyPriority(message.originalPriority)
        val deliveredPriority = FcmRecoveryPolicy.classifyPriority(message.priority)
        val priority = FcmRecoveryPolicy.classifyDelivery(message.originalPriority, message.priority)
        val priorityDetail = "original=$originalPriority delivered=$deliveredPriority classification=$priority"
        val validation = FcmRecoveryPolicy.validate(
            data = message.data,
            ownDeviceId = config.username,
            lastAttemptId = config.fcmLastAttemptId,
            nowMs = System.currentTimeMillis(),
        )

        if (!validation.valid) {
            config.fcmLastPriority = priority
            config.fcmLastOriginalPriority = originalPriority
            config.fcmLastResult = validation.reason
            config.fcmLastAt = System.currentTimeMillis()
            SentryLog.breadcrumb("fcm", "FCM_RECOVERY_BLOCKED", validation.reason)
            FcmAck.send(this, config, validation.attemptId, "RECOVERY_BLOCKED", priority, validation.reason)
            Log.w(TAG, "Probe rechazado: ${validation.reason}")
            return
        }

        // R8: gate de CONSENTIMIENTO — un probe jamás re-activa un rastreo que
        // la usuaria apagó (SessionKeeper/Boot ya lo exigían; este era el único
        // camino que lo saltaba). Con tracking off se ACKea BLOCKED y no se muta
        // ningún estado.
        if (!config.trackingEnabled) {
            config.fcmLastResult = "DISABLED_BY_USER"
            SentryLog.breadcrumb("fcm", "FCM_RECOVERY_BLOCKED", "DISABLED_BY_USER")
            FcmAck.send(this, config, validation.attemptId, "RECOVERY_BLOCKED", priority, "DISABLED_BY_USER")
            Log.i(TAG, "Probe ignorado: tracking desactivado por la usuaria")
            return
        }

        config.fcmLastAttemptId = validation.attemptId
        config.fcmLastPriority = priority
        config.fcmLastOriginalPriority = originalPriority
        config.fcmLastAt = System.currentTimeMillis()
        SentryLog.breadcrumb("fcm", "FCM_RECOVERY_RECEIVED", priorityDetail)
        if (priority == FcmRecoveryPolicy.PRIORITY_HIGH) {
            SentryLog.breadcrumb("fcm", "FCM_RECOVERY_PRIORITY_HIGH", priorityDetail)
        } else {
            // NORMAL/UNKNOWN/DEGRADED: el intento sigue, pero el arranque del FGS
            // desde background puede ser rechazado (ForegroundServiceStartNotAllowedException).
            SentryLog.breadcrumb("fcm", "FCM_PRIORITY_DEGRADED", priorityDetail)
        }
        FcmAck.send(this, config, validation.attemptId, "RECOVERY_RECEIVED", priority, "")

        // R8: ventana de rescate — CPU despierta 90 s (acotada, se libera sola)
        // para que el GPS enganche antes de que el sistema vuelva a dormir.
        com.dmujeres.traccar.recovery.RescueWindow.open(this)

        // R7: notificación visible ligada al probe (protege prioridad HIGH y
        // comunica la recuperación). Solo con el servicio ya activo: si no,
        // el propio arranque publicará la notificación de jornada.
        if (TrackingService.isRunning) {
            runCatching {
                com.dmujeres.traccar.platform.Notifications.ensureChannel(this)
                com.dmujeres.traccar.platform.Notifications.recoveryProbeInProgress(this)
            }
        }

        // Recovery: mismo mecanismo existente (TrackingService + SessionKeeper).
        try {
            if (TrackingService.isRunning) {
                // R8: ya corre → pedir un fix fresco (nudge manual existente);
                // la ventana de rescate ya está abierta para esperar el lock.
                runCatching { TrackingService.refresh(this) }
                FcmAck.send(this, config, validation.attemptId, "RECOVERY_STARTED", priority, "already-running")
                scheduleVerify(validation.attemptId, priority)
                return
            }
            config.trackingEnabled = true
            config.trackingState = com.dmujeres.traccar.core.TrackingState.SERVICE_RECOVERY.name
            val requested = TrackingService.start(this)
            if (!requested) {
                config.fcmLastResult = "BLOCKED_SERVICE_START"
                SentryLog.breadcrumb("fcm", "FCM_RECOVERY_BLOCKED", "BLOCKED_SERVICE_START")
                FcmAck.send(this, config, validation.attemptId, "RECOVERY_BLOCKED", priority, "BLOCKED_SERVICE_START")
                return
            }
            FcmAck.send(this, config, validation.attemptId, "RECOVERY_STARTED", priority, "")
            scheduleVerify(validation.attemptId, priority)
            Log.i(TAG, "Recovery iniciado por FCM (attempt=${validation.attemptId})")
        } catch (error: Exception) {
            // SecurityException / ForegroundServiceStartNotAllowedException quedan
            // registrados honestamente: jamás se declara éxito aquí.
            val reason = when (error.javaClass.simpleName) {
                "ForegroundServiceStartNotAllowedException" -> "BLOCKED_BACKGROUND_RESTRICTION"
                "SecurityException" -> "BLOCKED_PERMISSION"
                else -> "BLOCKED_SERVICE_START"
            }
            config.fcmLastResult = reason
            SentryLog.breadcrumb("fcm", "FCM_RECOVERY_BLOCKED", reason)
            FcmAck.send(this, config, validation.attemptId, "RECOVERY_BLOCKED", priority, reason)
            Log.w(TAG, "Recovery bloqueado: $reason", error)
        }
    }

    /**
     * Verificación a los 6 s (asíncrona). El timeout de 6 s es SOLO para el FGS
     * inmediato: si el FGS no está vivo → NO_FGS (timeout real). Si está vivo
     * pero no hay fix fresco, JAMÁS se declara TIMEOUT_NO_GPS: se ACKea
     * TRACKING_ACTIVE y se programa UNA re-verificación a los 30 s; el cierre
     * final lo decide el server con su evidenceTimeoutSeconds.
     */
    private fun scheduleVerify(attemptId: String, priority: String) {
        Handler(Looper.getMainLooper()).postDelayed({
            val config = AppConfig(this)
            val running = TrackingService.isRunning
            val fixFresh = isFixFresh(config)
            when (FcmRecoveryPolicy.classifyVerify(running, fixFresh)) {
                FcmRecoveryPolicy.VERIFY_TIMEOUT_NO_FGS -> {
                    config.fcmLastResult = "TIMEOUT_NO_FGS"
                    SentryLog.breadcrumb("fcm", "FCM_RECOVERY_TIMEOUT", "TIMEOUT_NO_FGS")
                    FcmAck.send(this, config, attemptId, "RECOVERY_TIMEOUT", priority, "TIMEOUT_NO_FGS")
                }
                FcmRecoveryPolicy.VERIFY_GPS_CONFIRMED -> {
                    config.fcmLastResult = "TRACKING_ACTIVE"
                    FcmAck.send(this, config, attemptId, "RECOVERY_FGS_ACTIVE", priority, "")
                    FcmAck.send(this, config, attemptId, "RECOVERY_TRACKING_ACTIVE", priority, "")
                    FcmAck.send(this, config, attemptId, "RECOVERY_GPS_CONFIRMED", priority, "")
                    SentryLog.breadcrumb("fcm", "FCM_RECOVERY_GPS_CONFIRMED", "6s")
                }
                else -> {
                    // FGS vivo, sin fix todavía: estado PENDING, no fallo.
                    config.fcmLastResult = "TRACKING_ACTIVE"
                    FcmAck.send(this, config, attemptId, "RECOVERY_FGS_ACTIVE", priority, "")
                    FcmAck.send(this, config, attemptId, "RECOVERY_TRACKING_ACTIVE", priority, "")
                    SentryLog.breadcrumb("fcm", "FCM_RECOVERY_GPS_PENDING", "sin fix a 6s; recheck a 30s")
                    scheduleGpsRecheck(attemptId, priority)
                }
            }
        }, VERIFY_DELAY_MS)
    }

    /**
     * Re-verificación única a los 30 s para el GPS tardío. Solo actúa con el
     * servicio aún vivo: con fix → GPS_CONFIRMED; sin fix → breadcrumb y cierre
     * al servidor (no se inventa éxito ni fallo local).
     */
    private fun scheduleGpsRecheck(attemptId: String, priority: String) {
        Handler(Looper.getMainLooper()).postDelayed({
            val config = AppConfig(this)
            if (!TrackingService.isRunning) {
                // El FGS murió tras la primera verificación: el server ya tiene
                // FGS_ACTIVE/TRACKING_ACTIVE y su evidenceTimeout decide.
                SentryLog.breadcrumb("fcm", "FCM_RECOVERY_GPS_PENDING", "FGS no vivo a 30s; cierre al server")
                return@postDelayed
            }
            if (isFixFresh(config)) {
                FcmAck.send(this, config, attemptId, "RECOVERY_GPS_CONFIRMED", priority, "")
                SentryLog.breadcrumb("fcm", "FCM_RECOVERY_GPS_CONFIRMED", "30s")
            } else {
                SentryLog.breadcrumb("fcm", "FCM_RECOVERY_GPS_PENDING", "sin fix a 30s; cierre al server")
            }
        }, GPS_RECHECK_DELAY_MS)
    }

    private fun isFixFresh(config: AppConfig): Boolean {
        val lastFix = config.lastFixAt
        return lastFix > 0L && System.currentTimeMillis() - lastFix < FIX_FRESH_MS
    }

    override fun onNewToken(token: String) {
        // Rotación de token: registrar en backend (idempotente, sin token en logs).
        FcmTokenRegistrar.register(this, explicitToken = token)
    }
}
