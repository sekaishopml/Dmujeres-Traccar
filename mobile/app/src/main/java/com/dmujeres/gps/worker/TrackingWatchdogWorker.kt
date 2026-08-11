package com.dmujeres.gps.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dmujeres.gps.data.PreferencesManager
import com.dmujeres.gps.service.GpsTrackingService
import kotlinx.coroutines.flow.first

/** Watchdog periódico: reinicia el servicio si el tracking estaba activo. */
class TrackingWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = PreferencesManager(applicationContext).preferences.first()
        if (prefs.trackingEnabled && prefs.uniqueId.isNotBlank() && prefs.serverHost.isNotBlank()) {
            GpsTrackingService.start(applicationContext)
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "tracking_watchdog"
    }
}
