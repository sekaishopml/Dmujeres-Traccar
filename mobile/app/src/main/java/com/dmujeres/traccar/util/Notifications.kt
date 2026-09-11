package com.dmujeres.traccar.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dmujeres.traccar.MainActivity
import com.dmujeres.traccar.R

/**
 * Política pura anti-spam de alertas (JVM-testable, sin Android).
 *
 * Reglas (aplicadas centralmente en [Notifications.alert], invisibles para los
 * ~15 call sites):
 * - MISMA alerta (title+body): no se repite dentro de [SAME_KEY_COOLDOWN_MS]
 *   (mata el doble Boot+Worker de la misma recuperación y el reintento del
 *   mismo aviso en ciclos de refresh).
 * - GAP GLOBAL: entre dos alertas CUALESQUIERA deben pasar [GLOBAL_MIN_GAP_MS]
 *   (acota el flap estado A↔B aunque las claves difieran).
 * - Umbral 0 = "nunca alerté" → siempre pasa la primera.
 */
object AlertPolicy {
    const val SAME_KEY_COOLDOWN_MS = 30 * 60_000L
    const val GLOBAL_MIN_GAP_MS = 60_000L

    fun shouldAlert(now: Long, lastSameKeyAt: Long, lastAnyAt: Long): Boolean =
        now - lastSameKeyAt >= SAME_KEY_COOLDOWN_MS && now - lastAnyAt >= GLOBAL_MIN_GAP_MS

    /** Clave de dedupe: lo que el usuario ve (título + cuerpo). */
    fun keyOf(title: String, body: String): String = "$title\n$body"
}

object Notifications {

    private const val ALERTS_PREFS = "dmj_alerts"
    private const val KEY_LAST_ALERT = "lastAlertKey"
    private const val KEY_LAST_ALERT_AT = "lastAlertKeyAt"
    private const val KEY_LAST_ANY_AT = "lastAnyAt"
    private const val KEY_CONNECTED_NOTIFIED = "lastConnectedNotified"

    const val CHANNEL_ID = "dmj_tracking"
    const val CHANNEL_ALERTS = "dmj_alerts"
    const val CHANNEL_UPDATES = "dmj_updates"
    const val NOTIFICATION_ID = 1
    const val ALERT_ID = 2
    const val WAKE_ID = 3
    const val CONNECTION_ALERT_ID = 4
    const val BATTERY_ALERT_ID = 5
    const val UPDATE_BADGE_ID = 6

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ongoing = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_name)
            setShowBadge(false)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_alerts)
        }
        val updates = NotificationChannel(
            CHANNEL_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_updates)
            setShowBadge(true)
        }
        manager.createNotificationChannel(ongoing)
        manager.createNotificationChannel(alerts)
        manager.createNotificationChannel(updates)
    }

    /** Badge persistente en el icono de la app cuando hay una versión nueva disponible. */
    fun updateAvailable(context: Context, version: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val title = context.getString(R.string.update_badge_title)
        val text = context.getString(R.string.update_badge_text, version)
        val open = pendingIntent(context)

        val updateIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_UPDATE, true)
        }
        val updatePending = PendingIntent.getActivity(
            context, 7, updateIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_pin)
            .setContentIntent(open)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setNumber(1)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(R.drawable.ic_stat_pin, context.getString(R.string.update_now), updatePending)
            .build()
        manager.notify(UPDATE_BADGE_ID, notification)
    }

    fun clearUpdateAvailable(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(UPDATE_BADGE_ID)
    }

    fun foregroundNotification(context: Context, title: String, text: String): Notification {
        val pending = pendingIntent(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_pin)
            .setOngoing(true)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .build()
    }

/**
 * Alerta puntual con sonido (conectado/desconectado, avisos...). Firma y_semántica
 * externas intactas; por dentro aplica la dedupe+cooldown de [AlertPolicy] con
 * estado persistente (mismo proceso, boot, worker comparten prefs): la misma
 * title+body no se repite en 30 min y entre alertas distintas hay 60 s mínimas.
 * Se salta en silencio (log). Para alertas que deben ser inmediatas
 * (inicio de jornada) usa [alertNow].
 */
    fun alert(context: Context, title: String, text: String, notificationId: Int = ALERT_ID) {
        val now = System.currentTimeMillis()
        val p = alertPrefs(context)
        val key = AlertPolicy.keyOf(title, text)
        val lastSameKeyAt = if (p.getString(KEY_LAST_ALERT, "") == key) {
            p.getLong(KEY_LAST_ALERT_AT, 0L)
        } else {
            0L
        }
        if (!AlertPolicy.shouldAlert(now, lastSameKeyAt, p.getLong(KEY_LAST_ANY_AT, 0L))) {
            Log.d("Notifications", "Alerta suprimida por cooldown: $title")
            return
        }
        p.edit()
            .putString(KEY_LAST_ALERT, key)
            .putLong(KEY_LAST_ALERT_AT, now)
            .putLong(KEY_LAST_ANY_AT, now)
            .apply()
        postAlert(context, title, text, notificationId)
    }

    /** Alerta inmediata sin cooldown (inicio de jornada: el usuario la pidió ya). */
    fun alertNow(context: Context, title: String, text: String, notificationId: Int = ALERT_ID) {
        val p = alertPrefs(context)
        p.edit()
            .putString(KEY_LAST_ALERT, AlertPolicy.keyOf(title, text))
            .putLong(KEY_LAST_ALERT_AT, System.currentTimeMillis())
            .putLong(KEY_LAST_ANY_AT, System.currentTimeMillis())
            .apply()
        postAlert(context, title, text, notificationId)
    }

    /**
     * Estado persistido del aviso "Conectado al servidor": solo transición real
     * OFF→ON (false→alert→true; DISCONNECTED lo vuelve a false). Sobrevive al proceso
     * para que un restart conectado no vuelva a sonar.
     */
    fun wasConnectedNotified(context: Context): Boolean =
        alertPrefs(context).getBoolean(KEY_CONNECTED_NOTIFIED, false)

    fun setConnectedNotified(context: Context, value: Boolean) {
        alertPrefs(context).edit().putBoolean(KEY_CONNECTED_NOTIFIED, value).apply()
    }

    private fun alertPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(ALERTS_PREFS, Context.MODE_PRIVATE)

    private fun postAlert(context: Context, title: String, text: String, notificationId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_pin)
            .setContentIntent(pendingIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(notificationId, notification)
    }

    /** Notificación de máxima prioridad que ENCIENDE la pantalla (con permiso). */
    fun wakeScreen(context: Context, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (android.os.Build.VERSION.SDK_INT >= 34
            && !manager.canUseFullScreenIntent()
        ) {
            alert(context, title, text)
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_pin)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .build()
        manager.notify(WAKE_ID, notification)
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun update(context: Context, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, foregroundNotification(context, title, text))
    }

    /** Notificación de "jornada finalizada": sustituye a la fija cuando el servicio para. */
    fun finished(context: Context, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_pin)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent(context))
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
