package com.dmujeres.traccar.util

import android.content.Context
import com.dmujeres.traccar.BuildConfig
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.mqtt.UpdateManager

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
            UpdateManager.check(config.serverUrl)
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
        if (UpdateManager.isNewer(BuildConfig.VERSION_NAME, latest.version)) {
            Notifications.updateAvailable(context, latest.version)
        } else {
            Notifications.clearUpdateAvailable(context)
        }
    }
}