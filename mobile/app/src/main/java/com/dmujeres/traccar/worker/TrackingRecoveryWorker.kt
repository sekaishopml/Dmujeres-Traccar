package com.dmujeres.traccar.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.R
import com.dmujeres.traccar.location.TrackingService
import com.dmujeres.traccar.util.Notifications

/**
 * Red de seguridad: si Android o el sistema detuvieron el servicio de tracking, este worker
 * periódico lo vuelve a arrancar (solo si el usuario dejó el tracking activado).
 */
class TrackingRecoveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = AppConfig(applicationContext)
        if (config.trackingEnabled && !TrackingService.isRunning) {
            runCatching { TrackingService.start(applicationContext) }
        }
        maybeNotifyDailySummary(config)
        return Result.success()
    }

    /** Notifica el resumen de la última jornada finalizada (una sola vez por jornada). */
    private fun maybeNotifyDailySummary(config: AppConfig) {
        val summary = config.lastJourneySummary
        if (summary.isBlank()) return
        val parts = summary.split('|')
        if (parts.size < 3) return
        val today = java.time.LocalDate.now().toString()
        if (config.lastSummaryNotified == today) return
        Notifications.alert(
            applicationContext,
            applicationContext.getString(R.string.daily_summary_title),
            applicationContext.getString(R.string.daily_summary_body, parts[0], parts[1], parts[2].toLongOrNull() ?: 0),
        )
        config.lastSummaryNotified = today
    }

    companion object {
        const val UNIQUE_NAME = "tracking-recovery"
    }
}
