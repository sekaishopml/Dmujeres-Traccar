package com.dmujeres.traccar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dmujeres.traccar.db.AppDatabase
import com.dmujeres.traccar.util.UpdateChecker
import com.dmujeres.traccar.worker.TrackingRecoveryWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class DmujeresApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) return
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork ?: return
            val caps = manager.getNetworkCapabilities(network) ?: return
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
            // Al volver la conexión, comprueba la versión de inmediato.
            appScope.launch { UpdateChecker.checkAndRefreshBadge(applicationContext) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Red de seguridad: si el sistema mata el tracking, se recupera solo.
        val request = PeriodicWorkRequestBuilder<TrackingRecoveryWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            TrackingRecoveryWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        WorkManager.getInstance(this).enqueueUniqueWork(
            TrackingRecoveryWorker.STARTUP_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TrackingRecoveryWorker>().build(),
        )
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        ContextCompat.registerReceiver(
            this,
            connectivityReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
