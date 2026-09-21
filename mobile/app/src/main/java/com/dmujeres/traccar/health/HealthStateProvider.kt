package com.dmujeres.traccar.health

import android.content.Context
import android.os.PowerManager
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.core.RecoveryOutcome

/**
 * FASE R3: estado de salud multidimensional consolidado con las capas vivas
 * (§7), extraído de `TrackingService`. NUNCA mezcla planos: solo observa, no
 * decide tracking.
 */
class HealthStateProvider(
    private val context: Context,
    private val config: AppConfig,
    private val health: TrackingHealthMonitor,
    private val serviceRunning: () -> Boolean,
    private val networkAvailable: () -> Boolean,
) {

    fun now(): String {
        val screenOn = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.getOrDefault(true)
        return health.state(
            TrackingHealthPolicy.Layers(
                trackingEnabled = config.trackingEnabled,
                serviceRunning = serviceRunning(),
                journeyActive = config.journeyStartAt > 0L,
                nowMs = System.currentTimeMillis(),
                lastCallbackAt = config.lastLocationCallbackAt,
                lastAcceptedAt = config.lastAcceptedAt,
                lastHeartbeatAt = config.journeyElapsedWallMs,
                lastAckAt = config.lastAckAt,
                lastRecoveryAt = config.lastRecoveryAt,
                recoveryPending = config.lastRecoveryResult == RecoveryOutcome.PENDING,
                networkAvailable = networkAvailable(),
                screenOn = screenOn,
            ),
        )
    }
}
