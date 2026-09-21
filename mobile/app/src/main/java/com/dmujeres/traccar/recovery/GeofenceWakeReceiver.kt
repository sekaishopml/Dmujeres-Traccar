package com.dmujeres.traccar.recovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.recovery.RescueWindow
import com.dmujeres.traccar.recovery.RescueWindowPolicy
import com.dmujeres.traccar.recovery.SessionKeeper
import com.dmujeres.traccar.tracking.TrackingService

/**
 * R9: receptor de la transición de GEOFENCE de arranque. El sistema (o GMS)
 * lo entrega AUNQUE el proceso esté congelado — es el canal exento por el OS
 * para reactivar el rastreo al arrancar el movimiento.
 *
 * Acción: pedir fix inmediato (vía TrackingService.refresh → one-shot) y
 * reforzar el guardián. Nunca se declara éxito; el fix real es la prueba.
 */
class GeofenceWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_GEOFENCE_WAKE) return
        try {
            val config = AppConfig(context)
            if (!config.trackingEnabled || config.journeyStartAt <= 0L) {
                Log.i(TAG, "Geofence ignorada: jornada inactiva")
                return
            }
            val exiting: Boolean = if (intent.extras?.containsKey("entering") == true) {
                // AOSP (addProximityAlert): LocationManager.KEY_PROXIMITY_ENTERING.
                !intent.getBooleanExtra("entering", true)
            } else {
                // GMS: la transición del evento de geofence.
                val geofencingEvent = GeofencingEvent.fromIntent(intent)
                if (geofencingEvent != null && geofencingEvent.hasError()) {
                    Log.w(TAG, "Geofence error ${geofencingEvent.errorCode}")
                    return
                }
                geofencingEvent?.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT
            }
            if (!exiting) {
                // ENTRAR al radio: aún quieto, nada que hacer.
                return
            }
            Log.i(TAG, "Geofence EXIT → arranque de movimiento (canal exento OS)")
            // Salida de la geocerca = arranque de marcha: ventana de movimiento
            // (sin red, esta ventana es la que sostiene el trazado).
            runCatching { RescueWindow.open(context, RescueWindowPolicy.MOVING_WINDOW_MS) }
            if (TrackingService.isRunning) {
                runCatching { TrackingService.refresh(context) }
            } else {
                // Exención: FGS por transición de geofence (lista oficial del OS).
                val started = TrackingService.start(context)
                Log.i(TAG, "FGS por geofence: ${if (started) "iniciado" else "bloqueado"}")
            }
            runCatching { SessionKeeper.schedule(context, 30_000L) }
            // NO se retira la geocerca: el motor la re-centra con el siguiente
            // fix (chaining) y dejarla registrada permite nuevos avisos si el
            // equipo vuelve a entrar y salir del radio.
        } catch (error: Exception) {
            Log.w(TAG, "GeofenceWake falló", error)
        }
    }

    companion object {
        const val ACTION_GEOFENCE_WAKE = "com.dmujeres.traccar.GEOFENCE_WAKE"
        private const val TAG = "GeofenceWake"
    }
}
