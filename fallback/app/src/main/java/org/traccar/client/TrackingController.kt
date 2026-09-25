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

class TrackingController(private val context: Context) :
    PositionListener, NetworkHandler, MotionMonitor.TurnListener {

    private val handler = Handler(Looper.getMainLooper())
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private var positionProvider = PositionProviderFactory.create(context, this)
    private val watchdog = LocationWatchdog()

    /** Cadencia adaptativa: fina en movimiento, gruesa en quietud. */
    private var moving = true
    private var platformFallback = false

    /** Fin del forzado por velocidad (red de seguridad del sensor). */
    private var holdMovingUntilMs = 0L
    private var lastFixLat = 0.0
    private var lastFixLon = 0.0
    private var lastFixAtMs = 0L
    private val databaseHelper = DatabaseHelper(context)
    private val networkManager = NetworkManager(context, this)

    private val url: String = preferences.getString(Prefs.URL, context.getString(R.string.settings_url_default_value))!!
    private val buffer: Boolean = preferences.getBoolean(Prefs.BUFFER, true)

    /** Interruptor del wake lock por envío (la preferencia sigue mandando). */
    private val wakeLockEnabled: Boolean = preferences.getBoolean(Prefs.WAKELOCK, true)

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
        MotionMonitor.setTurnListener(this)
        MotionMonitor.setSignificantMotionListener { onSignificantMotion() }
        watchdog.start(System.currentTimeMillis())
        handler.postDelayed(watchdogTick, WATCHDOG_PERIOD_MS)
        handler.postDelayed(motionTick, MOTION_CHECK_PERIOD_MS)
        networkManager.start()
    }

    /**
     * Cada pocos segundos: al cambiar el estado de movimiento ajusta la
     * petición de ubicaciones (y pide un fix ya al arrancar la ruta).
     */
    private val motionTick = object : Runnable {
        override fun run() {
            // Mientras dure el forzado por velocidad manda la velocidad, no el
            // sensor (si no, un soporte que amortigua deja la cadencia en 120 s).
            val holdActive = System.currentTimeMillis() < holdMovingUntilMs
            val motion = MotionMonitor.isMoving()
            if (!holdActive && motion != null && motion != moving) {
                moving = motion
                positionProvider.applyMotionState(moving)
                Log.i(TAG, "cadencia adaptativa: moviendose=$moving")
                if (moving) {
                    // Arranque de ruta: un fix inmediato en vez de esperar la ventana.
                    runCatching { positionProvider.requestSingleLocation() }
                }
            }
            handler.postDelayed(this, MOTION_CHECK_PERIOD_MS)
        }
    }

    /**
     * Cada minuto: vigilante de GPS (re-solicitar y, si sigue colgado, pasar al
     * GPS del sistema). Nunca inventa posiciones.
     */
    private val watchdogTick = object : Runnable {
        override fun run() {
            when (watchdog.tick(System.currentTimeMillis())) {
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
            positionProvider.applyMotionState(moving)
            positionProvider.startUpdates()
        }.onFailure { Log.w(TAG, "no se pudo activar el GPS del sistema", it) }
    }

    /**
     * Refresco manual (botón ACTUALIZAR del home): reenvía los pendientes al
     * servidor y pide un fix inmediato. Seguro: no reinicia nada.
     */
    fun refreshNow() {
        runCatching {
            if (isOnline) {
                isWaiting = false
                read()
            }
            positionProvider.requestSingleLocation()
        }.onFailure { Log.w(TAG, "refresco manual falló", it) }
    }

    fun stop() {
        networkManager.stop()
        try {
            positionProvider.stopUpdates()
        } catch (e: SecurityException) {
            Log.w(TAG, e)
        }
        MotionMonitor.setTurnListener(null)
        MotionMonitor.setSignificantMotionListener(null)
        MotionMonitor.unregister(context)
        handler.removeCallbacksAndMessages(null)
    }

    override fun onPositionUpdate(position: Position) {
        // La velocidad reportada (nudos) alimenta el detector de giros.
        MotionMonitor.lastSpeedKnots = position.speed
        // Red de seguridad por velocidad real: si el GPS o la distancia entre
        // fixes dicen que el equipo se mueve, se pasa a la cadencia fina ya y
        // se mantiene un rato (el sensor puede no notarlo).
        val now = System.currentTimeMillis()
        val impliedKn = if (lastFixAtMs > 0) {
            val seconds = (now - lastFixAtMs) / 1000.0
            if (seconds > 0) {
                legMeters(lastFixLat, lastFixLon, position.latitude, position.longitude) /
                    seconds * 1.943844
            } else {
                0.0
            }
        } else {
            0.0
        }
        lastFixLat = position.latitude
        lastFixLon = position.longitude
        lastFixAtMs = now
        // El último fix con GPS falso (mock) queda para el diagnostico.
        runCatching {
            preferences.edit().putBoolean(Prefs.LAST_MOCK, position.mock).apply()
        }
        if (MotionSignal.shouldMove(position.speed, impliedKn)) {
            holdMovingUntilMs = now + SPEED_MOVING_HOLD_MS
            if (!moving) {
                moving = true
                positionProvider.applyMotionState(true)
                Log.i(TAG, "movimiento por velocidad (${position.speed} kn / $impliedKn impl)")
            }
        }
        watchdog.noteFix(now)
        StatusActivity.addMessage(context.getString(R.string.status_location_update))
        if (buffer) {
            write(position)
        } else {
            send(position)
        }
    }

    /** Distancia (m) entre dos coordenadas, para la velocidad implícita. */
    private fun legMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    /**
     * Arranque de movimiento por el sensor de bajo consumo (significant motion):
     * adelanta la cadencia fina y pide un fix ya, sin esperar al acelerómetro.
     */
    fun onSignificantMotion() {
        holdMovingUntilMs = System.currentTimeMillis() + SPEED_MOVING_HOLD_MS
        Log.i(TAG, "movimiento por sensor significativo")
        if (!moving) {
            moving = true
            positionProvider.applyMotionState(true)
        }
        runCatching { positionProvider.requestSingleLocation() }
    }

    /** Giro fuerte (giroscopio): captura la esquina sin subir la cadencia base. */
    override fun onTurn() {
        Log.i(TAG, "giro fuerte: fix inmediato y refuerzo a los $TURN_REINFORCE_DELAY_MS ms")
        runCatching { positionProvider.requestSingleLocation() }
        handler.removeCallbacks(turnReinforce)
        handler.postDelayed(turnReinforce, TURN_REINFORCE_DELAY_MS)
    }

    private val turnReinforce = Runnable {
        runCatching { positionProvider.requestSingleLocation() }
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
                        if (result.deviceId == preferences.getString(Prefs.DEVICE, null)) {
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
        // Wake lock SOLO durante el envío: antes era permanente y gastaba
        // 834 mAh/24 h; ahora la CPU se despierta para el POST y se suelta en
        // el callback (éxito o error), con timeout de seguridad de 60 s.
        if (wakeLockEnabled) SendWakeLock.acquire(context)
        runCatching {
            sendRequestAsync(request, object : RequestHandler {
                override fun onComplete(success: Boolean) {
                    if (wakeLockEnabled) SendWakeLock.release()
                    if (success) {
                        ConnectionState.noteSuccess(System.currentTimeMillis())
                        if (buffer) {
                            delete(position)
                        }
                    } else {
                        ConnectionState.noteFailure(System.currentTimeMillis())
                        StatusActivity.addMessage(context.getString(R.string.status_send_fail))
                        if (buffer) {
                            retry()
                        }
                    }
                }
            })
        }.onFailure {
            if (wakeLockEnabled) SendWakeLock.release()
            Log.w(TAG, "no se pudo encolar el envío", it)
        }
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

        /** Revisión del vigilante de GPS (re-solicitud / respaldo AOSP). */
        private const val WATCHDOG_PERIOD_MS = 60_000L

        /** Revisión del estado de sensores para ajustar la cadencia. */
        private const val MOTION_CHECK_PERIOD_MS = 10_000L

        /** Cuánto se mantiene la cadencia fina tras detectar velocidad real. */
        private const val SPEED_MOVING_HOLD_MS = 3 * 60_000L

        /** Refuerzo del fix por giro: uno solo, 3 s después del giro. */
        private const val TURN_REINFORCE_DELAY_MS = 3_000L
    }

}
