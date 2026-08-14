package com.dmujeres.traccar

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dmujeres.traccar.db.AppDatabase
import com.dmujeres.traccar.worker.TrackingRecoveryWorker
import java.util.concurrent.TimeUnit

class DmujeresApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        // Red de seguridad: si el sistema mata el tracking, se recupera solo.
        val request = PeriodicWorkRequestBuilder<TrackingRecoveryWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            TrackingRecoveryWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
