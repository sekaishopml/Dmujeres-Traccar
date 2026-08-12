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
    const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_name)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun foregroundNotification(context: Context, title: String, text: String): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun update(context: Context, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, foregroundNotification(context, title, text))
    }
}
