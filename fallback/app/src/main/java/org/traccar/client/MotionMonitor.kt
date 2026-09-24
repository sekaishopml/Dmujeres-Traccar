package org.traccar.client

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * Sensores del plan B (acelerómetro + giroscopio) con la lógica de la app
 * nativa: ventana deslizante e histéresis para no flapear.
 *
 * Es SOLO evidencia de movimiento (nunca produce coordenadas): sirve para
 * arrancar la ruta en cuanto el equipo se mueve y para no reportar ruido
 * cuando está quieto.
 *
 * - Desviación estándar de la magnitud del acelerómetro:
 *   < 0.15 m/s² quieto, >= 0.6 en movimiento, en medio conserva el estado.
 * - Giroscopio: se registra SOLO con el equipo en movimiento (ahorro) y su uso
 *   nuevo es detectar giros fuertes ([TurnDetector]) para capturar esquinas.
 */
object MotionMonitor : SensorEventListener {

    enum class State { STATIONARY, MOVING, UNKNOWN }

    /** Aviso de giro fuerte: el controlador pide un fix extra. */
    interface TurnListener {
        fun onTurn()
    }

    private const val TAG = "MotionMonitor"
    private const val WINDOW = 24
    private const val MIN_SAMPLES = 12
    private const val STATIONARY_STD = 0.15
    private const val MOVING_STD = 0.6

    private val magnitudes = ArrayDeque<Double>()
    private val turnDetector = TurnDetector()

    private var sensorManager: SensorManager? = null

    @Volatile
    private var state = State.UNKNOWN

    @Volatile
    private var registered = false

    @Volatile
    private var gyroRegistered = false

    /** Velocidad del último fix reportado (nudos); la usa el detector de giros. */
    @Volatile
    var lastSpeedKnots: Double = 0.0

    @Volatile
    private var turnListener: TurnListener? = null

    /** true/false conocidos; null = sin datos suficientes (honesto). */
    fun isMoving(): Boolean? = when (state) {
        State.MOVING -> true
        State.STATIONARY -> false
        State.UNKNOWN -> null
    }

    fun setTurnListener(listener: TurnListener?) {
        turnListener = listener
    }

    fun register(context: Context) {
        if (registered) return
        runCatching {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            sensorManager = manager
            manager.registerListener(this, manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL)
            registered = true
            updateGyroscope()
            Log.i(TAG, "acelerómetro registrado")
        }.onFailure { Log.w(TAG, "no se pudieron registrar los sensores", it) }
    }

    fun unregister(context: Context) {
        if (!registered) return
        runCatching {
            (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        }
        registered = false
        gyroRegistered = false
        sensorManager = null
        magnitudes.clear()
        state = State.UNKNOWN
        turnDetector.reset()
        turnListener = null
        lastSpeedKnots = 0.0
    }

    /** El giroscopio solo se enciende con el equipo en movimiento (ahorro). */
    private fun updateGyroscope() {
        val manager = sensorManager ?: return
        val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        val movingNow = state == State.MOVING
        if (movingNow && !gyroRegistered) {
            manager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_NORMAL)
            gyroRegistered = true
        } else if (!movingNow && gyroRegistered) {
            manager.unregisterListener(this, gyro)
            gyroRegistered = false
            turnDetector.reset()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAccelerometer(event)
            Sensor.TYPE_GYROSCOPE -> onGyroscope(event)
        }
    }

    private fun onAccelerometer(event: SensorEvent) {
        val magnitude = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2],
        ).toDouble()
        magnitudes.addLast(magnitude)
        if (magnitudes.size > WINDOW) magnitudes.removeFirst()
        if (magnitudes.size < MIN_SAMPLES) return
        val mean = magnitudes.average()
        val variance = magnitudes.sumOf { (it - mean) * (it - mean) } / magnitudes.size
        val std = sqrt(variance)
        state = when {
            std >= MOVING_STD -> State.MOVING
            std <= STATIONARY_STD -> State.STATIONARY
            else -> state // zona de histéresis: se conserva el estado previo
        }
        updateGyroscope()
    }

    private fun onGyroscope(event: SensorEvent) {
        val magnitude = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2],
        ).toDouble()
        // Giro fuerte con el vehículo en marcha: aviso para capturar la esquina.
        if (turnDetector.onSample(Math.toDegrees(magnitude), lastSpeedKnots, System.currentTimeMillis())) {
            turnListener?.onTurn()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
