package com.dmujeres.traccar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.location.TrackingService

/**
 * Reinicia el tracking tras el arranque del teléfono (y tras una actualización de la app),
 * solo si el usuario lo había dejado activado. La decisión de tracking la toma el usuario.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_USER_UNLOCKED -> {
                    if (AppConfig(context).trackingEnabled) {
                        val started = TrackingService.start(context)
                        if (!started) {
                            AppConfig(context).lastStartError =
                                "No se pudo arrancar el servicio al encender (revisa permisos)"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppConfig(context).lastStartError = "Error al auto-iniciar: " + (e.message ?: e.javaClass.simpleName)
        }
    }
}
