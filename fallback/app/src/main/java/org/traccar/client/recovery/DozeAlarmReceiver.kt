package org.traccar.client.recovery

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.preference.PreferenceManager
import org.traccar.client.Prefs
import org.traccar.client.TrackingService

/**
 * Rescate de Doze (RecoveryEngine, ADR-003): alarma inexacta cada 9 min con
 * `setAndAllowWhileIdle` —la única que Doze respeta sin permiso especial—.
 *
 * NO es el reloj del tracking (la cadencia la manda la captura continua): es
 * el rescate para cuando el Handler del servicio quedó congelado. Al despertar
 * pide un fix FRESCO (nunca last-known) y vacía la cola.
 *
 * Wake lock: solo uno corto en el receiver (timeout de seguridad) más el de
 * envío que ya existe. Sin wake lock permanente (descartado por consumo).
 * Cada despertar cuenta en `recovery_count` y se reporta en el latido.
 */
class DozeAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RECOVER) return
        // Corto y con timeout: si el sistema tarda, se suelta solo.
        val wakeLock = runCatching {
            (context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dmujeres:recover").apply {
                    setReferenceCounted(false)
                    acquire(RECEIVER_WAKELOCK_MS)
                }
        }.getOrNull()
        try {
            countRecovery(context)
            schedule(context) // rearma SIEMPRE, haya o no servicio activo
            val woken = TrackingService.onDozeAlarm(context)
            Log.i(TAG, "despertar de recuperación (servicio_activo=$woken)")
        } finally {
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        }
    }

    companion object {
        private val TAG = DozeAlarmReceiver::class.java.simpleName
        const val ACTION_RECOVER = "org.traccar.client.action.DOZE_RECOVER"

        /** La alarma que Doze respeta sin permiso especial (~9 min mínimo). */
        const val INTERVAL_MS = 9 * 60_000L

        /** Wake lock del receiver: corto, solo el despertar (el envío usa el suyo). */
        private const val RECEIVER_WAKELOCK_MS = 30_000L

        /** Rearma la alarma de rescate (servicio, boot y replace la llaman). */
        fun schedule(context: Context) {
            val manager = context.applicationContext
                .getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = pendingIntent(context)
            // Inexacta a propósito: exacta pediría permiso especial sin aporte.
            manager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + INTERVAL_MS,
                pending,
            )
        }

        fun cancel(context: Context) {
            val manager = context.applicationContext
                .getSystemService(Context.ALARM_SERVICE) as AlarmManager
            manager.cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context.applicationContext, DozeAlarmReceiver::class.java)
                .setAction(ACTION_RECOVER)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            return PendingIntent.getBroadcast(context.applicationContext, 0, intent, flags)
        }

        /** Cada recuperación cuenta (persiste en prefs: sobrevive reboot). */
        internal fun countRecovery(context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val next = prefs.getLong(Prefs.RECOVERY_COUNT, 0L) + 1
            prefs.edit().putLong(Prefs.RECOVERY_COUNT, next).apply()
        }
    }
}
