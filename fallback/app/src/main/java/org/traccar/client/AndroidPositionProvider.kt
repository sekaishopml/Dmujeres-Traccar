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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

class AndroidPositionProvider(context: Context, listener: PositionListener) : PositionProvider(context, listener), LocationListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    private var started = false

    /** Estado asumido al arrancar: movimiento (no perder la salida de ruta). */
    @Volatile
    private var moving = true

    /** Proveedor vigente: GPS en movimiento, red parado (ahorro). */
    @Volatile
    private var provider = LocationManager.GPS_PROVIDER

    override fun startUpdates() {
        started = true
        updateReportInterval(moving)
        requestUpdates()
    }

    override fun stopUpdates() {
        started = false
        locationManager.removeUpdates(this)
    }

    /**
     * Recrea la petición al cambiar el estado: GPS fino en movimiento,
     * NETWORK/BALANCED con 120 s parado. `mobile.accuracy` ya no decide el
     * proveedor (decisión del dueño: GPS en movimiento siempre).
     */
    override fun applyMotionState(moving: Boolean) {
        this.moving = moving
        updateReportInterval(moving)
        if (started) requestUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        val cadence = AdaptiveCadence.request(moving, null, configuredIntervalSeconds())
        provider = if (moving) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
        try {
            locationManager.requestLocationUpdates(provider, cadence.intervalMs, cadence.minDistanceM, this)
        } catch (e: RuntimeException) {
            listener.onPositionError(e)
        }
    }

    @Suppress("DEPRECATION", "MissingPermission")
    override fun requestSingleLocation() {
        try {
            val location = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (location != null) {
                listener.onPositionUpdate(Position(deviceId, location, getBatteryStatus(context)))
            } else {
                locationManager.requestSingleUpdate(provider, object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        listener.onPositionUpdate(Position(deviceId, location, getBatteryStatus(context)))
                    }

                    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }, Looper.myLooper())
            }
        } catch (e: RuntimeException) {
            listener.onPositionError(e)
        }
    }

    override fun onLocationChanged(location: Location) {
        processLocation(location)
    }

    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

}
