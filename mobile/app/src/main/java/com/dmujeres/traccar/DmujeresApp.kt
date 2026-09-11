package com.dmujeres.traccar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.db.AppDatabase
import com.dmujeres.traccar.location.TrackingService
import com.dmujeres.traccar.util.Notifications
import com.dmujeres.traccar.util.UpdateChecker
import com.dmujeres.traccar.worker.RecoverySchedule
import com.dmujeres.traccar.worker.TrackingRecoveryWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DmujeresApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Reporte de crashes a Sentry. Solo se activa si hay DSN configurado
     * (ver SENTRY_DSN en app/build.gradle.kts): sin DSN no hace nada y no
     * gasta batería ni red. No captura pantallas por privacidad.
     */
    private fun initCrashReporting() {
        val dsn = BuildConfig.SENTRY_DSN.trim()
        if (dsn.isBlank()) {
            return
        }
        // try/catch(Throwable) a propósito (no runCatching): un Error (p.ej.
        // clase recortada por R8) también debe quedar atrapado para no tumbar
        // el arranque de la app.
        try {
            io.sentry.android.core.SentryAndroid.init(this) { options ->
                options.dsn = dsn
                options.environment = if (BuildConfig.DEBUG) "debug" else "production"
                options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                options.tracesSampleRate = 0.1
                options.isAttachScreenshot = false
                // ANR reales (antes el contador anrs24h no tenía emisor): con
                // video del replay en errores (artefacto transitivo, solo-error).
                options.isAnrEnabled = true
                options.isAnrReportInDebug = false
                options.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ ->
                    // Sin PII extra: solo crash + contexto de app (versión, dispositivo).
                    event.user?.ipAddress = null
                    // Los ANR por fin tienen emisor: contarlos para el monitor diario.
                    if (event.exceptions?.any { it.type?.contains("ANR", ignoreCase = true) == true } == true) {
                        runCatching { AppConfig(applicationContext).incAnr24h() }
                    }
                    event
                }
            }
            Log.i(TAG, "Crash reporting activo (Sentry)")
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudo iniciar Sentry", t)
        }
    }

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) return
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork ?: return
            val caps = manager.getNetworkCapabilities(network) ?: return
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
            // Al volver la conexión, comprueba la versión de inmediato.
            appScope.launch { UpdateChecker.checkAndRefreshBadge(applicationContext) }
            // SEGUNDO PLANO BLINDADO: además del UpdateChecker, drenaje + GPS.
            // - One-shot expedited inmediato (no espera al periódico de 15 min).
            // - Re-registro GPS vía intent al servicio (TrackingService.start es
            //   idempotente: si ya corre, startTracking() retorna sin duplicar el
            //   watchdog; el watchdog interno ya re-registra cada 2 min si no hay
            //   fix, aquí solo se le da un empujón tras reconectar).
            // Respeta VendorSettings: no se toca su guía, solo se reintenta.
            appScope.launch {
                try {
                    val trackingOn = runCatching { AppConfig(applicationContext).trackingEnabled }
                        .getOrDefault(false)
                    if (ReconnectPolicy.shouldExpediteOnReconnect(trackingOn)) {
                        runCatching { TrackingRecoveryWorker.enqueueReconnect(applicationContext) }
                            .onFailure { Log.w(TAG, "No se pudo encolar recovery de reconexión", it) }
                    }
                    if (ReconnectPolicy.shouldReregisterGps(trackingOn)) {
                        runCatching { TrackingService.start(applicationContext) }
                            .onFailure { Log.w(TAG, "No se pudo pedir re-registro GPS", it) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Reconexión: no se pudo blindar segundo plano", e)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        initCrashReporting()
        // Sin esto, el Worker de recuperación publica en un canal inexistente
        // y el sistema descarta la notificación de actualización en silencio.
        Notifications.ensureChannel(this)
        // Red de seguridad: si el sistema mata el tracking, se recupera solo.
        // SIN RequiredNetwork (ver RecoverySchedule.requiresNetwork): el drenaje
        // offline debe correr también sin red para reactivar GPS y drenar Room.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            TrackingRecoveryWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            TrackingRecoveryWorker.periodicRequest(),
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

    companion object {
        private const val TAG = "DmujeresApp"
    }
}

/**
 * Decisiones puras de reconexión (JVM, sin Android) para `testDebugUnitTest`.
 * Solo se empuja al servicio/worker si la jornada sigue activa: sin jornada no
 * hay nada que re-registrar ni drenar de más (el worker periódico ya cubre el
 * drenaje post-jornada).
 */
object ReconnectPolicy {
    fun shouldReregisterGps(trackingEnabled: Boolean): Boolean = trackingEnabled

    fun shouldExpediteOnReconnect(trackingEnabled: Boolean): Boolean =
        RecoverySchedule.shouldEnqueueOnReconnect(trackingEnabled)
}
