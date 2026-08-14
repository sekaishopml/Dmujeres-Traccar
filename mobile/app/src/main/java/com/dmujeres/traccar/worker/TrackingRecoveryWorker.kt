package com.dmujeres.traccar.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.location.TrackingService

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
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "tracking-recovery"
    }
}
