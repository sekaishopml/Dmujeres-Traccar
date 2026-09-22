/*
 * Copyright 2015 - 2021 Anton Tananaev (anton@traccar.org)
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
import org.traccar.client.ProtocolFormatter.formatRequest
import org.traccar.client.RequestManager.sendRequestAsync
import org.traccar.client.PositionProvider.PositionListener
import org.traccar.client.NetworkManager.NetworkHandler
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import android.util.Log
import org.traccar.client.DatabaseHelper.DatabaseHandler
import org.traccar.client.RequestManager.RequestHandler

class TrackingController(private val context: Context) : PositionListener, NetworkHandler {

    private val handler = Handler(Looper.getMainLooper())
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private var positionProvider = PositionProviderFactory.create(context, this)
    private val watchdog = LocationWatchdog()

    /** Cadencia adaptativa: fina en movimiento, base en quietud. */
    private var moving = false
    private var platformFallback = false
    private val databaseHelper = DatabaseHelper(context)
    private val networkManager = NetworkManager(context, this)

    private val url: String = preferences.getString(MainFragment.KEY_URL, context.getString(R.string.settings_url_default_value))!!
    private val buffer: Boolean = preferences.getBoolean(MainFragment.KEY_BUFFER, true)

    private var isOnline = networkManager.isOnline
    private var isWaiting = false

    fun start() {
        if (isOnline) {
            read()
        }
        try {
            positionProvider.startUpdates()
        } catch (e: SecurityException) {
            Log.w(TAG, e)
        }
        MotionMonitor.register(context)
        watchdog.start(System.currentTimeMillis())
        handler.postDelayed(watchdogTick, WATCHDOG_PERIOD_MS)
        networkManager.start()
    }

    /**
     * Cada minuto: cadencia según sensores y vigilante de GPS (re-solicitar y,
     * si sigue colgado, pasar al GPS del sistema). Nunca inventa posiciones.
     */
    private val watchdogTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val motion = MotionMonitor.isMoving()
            if (motion != null && motion != moving) {
                moving = motion
                positionProvider.reportIntervalMs =
                    if (moving) MOVING_REPORT_MS else STATIONARY_REPORT_MS
                Log.i(TAG, "cadencia adaptativa: moviendose=$moving")
                if (moving) {
                    // Arranque de ruta: un fix inmediato en vez de esperar la ventana.
                    runCatching { positionProvider.requestSingleLocation() }
                }
            }
            when (watchdog.tick(now)) {
                LocationWatchdog.Action.RE_REQUEST -> {
                    Log.w(TAG, "GPS sin fix: re-solicitando actualizaciones")
                    runCatching {
                        positionProvider.stopUpdates()
                        positionProvider.startUpdates()
                    }
                }
                LocationWatchdog.Action.FALLBACK -> switchToPlatformProvider()
                LocationWatchdog.Action.NONE -> Unit
            }
            handler.postDelayed(this, WATCHDOG_PERIOD_MS)
        }
    }

    /** Cambia al GPS del sistema (AOSP) cuando el fused no responde. */
    private fun switchToPlatformProvider() {
        if (platformFallback) return
        platformFallback = true
        Log.w(TAG, "GPS del sistema como respaldo (fused sin fixes)")
        StatusActivity.addMessage(context.getString(R.string.status_platform_gps))
        runCatching {
            positionProvider.stopUpdates()
            positionProvider = AndroidPositionProvider(context, this)
            positionProvider.startUpdates()
        }.onFailure { Log.w(TAG, "no se pudo activar el GPS del sistema", it) }
    }

    fun stop() {
        networkManager.stop()
        try {
            positionProvider.stopUpdates()
        } catch (e: SecurityException) {
            Log.w(TAG, e)
        }
        MotionMonitor.unregister(context)
        handler.removeCallbacksAndMessages(null)
    }

    override fun onPositionUpdate(position: Position) {
        watchdog.noteFix(System.currentTimeMillis())
        StatusActivity.addMessage(context.getString(R.string.status_location_update))
        if (buffer) {
            write(position)
        } else {
            send(position)
        }
    }

    override fun onPositionError(error: Throwable) {}
    override fun onNetworkUpdate(isOnline: Boolean) {
        val message = if (isOnline) R.string.status_network_online else R.string.status_network_offline
        StatusActivity.addMessage(context.getString(message))
        if (!this.isOnline && isOnline) {
            read()
        }
        this.isOnline = isOnline
    }

    //
    // State transition examples:
    //
    // write -> read -> send -> delete -> read
    //
    // read -> send -> retry -> read -> send
    //

    private fun log(action: String, position: Position?) {
        var formattedAction: String = action
        if (position != null) {
            formattedAction +=
                    " (id:" + position.id +
                    " time:" + position.time.time / 1000 +
                    " lat:" + position.latitude +
                    " lon:" + position.longitude + ")"
        }
        Log.d(TAG, formattedAction)
    }

    private fun write(position: Position) {
        log("write", position)
        databaseHelper.insertPositionAsync(position, object : DatabaseHandler<Unit?> {
            override fun onComplete(success: Boolean, result: Unit?) {
                if (success) {
                    if (isOnline && isWaiting) {
                        read()
                        isWaiting = false
                    }
                }
            }
        })
    }

    private fun read() {
        log("read", null)
        databaseHelper.selectPositionAsync(object : DatabaseHandler<Position?> {
            override fun onComplete(success: Boolean, result: Position?) {
                if (success) {
                    if (result != null) {
                        if (result.deviceId == preferences.getString(MainFragment.KEY_DEVICE, null)) {
                            send(result)
                        } else {
                            delete(result)
                        }
                    } else {
                        isWaiting = true
                    }
                } else {
                    retry()
                }
            }
        })
    }

    private fun delete(position: Position) {
        log("delete", position)
        databaseHelper.deletePositionAsync(position.id, object : DatabaseHandler<Unit?> {
            override fun onComplete(success: Boolean, result: Unit?) {
                if (success) {
                    read()
                } else {
                    retry()
                }
            }
        })
    }

    private fun send(position: Position) {
        log("send", position)
        val request = formatRequest(url, position)
        sendRequestAsync(request, object : RequestHandler {
            override fun onComplete(success: Boolean) {
                if (success) {
                    if (buffer) {
                        delete(position)
                    }
                } else {
                    StatusActivity.addMessage(context.getString(R.string.status_send_fail))
                    if (buffer) {
                        retry()
                    }
                }
            }
        })
    }

    private fun retry() {
        log("retry", null)
        handler.postDelayed({
            if (isOnline) {
                read()
            }
        }, RETRY_DELAY.toLong())
    }

    companion object {
        private val TAG = TrackingController::class.java.simpleName
        private const val RETRY_DELAY = 30 * 1000

        /** Revisión del vigilante de GPS (y de los sensores). */
        private const val WATCHDOG_PERIOD_MS = 60_000L

        /** Reporte fino mientras hay movimiento (el trazo sigue la vía). */
        private const val MOVING_REPORT_MS = 15_000L

        /** Reporte base en quietud (menos ruido y menos datos). */
        private const val STATIONARY_REPORT_MS = 60_000L
    }

}
