package com.dmujeres.traccar.tracking

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.core.MqttStatus
import com.dmujeres.traccar.data.AppDatabase
import com.dmujeres.traccar.data.RemoteConfig
import com.dmujeres.traccar.diagnostics.DiagnosticsReporter
import com.dmujeres.traccar.outbox.PositionOutboxDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Puerto Android del refresco manual: delega en los mecanismos EXISTENTES
 * (TrackingService, RemoteConfig, MqttStatus, Outbox, DiagnosticsReporter).
 * No crea servicios, conexiones ni colas nuevas.
 */
class AndroidRefreshPort(private val context: Context) : RefreshPort {

    override suspend fun isNetworkAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(net) ?: return@runCatching false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
    }

    override suspend fun ensureTracking(): RefreshTrackingState {
        val hasJourney = AppConfig(context).journeyStartAt > 0L
        if (!hasJourney) {
            return RefreshTrackingState(hasJourney = false, resumed = false, alreadyRunning = TrackingService.isRunning)
        }
        if (!TrackingService.isRunning) {
            // Regla 8: jornada activa + servicio detenido → reanudación segura
            // con el mecanismo existente (ACTION_START). Nunca se crea otra sesión.
            val requested = TrackingService.start(context)
            return RefreshTrackingState(hasJourney = true, resumed = requested, alreadyRunning = false)
        }
        // Regla 7: si ya está activo NO se detiene.
        return RefreshTrackingState(hasJourney = true, resumed = false, alreadyRunning = true)
    }

    override suspend fun syncConfig(): Boolean =
        runCatching { RemoteConfig.fetch(context) }.getOrDefault(false)

    override suspend fun nudgeGps(): Boolean {
        if (!TrackingService.isRunning) return false
        TrackingService.refresh(context)
        return true
    }

    override suspend fun checkMqtt(): Boolean = MqttStatus.status == MqttStatus.CONNECTED

    override suspend fun drainOutboxSignal(): Int = withContext(Dispatchers.IO) {
        // Señal al mismo wake loop (flushOnce con ACK real); devuelve pendientes.
        PositionOutboxDispatcher.requestFlush()
        runCatching { AppDatabase.getInstance(context).positionDao().count() }.getOrDefault(0)
    }

    override suspend fun sendHealth(): Boolean = runCatching {
        DiagnosticsReporter.report(context, "manual_refresh")
        true
    }.getOrDefault(false)

    override suspend fun requestServerState(): Boolean =
        runCatching { RemoteConfig.fetch(context) }.getOrDefault(false)
}
