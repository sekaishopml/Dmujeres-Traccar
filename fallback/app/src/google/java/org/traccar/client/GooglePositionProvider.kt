/*
 * Copyright 2019 - 2021 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.client

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class GooglePositionProvider(context: Context, listener: PositionListener) : PositionProvider(context, listener) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    @Volatile
    private var started = false

    /** Estado asumido al arrancar: movimiento (no perder la salida de ruta). */
    @Volatile
    private var moving = true

    override fun startUpdates() {
        started = true
        updateReportInterval(moving)
        requestUpdates()
    }

    override fun stopUpdates() {
        started = false
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    /**
     * Recrea la petición al cambiar el estado: fina y HIGH en movimiento (sin
     * batching, vista fresca), gruesa, BALANCED y con batching en quietud.
     */
    override fun applyMotionState(moving: Boolean) {
        this.moving = moving
        updateReportInterval(moving)
        if (started) requestUpdates()
    }

    @SuppressLint("MissingPermission")
    override fun requestSingleLocation() {
        // Fix FRESCO (no el cacheado): el disparo por giro debe capturar la
        // esquina en el momento, no un punto viejo de hasta 2 minutos.
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                val fix = location
                if (fix != null) {
                    listener.onPositionUpdate(Position(deviceId, fix, getBatteryStatus(context)))
                } else {
                    fusedLocationClient.lastLocation.addOnSuccessListener { last ->
                        if (last != null) {
                            listener.onPositionUpdate(Position(deviceId, last, getBatteryStatus(context)))
                        }
                    }
                }
            }
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        val cadence = AdaptiveCadence.request(
            moving,
            preferences.getString(Prefs.ACCURACY, "high"),
            configuredIntervalSeconds(),
        )
        val locationRequest = LocationRequest.Builder(priorityOf(cadence.accuracy), cadence.intervalMs)
            .setMinUpdateDistanceMeters(cadence.minDistanceM)
            .setMaxUpdateDelayMillis(cadence.maxUpdateDelayMs)
            .setWaitForAccurateLocation(false)
            .build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private val locationCallback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                processLocation(location)
            }
        }
    }

    private fun priorityOf(accuracy: AdaptiveCadence.Accuracy): Int {
        return when (accuracy) {
            AdaptiveCadence.Accuracy.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
            AdaptiveCadence.Accuracy.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            AdaptiveCadence.Accuracy.LOW -> Priority.PRIORITY_LOW_POWER
        }
    }
}
