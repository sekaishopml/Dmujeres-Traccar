package com.dmujeres.traccar.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * R9: registro/retiro de la geofence de ARRANQUE (canal exento por el OS para
 * lanzar el servicio en primer plano, técnica de transistorsoft).
 *
 * Proveedor preferido: GeofencingClient de Google (persiste en el proceso del
 * sistema y entrega la transición aunque nuestra app esté congelada). Fallback
 * AOSP: LocationManager.addProximityAlert (misma semántica, sin GMS).
 *
 * Nunca lanza: si el proveedor falla, queda sin geofence (el resto de canales
 * —keeper, FCM, significant motion— siguen).
 */
object MovementGeofence {

    private const val TAG = "MovementGeofence"

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    private var registeredLat: Double? = null

    @Volatile
    private var registeredLon: Double? = null

    @Volatile
    private var provider: String? = null

    fun isRegistered(): Boolean = registeredLat != null

    fun registeredCenter(): Pair<Double, Double>? =
        registeredLat?.let { lat -> registeredLon?.let { lat to it } }

    @SuppressLint("MissingPermission")
    fun register(context: Context, anchor: Location) {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            remove(context)
            val intent = Intent(context, com.dmujeres.traccar.recovery.GeofenceWakeReceiver::class.java)
                .setAction(com.dmujeres.traccar.recovery.GeofenceWakeReceiver.ACTION_GEOFENCE_WAKE)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                9101,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            var providerName: String? = null
            // Preferido: GeofencingClient (GMS) — entrega aunque el proceso muera.
            val geofence = com.google.android.gms.location.Geofence.Builder()
                .setRequestId("dmj-journey-start")
                .setCircularRegion(
                    anchor.latitude, anchor.longitude, MovementGeofencePolicy.RADIUS_M,
                )
                .setExpirationDuration(com.google.android.gms.location.Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_EXIT,
                )
                .build()
            val request = com.google.android.gms.location.GeofencingRequest.Builder()
                .setInitialTrigger(com.google.android.gms.location.GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofence(geofence)
                .build()
            val client = com.google.android.gms.location.LocationServices
                .getGeofencingClient(context)
            runCatching {
                client.addGeofences(request, pendingIntent)
            }.onSuccess {
                providerName = "gms"
            }.onFailure { gmsError ->
                Log.i(TAG, "GeofencingClient no disponible (${gmsError.message}); fallback AOSP")
            }
            if (providerName == null) {
                // Fallback AOSP: proximity alert (sin GMS, p.ej. Honor/HMS).
                val lm = context.getSystemService(LocationManager::class.java)
                if (lm != null) {
                    lm.addProximityAlert(
                        anchor.latitude,
                        anchor.longitude,
                        MovementGeofencePolicy.RADIUS_M,
                        -1L,
                        pendingIntent,
                    )
                    providerName = "aosp"
                }
            }
            provider = providerName
            available = providerName != null
            registeredLat = anchor.latitude
            registeredLon = anchor.longitude
            Log.i(TAG, "Geofence de arranque registrada (${providerName}, ${MovementGeofencePolicy.RADIUS_M}m)")
        }.onFailure {
            Log.w(TAG, "No se pudo registrar la geofence de arranque", it)
            available = false
        }
    }

    fun remove(context: Context) {
        if (registeredLat == null) return
        runCatching {
            val intent = Intent(context, com.dmujeres.traccar.recovery.GeofenceWakeReceiver::class.java)
                .setAction(com.dmujeres.traccar.recovery.GeofenceWakeReceiver.ACTION_GEOFENCE_WAKE)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                910,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val geofence = com.google.android.gms.location.Geofence.Builder()
                .setRequestId("dmj-journey-start")
                .setCircularRegion(
                    registeredLat ?: return,
                    registeredLon ?: return,
                    MovementGeofencePolicy.RADIUS_M,
                )
                .setExpirationDuration(com.google.android.gms.location.Geofence.NEVER_EXPIRE)
                .setTransitionTypes(com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
            val client = com.google.android.gms.location.LocationServices
                .getGeofencingClient(context)
            runCatching { client.removeGeofences(listOf(geofence.requestId)) }
            runCatching {
                val lm = context.getSystemService(LocationManager::class.java)
                lm?.removeProximityAlert(pendingIntent)
            }
            Log.i(TAG, "Geofence de arranque retirada")
        }.onFailure { Log.w(TAG, "No se pudo retirar la geofence", it) }
        registeredLat = null
        registeredLon = null
        provider = null
        available = false
    }
}
