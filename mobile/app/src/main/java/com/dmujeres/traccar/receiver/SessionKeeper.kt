package com.dmujeres.traccar.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.location.TrackingService

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
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + periodMs, pi)
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
            if (config.trackingEnabled) {
                SessionKeeper.schedule(context)
            }
            val decision = SessionKeeperPolicy.decide(
                trackingEnabled = config.trackingEnabled,
                isRunning = TrackingService.isRunning,
                stopRequested = config.journeyStopRequested,
            )
            when (decision) {
                SessionKeeperPolicy.Decision.START -> {
                    Log.i("SessionKeeper", "Jornada activa sin servicio: revivir")
                    TrackingService.start(context)
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
