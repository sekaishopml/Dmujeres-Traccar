/*
 * Copyright 2013 - 2022 Anton Tananaev (anton@traccar.org)
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

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.location.Location
import android.os.BatteryManager
import androidx.preference.PreferenceManager
import android.util.Log
import kotlin.math.abs

abstract class PositionProvider(
    protected val context: Context,
    protected val listener: PositionListener,
) {

    interface PositionListener {
        fun onPositionUpdate(position: Position)
        fun onPositionError(error: Throwable)
    }

    protected var preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    protected var deviceId = preferences.getString(MainFragment.KEY_DEVICE, "undefined")!!
    protected var interval = preferences.getString(MainFragment.KEY_INTERVAL, "60")!!.toLong() * 1000
    protected var distance: Double = preferences.getString(MainFragment.KEY_DISTANCE, "50")!!.toInt().toDouble()
    protected var angle: Double = preferences.getString(MainFragment.KEY_ANGLE, "15")!!.toInt().toDouble()
    private var lastLocation: Location? = null

    abstract fun startUpdates()
    abstract fun stopUpdates()
    abstract fun requestSingleLocation()

    protected fun processLocation(location: Location?) {
        val lastLocation = this.lastLocation
        // Guardas anti-ruido para el filtro de ángulo: quieto, el rumbo del GPS
        // salta aleatoriamente y sin estas condiciones se enviaba un punto en
        // cada fix (telaraña en el replay con el equipo detenido).
        val leg = if (lastLocation != null && location != null) {
            location.distanceTo(lastLocation).toDouble()
        } else {
            0.0
        }
        val dtSeconds = if (lastLocation != null && location != null) {
            (location.time - lastLocation.time) / 1000.0
        } else {
            0.0
        }
        val impliedSpeed = if (dtSeconds > 0) leg / dtSeconds else 0.0
        if (location != null &&
            (lastLocation == null || location.time - lastLocation.time >= interval || distance > 0
                    && leg >= distance || angle > 0
                    && leg >= ANGLE_MIN_LEG_M && impliedSpeed >= ANGLE_MIN_SPEED_MPS
                    && abs(location.bearing - lastLocation.bearing) >= angle)
        ) {
            Log.i(TAG, "location new")
            this.lastLocation = location
            listener.onPositionUpdate(Position(deviceId, location, getBatteryStatus(context)))
        } else {
            Log.i(TAG, if (location != null) "location ignored" else "location nil")
        }
    }

    protected fun getBatteryStatus(context: Context): BatteryStatus {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 1)
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            return BatteryStatus(
                level = level * 100.0 / scale,
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            )
        }
        return BatteryStatus()
    }

    companion object {
        private val TAG = PositionProvider::class.java.simpleName
        const val MINIMUM_INTERVAL: Long = 1000

        /** Pata mínima (m) para que un giro cuente como reporte (filtra jitter). */
        const val ANGLE_MIN_LEG_M = 12.0

        /** Velocidad implícita mínima (m/s) para que el giro cuente. */
        const val ANGLE_MIN_SPEED_MPS = 1.5
    }

}
