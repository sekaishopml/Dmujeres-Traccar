package com.dmujeres.traccar.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.DmujeresApp
import com.dmujeres.traccar.R
import com.dmujeres.traccar.location.TrackingService
import com.dmujeres.traccar.mqtt.HttpFallbackDispatcher
import com.dmujeres.traccar.util.Notifications
import com.dmujeres.traccar.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Red de seguridad: recupera el servicio activo y drena posiciones pendientes después de cerrar
 * una jornada, sin perder el outbox si la app ya no está abierta.
 */
class TrackingRecoveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = AppConfig(applicationContext)
        if (config.trackingEnabled && !TrackingService.isRunning) {
            val started = runCatching { TrackingService.start(applicationContext) }.getOrDefault(false)
            if (!started) {
                Log.e("TrackingRecoveryWorker", "No se pudo reactivar el servicio")
                config.lastStartError = "La red de seguridad no pudo reactivar el servicio"
            }
        } else if (config.journeyStopRequested && config.journeyStartAt > 0L
            && TrackingService.isRunning
        ) {
            runCatching { TrackingService.stop(applicationContext) }
        }
        if (!config.trackingEnabled && !TrackingService.isRunning) {
            val dao = (applicationContext as DmujeresApp).database.positionDao()
            drainPending(dao, config)
            val remaining = withContext(Dispatchers.IO) { dao.count() }
            if (remaining == 0) {
                config.journeyStopRequested = false
            }
        }
        maybeNotifyDailySummary(config)
        UpdateChecker.checkAndRefreshBadge(applicationContext)
        return Result.success()
    }

    private suspend fun drainPending(dao: com.dmujeres.traccar.db.PositionDao, config: AppConfig) {
        val deadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadline) {
            val remaining = withContext(Dispatchers.IO) { dao.count() }
            if (remaining == 0) return
            val flushed = runCatching {
                withContext(Dispatchers.IO) {
                    HttpFallbackDispatcher.flush(dao, config)
                }
            }.getOrElse { error ->
                Log.w("TrackingRecoveryWorker", "No se pudo drenar el outbox", error)
                0
            }
            if (flushed <= 0) return
        }
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
            applicationContext.getString(
                R.string.daily_summary_body,
                parts[0],
                parts[1],
                parts[2].toLongOrNull() ?: 0,
                parts.getOrNull(3)?.toLongOrNull() ?: 0,
            ),
        )
        config.lastSummaryNotified = today
    }

    companion object {
        const val UNIQUE_NAME = "tracking-recovery"
        const val STARTUP_NAME = "tracking-recovery-startup"
    }
}
