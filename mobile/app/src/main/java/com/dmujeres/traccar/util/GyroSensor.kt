package com.dmujeres.traccar.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Giroscopio OPCIONAL (Fase 12): espejo de [MotionSensor], solo
 * observabilidad (NO decisiones de captura). En equipos sin
 * TYPE_GYROSCOPE (p. ej. ZTE Z2450) queda available=false sin error y no se
 * registra nada en los POSITION_*.
 *
 * Mide la magnitud del vector de velocidad angular (rad/s). Umbrales de la
 * política INICIALES y observacionales: en un dispositivo realmente en
 * rotación CONSTANTE la desviación puede ser baja (límite conocido del
 * criterio por varianza); servirá para distinguir quieto vs. agitado, no
 * para medir rotación sostenida.
 *
 * Ciclo de vida: register() en startTracking, unregister() en stop/onDestroy.
 * Sin pausa por quietud (a diferencia del acelerómetro, el giroscopio solo
 * alimenta el tag rot= de los logs).
 */
object GyroSensor {

    private const val TAG = "GyroSensor"

    /** Sampling ~1.5 Hz (150 000 us): igual que el acelerómetro. */
    const val SAMPLING_PERIOD_US = 150_000

    /** Buffer circular de magnitudes ~10 s a 1.5 Hz. */
    const val BUFFER_SAMPLES = 15

    /** Muestras mínimas para clasificar; menos → UNKNOWN. */
    const val MIN_SAMPLES = 6

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    private var state: GyroState = GyroState.UNKNOWN

    private val registered = AtomicBoolean(false)
    private val magnitudes = ArrayDeque<Double>()
    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null

    /** Estado vigente (UNKNOWN si no hay datos suficientes). Thread-safe. */
    fun currentState(): GyroState = state

    /** Registra el listener si el sensor existe. Idempotente. Nunca lanza. */
    fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            if (sm == null || gyro == null) {
                // Sin giroscopio (o servicio ausente): disponible=false, sin crash.
                available = false
                registered.set(false)
                Log.i(TAG, "Giroscopio no disponible en este dispositivo")
                return
            }
            available = true
            sensorManager = sm
            val l = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor?.type != Sensor.TYPE_GYROSCOPE) return
                    val values = event.values
                    if (values.size < 3) return
                    val mag = Math.sqrt(
                        values[0].toDouble() * values[0] +
                            values[1].toDouble() * values[1] +
                            values[2].toDouble() * values[2],
                    )
                    if (!mag.isFinite()) return
                    synchronized(magnitudes) {
                        magnitudes.addLast(mag)
                        if (magnitudes.size > BUFFER_SAMPLES) magnitudes.removeFirst()
                        state = GyroSensorPolicy.classify(magnitudes.toList())
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            listener = l
            sm.registerListener(l, gyro, SAMPLING_PERIOD_US)
        } catch (e: Exception) {
            available = false
            registered.set(false)
            Log.w(TAG, "No se pudo registrar el giroscopio", e)
        }
    }

    fun unregister() {
        val sm = sensorManager
        val l = listener
        if (sm != null && l != null) {
            runCatching { sm.unregisterListener(l) }
                .onFailure { Log.w(TAG, "No se pudo desregistrar el giroscopio", it) }
        }
        listener = null
        available = false
        registered.set(false)
        synchronized(magnitudes) { magnitudes.clear() }
        state = GyroState.UNKNOWN
        sensorManager = null
    }
}

enum class GyroState { STEADY, ROTATING, UNKNOWN }

/**
 * Política PURA (JVM): clasifica por la desviación estándar de las magnitudes
 * del giroscopio (rad/s). Umbral STEADY < 0.02 rad/s (~1.1 °/s de jitter) y
 * ROTATING > 0.35 rad/s, INICIALES y observacionales: pueden calibrarse con
 * datos de campo sin cambiar la forma de la regla. Entre ambos, histéresis
 * UNKNOWN. Menos de 6 muestras → UNKNOWN (insuficiente).
 */
object GyroSensorPolicy {

    const val STD_STEADY_MAX = 0.02
    const val STD_ROTATING_MIN = 0.35
    const val MIN_SAMPLES = 6

    /**
     * @param magnitudes magnitudes del vector angular (sqrt(x²+y²+z²)) en
     *   rad/s, en orden de llegada.
     */
    fun classify(magnitudes: List<Double>): GyroState {
        if (magnitudes.size < MIN_SAMPLES) return GyroState.UNKNOWN
        val mean = magnitudes.sum() / magnitudes.size
        if (!mean.isFinite()) return GyroState.UNKNOWN
        val variance = magnitudes.sumOf { (it - mean) * (it - mean) } / magnitudes.size
        val stdDev = Math.sqrt(variance)
        return when {
            stdDev < STD_STEADY_MAX -> GyroState.STEADY
            stdDev > STD_ROTATING_MIN -> GyroState.ROTATING
            else -> GyroState.UNKNOWN
        }
    }
}
