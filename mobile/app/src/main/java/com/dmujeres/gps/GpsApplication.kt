package com.dmujeres.gps

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dmujeres.gps.worker.TrackingWatchdogWorker
import java.util.concurrent.TimeUnit

class GpsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        scheduleWatchdog()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val trackingChannel = NotificationChannel(
                CHANNEL_TRACKING,
                getString(R.string.notification_channel_tracking),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación permanente de rastreo GPS"
            }
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas de conectividad"
            }
            manager.createNotificationChannel(trackingChannel)
            manager.createNotificationChannel(alertsChannel)
        }
    }

    private fun scheduleWatchdog() {
        val request = PeriodicWorkRequestBuilder<TrackingWatchdogWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            TrackingWatchdogWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"
        const val CHANNEL_ALERTS = "alerts"
        const val NOTIFICATION_ID_TRACKING = 1001
        const val NOTIFICATION_ID_NO_SIGNAL = 1002
    }
}
