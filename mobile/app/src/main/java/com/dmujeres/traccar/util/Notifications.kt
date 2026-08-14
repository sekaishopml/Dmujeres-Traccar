package com.dmujeres.traccar.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dmujeres.traccar.MainActivity
import com.dmujeres.traccar.R

object Notifications {

    const val CHANNEL_ID = "dmj_tracking"
    const val CHANNEL_ALERTS = "dmj_alerts"
    const val NOTIFICATION_ID = 1
    const val ALERT_ID = 2
    const val WAKE_ID = 3

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
        manager.createNotificationChannel(ongoing)
        manager.createNotificationChannel(alerts)
    }

    fun foregroundNotification(context: Context, title: String, text: String): Notification {
        val pending = pendingIntent(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Alerta puntual con sonido (conectado/desconectado, inicio/fin de jornada, avisos). */
    fun alert(context: Context, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(ALERT_ID, notification)
    }

    /** Notificación de máxima prioridad que ENCIENDE la pantalla (con permiso). */
    fun wakeScreen(context: Context, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
            && !manager.canUseFullScreenIntent()
        ) {
            alert(context, title, text)
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
}
