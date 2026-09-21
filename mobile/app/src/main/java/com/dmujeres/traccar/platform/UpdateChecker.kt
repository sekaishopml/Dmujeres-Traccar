package com.dmujeres.traccar.platform

import android.content.Context
import com.dmujeres.traccar.BuildConfig
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.platform.UpdateManager

/**
 * Chequea la última versión publicada y deja al día la notificación/badge del icono.
 */
object UpdateChecker {

    @Volatile
    private var lastRunAt = 0L

    /** Comprueba el servidor y muestra/oculta la notificación de actualización. */
    suspend fun checkAndRefreshBadge(context: Context) {
        // El Worker puede ejecutarse sin que MainActivity haya abierto nunca:
        // sin canal creado el sistema descarta la notificación en silencio.
        Notifications.ensureChannel(context)
        val now = System.currentTimeMillis()
        if (now - lastRunAt < 60_000L) return
        lastRunAt = now
        val config = AppConfig(context)
        val latest = try {
            // R8: identifica el equipo para que el servidor aplique el rollout.
            UpdateManager.check(config.serverUrl, config.deviceId)
        } catch (e: Exception) {
            null
        }
        config.lastUpdateCheckAt = System.currentTimeMillis()
        if (latest == null) {
            config.lastUpdateLatest = ""
            config.lastUpdateError = UpdateManager.lastError ?: "sin respuesta"
            return
        }
        config.lastUpdateLatest = latest.version
        config.lastUpdateError = ""
        // R7: misma autoridad que MainActivity (versionCode), no solo nombre.
        val decision = OtaVersionPolicy.decide(
            installedCode = BuildConfig.VERSION_CODE,
            latestCode = latest.versionCode,
            latestName = latest.version,
            installedName = BuildConfig.VERSION_NAME,
            minVersionCode = latest.minVersionCode,
        )
        if (decision == OtaVersionPolicy.Decision.UPDATE_AVAILABLE) {
            config.otaState = "AVAILABLE"
            Notifications.updateAvailable(context, latest.version)
        } else {
            Notifications.clearUpdateAvailable(context)
        }
    }
}