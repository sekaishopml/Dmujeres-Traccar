package com.dmujeres.traccar.recovery

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.tracking.TrackingService
import com.dmujeres.traccar.platform.Notifications
import com.dmujeres.traccar.recovery.RecoveryJournal

/**
 * Guardián de sesión por ALARMA (canal más fuerte que WorkManager frente a
 * OEMs agresivos tipo Mifavor "con control de IA"): con jornada abierta,
 * despierta el proceso cada [PERIOD_MS] y, si el servicio NO está vivo,
 * lo relanza con ACTION_START. Encadena la siguiente alarma él mismo.
 *
 * Casos que cubre (poco frecuentes pero reales en campo):
 * - El OEM mata el FGS durante una jornada y también congela el worker.
 * - Reinstalación de la app (install mata el proceso; MY_PACKAGE_REPLACED
 *   llega, pero con pantalla apagada puede no consolidar).
 * - Reboot (refuerza al BootReceiver).
 *
 * setAndAllowWhileIdle (inexacta): se permite en Doze con diferimiento
 * (≥15 min), no requiere permisos de alarma exacta en ninguna API >= 26.
 * Costo: 1 broadcast cada 15 min solo mientras trackingEnabled=true.
 */
object SessionKeeper {

    private const val TAG = "SessionKeeper"
    const val ACTION_KEEPER = "com.dmujeres.traccar.SESSION_KEEPER"
    const val PERIOD_MS = 15L * 60_000L

    fun schedule(context: Context, periodMs: Long = PERIOD_MS) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context)
        val canExact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()
        val triggerAt = SystemClock.elapsedRealtime() + periodMs
        runCatching {
            // Con exención de batería, canScheduleExactAlarms() devuelve true y
            // la alarma exacta NO necesita SCHEDULE_EXACT_ALARM (fase 1).
            // Sin ella se mantiene la inexacta (permitida en Doze).
            if (SessionKeeperAlarmPolicy.useExact(Build.VERSION.SDK_INT, canExact)) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }.recoverCatching {
            // R8: si la exacta lanza (carrera de revocación/OEM), la cadena NO
            // debe morir en silencio: se cae a la inexacta (permitida en Doze).
            Log.w(TAG, "Exacta falló; fallback a inexacta", it)
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }.onFailure { Log.w(TAG, "No se pudo programar el guardián", it) }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pendingIntent(context)) }
            .onFailure { Log.w(TAG, "No se pudo cancelar el guardián", it) }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 2001,
            Intent(context, SessionKeeperReceiver::class.java).setAction(ACTION_KEEPER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

class SessionKeeperReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SessionKeeper.ACTION_KEEPER) return
        runCatching {
            val config = AppConfig(context)
            // Re-armar SIEMPRE que la jornada siga activa (cadena); si el
            // usuario cerró la jornada, no se re-programa (la alarma muere).
            // 1.1.8: la cadena conserva la cadencia de 2 min con jornada activa
            // (antes re-armaba con el default de 15 min y degradaba el guard).
            if (config.trackingEnabled) {
                SessionKeeper.schedule(
                    context,
                    com.dmujeres.traccar.tracking.ForegroundGuardPolicy.keeperPeriodMs(
                        moving = false, journeyActive = true,
                    ),
                )
            }
            val nowMs = System.currentTimeMillis()
            val isRunning = TrackingService.isRunning
            // R8: el despertar del guardián también necesita CPU para que el GPS
            // enganche (antes solo FCM abría ventana -> "despierta pero no fija").
            runCatching { RescueWindow.open(context, RescueWindowPolicy.KEEPER_WINDOW_MS) }
            if (isRunning) {
                // Nudge de ADQUISICIÓN real (no solo re-registro del request).
                runCatching { TrackingService.refresh(context) }
            }
            val decision = SessionKeeperPolicy.decide(
                trackingEnabled = config.trackingEnabled,
                isRunning = isRunning,
                stopRequested = config.journeyStopRequested,
            )
            // Verificación honesta del intento ANTERIOR (Fase 6): el start()
            // del fire previo es asíncrono; recién aquí se sabe si consolidó.
            when (
                RecoveryJournal.verdictForPreviousAttempt(
                    trackingEnabled = config.trackingEnabled,
                    isRunning = isRunning,
                    nowMs = nowMs,
                    lastAttemptAtMs = config.lastRecoveryAt,
                    lastResult = config.lastRecoveryResult,
                )
            ) {
                RecoveryJournal.PreviousVerdict.SUCCESS -> {
                    Log.i("SessionKeeper", "RECOVERY_SUCCESS")
                    config.lastRecoveryResult = RecoveryJournal.RESULT_OK
                    config.recoveryConfirmAt = nowMs
                    config.attemptsSinceLastSuccess = 0
                }
                RecoveryJournal.PreviousVerdict.BLOCKED -> {
                    Log.i("SessionKeeper", "RECOVERY_BLOCKED_OEM (posible OEM)")
                    config.lastRecoveryResult = RecoveryJournal.RESULT_BLOCKED
                    // FASE 6: el intento anterior no consolidó y Android/OEM
                    // bloqueó el start en segundo plano → acción de usuario.
                    runCatching {
                        Notifications.resumeRequired(context)
                    }
                }
                RecoveryJournal.PreviousVerdict.NONE -> Unit
            }
            when (decision) {
                SessionKeeperPolicy.Decision.START -> {
                    Log.i("SessionKeeper", "RECOVERY_ATTEMPT isRunning=false")
                    val started = TrackingService.start(context)
                    // El FGS consolidará (o no) de forma asíncrona: el
                    // resultado queda pendiente hasta la verificación.
                    config.incRecoveryAttempt(RecoveryJournal.RESULT_PENDING)
                    if (!started) {
                        // El sistema rechazó el arranque en segundo plano: no se
                        // finge recuperación; se pide acción del usuario.
                        config.lastRecoveryResult = RecoveryJournal.RESULT_BLOCKED
                        runCatching { Notifications.resumeRequired(context) }
                    }
                    Unit
                }
                else -> Log.d("SessionKeeper", "Keeper: $decision (sin acción)")
            }
        }.onFailure { Log.w("SessionKeeper", "Keeper falló", it) }
    }
}

/** Política pura (JVM): decide si el guardián debe revivir el servicio. */
object SessionKeeperPolicy {
    enum class Decision { START, SKIP_NOT_ACTIVE, SKIP_RUNNING, SKIP_STOP_PENDING }

    fun decide(trackingEnabled: Boolean, isRunning: Boolean, stopRequested: Boolean): Decision = when {
        !trackingEnabled -> Decision.SKIP_NOT_ACTIVE
        isRunning -> Decision.SKIP_RUNNING
        stopRequested -> Decision.SKIP_STOP_PENDING
        else -> Decision.START
    }
}

/** Política pura (JVM): ¿el guardián puede usar alarma exacta en este equipo? */
object SessionKeeperAlarmPolicy {
    const val EXACT_SDK = 31

    fun useExact(sdkInt: Int, canScheduleExact: Boolean): Boolean =
        sdkInt >= EXACT_SDK && canScheduleExact
}
