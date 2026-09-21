package com.dmujeres.traccar.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * FASE R3: observadores de conectividad extraídos de `TrackingService` SIN
 * cambio de comportamiento:
 * - receiver dinámico de modo avión (NUNCA en el manifest),
 * - `registerDefaultNetworkCallback` (onAvailable/onLost/onCapabilitiesChanged).
 *
 * El observador DETECTA y persiste la foto de red; las reacciones (presencia,
 * reconexión MQTT inmediata, drenaje del outbox, refresco de estado) las
 * decide el servicio por callbacks: aquí no hay decisión de tracking.
 */
class ConnectivityObserver(
    private val context: Context,
    private val sampler: DeviceTelemetrySampler,
    private val scopeProvider: () -> CoroutineScope,
    /** started && !stopping: la presencia solo se emite con tracking vivo. */
    private val isActive: () -> Boolean,
    /** Encola presencia (el servicio la lanza en su scope). */
    private val onPresenceRequested: () -> Unit,
    /** Red validada: vía inmediata de reconexión MQTT + drenaje del outbox. */
    private val onNetworkValidated: () -> Unit,
    /** Refresco de notificación/estado (modo avión y eventos de red). */
    private val onRefreshState: () -> Unit,
) {

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var airplaneReceiver: BroadcastReceiver? = null

    fun start() {
        registerNetworkCallback()
        registerAirplaneReceiver()
    }

    fun stop() {
        networkCallback?.let {
            runCatching {
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
        unregisterAirplaneReceiver()
    }

    private fun registerAirplaneReceiver() {
        if (airplaneReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_AIRPLANE_MODE_CHANGED) return
                if (!isActive()) {
                    onRefreshState()
                    return
                }
                scopeProvider().launch {
                    try {
                        val shot = sampler.snapshot()
                        sampler.persistNetState(shot, sampler.cause(shot))
                        onPresenceRequested()
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo refrescar presencia por modo avión", e)
                    }
                    onRefreshState()
                }
            }
        }
        airplaneReceiver = receiver
        try {
            val filter = IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo registrar el receiver de modo avión", e)
            airplaneReceiver = null
        }
    }

    private fun unregisterAirplaneReceiver() {
        val receiver = airplaneReceiver ?: return
        airplaneReceiver = null
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun registerNetworkCallback() {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val shot = runCatching {
                    val s = sampler.snapshot()
                    sampler.persistNetState(s, sampler.cause(s))
                    Log.i(TAG, "Red disponible netCause=${sampler.cause(s).value} validated=${s.validated}")
                    s
                }.getOrNull()
                // Señal, no verdad: sin VALIDATED no hay Internet real (captive /
                // WiFi sin salida). No se intenta MQTT ni se drena (fallarían y
                // quemarían backoff); igual se encola presencia para que el
                // servidor vea network=none. La verdad la pone el watchdog con
                // LinkState (validated + mqtt + reachability).
                // Si la foto falla (shot null) se sigue el camino optimista,
                // igual que antes de la extracción.
                if (shot != null && !shot.validated) {
                    if (isActive()) onPresenceRequested()
                    return
                }
                // Vía inmediata de la puerta: vuelta de red validada.
                onNetworkValidated()
                if (isActive()) onPresenceRequested()
            }

            override fun onLost(network: android.net.Network) {
                runCatching {
                    val shot = sampler.snapshot()
                    val cause = sampler.cause(shot)
                    sampler.persistNetState(shot, cause)
                    Log.i(TAG, "Red perdida netCause=${cause.value}")
                }
                // Red perdida: enviar presencia inmediata con network=none
                if (isActive()) onPresenceRequested()
            }

            override fun onCapabilitiesChanged(
                network: android.net.Network,
                capabilities: android.net.NetworkCapabilities,
            ) {
                // Detectar cambio de tipo de red (wifi ↔ mobile)
                if (!isActive()) return
                val hasWifi = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                val hasMobile = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                val currentNetwork = when {
                    hasWifi -> NetworkStatePolicy.LABEL_WIFI
                    hasMobile -> NetworkStatePolicy.LABEL_MOBILE
                    else -> "other"
                }
                if (sampler.reportedNetwork.isNotEmpty() && currentNetwork != sampler.reportedNetwork) {
                    runCatching {
                        val shot = sampler.snapshot()
                        sampler.persistNetState(shot, sampler.cause(shot))
                    }
                    onPresenceRequested()
                }
                sampler.noteReportedNetwork(currentNetwork)
            }

            override fun onBlockedStatusChanged(network: android.net.Network, blocked: Boolean) {
                // Defensivo: solo telemetría, sin cambiar lógica de envío.
                Log.i(TAG, "onBlockedStatusChanged blocked=$blocked")
            }
        }
        networkCallback = callback
        try {
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo registrar el callback de red", e)
            networkCallback = null
        }
    }

    private companion object {
        const val TAG = "ConnectivityObserver"
    }
}
