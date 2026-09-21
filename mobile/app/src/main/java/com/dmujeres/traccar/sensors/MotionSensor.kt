package com.dmujeres.traccar.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Acelómetro AUXILIAR (solo observabilidad, NO decisiones de captura).
 * El ZTE Z2450 NO tiene giroscopio ni rotation vector (verificado en
 * auditoría): solo TYPE_ACCELEROMETER (silan sc7a20h). A ~1.5 Hz basta para
 * distinguir parado/movido por la varianza de la magnitud.
 *
 * Ciclo de vida: register() en startTracking, unregister() en stop/onDestroy.
 * Ahorro de batería: si el estado es STATIONARY estable (>= STABLE_MS sin
 * cambio) se desregistra el listener; el tracker lo vuelve a registrar en
 * cada fix aceptado (re-register on fix).
 */
object MotionSensor {

    private const val TAG = "MotionSensor"

    /** Sampling ~1.5 Hz (150 000 us): baja tasa justificada, solo varianza. */
    const val SAMPLING_PERIOD_US = 150_000

    /** Buffer circular de magnitudes ~10 s a 1.5 Hz. */
    const val BUFFER_SAMPLES = 15

    /** Muestras mínimas para clasificar; menos → UNKNOWN. */
    const val MIN_SAMPLES = 6

    /** STATIONARY estable durante este tiempo → desregistrar (batería). */
    const val STABLE_STATIONARY_MS = 5 * 60_000L

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    private var state: MotionState = MotionState.UNKNOWN

    @Volatile
    private var stateChangedAt: Long = 0L

    private val registered = AtomicBoolean(false)
    private val magnitudes = ArrayDeque<Double>()
    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null

    /**
     * null = UNKNOWN o sin datos (sensor ausente, nunca registró, buffer
     * insuficiente). Thread-safe (@Volatile).
     */
    fun isMoving(): Boolean? = when (state) {
        MotionState.MOVING -> true
        MotionState.STATIONARY -> false
        MotionState.UNKNOWN -> null
    }

    fun currentState(): MotionState = state

    /** Registra el listener si el sensor existe. Idempotente. */
    fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sm == null || accel == null) {
                // Sin acelerómetro (o servicio ausente): disponible=false, sin crash.
                available = false
                registered.set(false)
                Log.i(TAG, "Acelómetro no disponible en este dispositivo")
                return
            }
            available = true
            sensorManager = sm
            val l = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
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
                        val next = MotionSensorPolicy.classify(magnitudes.toList())
                        if (next != state) {
                            state = next
                            stateChangedAt = System.currentTimeMillis()
                        }
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            listener = l
            sm.registerListener(l, accel, SAMPLING_PERIOD_US)
        } catch (e: Exception) {
            available = false
            registered.set(false)
            Log.w(TAG, "No se pudo registrar el acelerómetro", e)
        }
    }

    /** Si STATIONARY lleva estable >= STABLE_STATIONARY_MS, desregistra (batería). */
    fun maybePauseIfStationary() {
        if (!registered.get() || !available) return
        val st = state
        val since = stateChangedAt
        if (st == MotionState.STATIONARY &&
            since > 0L && System.currentTimeMillis() - since >= STABLE_STATIONARY_MS
        ) {
            unregisterListener()
            // R8: la pausa debe permitir el re-registro (bug: registered quedaba
            // true y reRegisterOnFix() era no-op para siempre). Durante la pausa
            // NO hay evidencia: el estado honesto es UNKNOWN, no STATIONARY.
            registered.set(false)
            state = MotionState.UNKNOWN
            stateChangedAt = 0L
            Log.i(TAG, "Acelómetro en pausa (STATIONARY estable >= ${STABLE_STATIONARY_MS}ms)")
        }
    }

    /**
     * Re-registrar en cada fix aceptado si estaba desregistrado por pausa
     * (regla del watchdog: lastFixAt avanza → hay que volver a medir).
     */
    fun reRegisterOnFix(context: Context) {
        if (!available || registered.get()) return
        register(context)
    }

    fun unregister() {
        unregisterListener()
        available = false
        registered.set(false)
        synchronized(magnitudes) { magnitudes.clear() }
        state = MotionState.UNKNOWN
        stateChangedAt = 0L
        sensorManager = null
        listener = null
    }

    private fun unregisterListener() {
        val sm = sensorManager
        val l = listener
        if (sm != null && l != null) {
            runCatching { sm.unregisterListener(l) }
                .onFailure { Log.w(TAG, "No se pudo desregistrar el acelerómetro", it) }
        }
        listener = null
    }
}

enum class MotionState { STATIONARY, MOVING, UNKNOWN }

/**
 * Política PURA (JVM): clasifica por la desviación estándar de las magnitudes
 * del acelerómetro respecto a su media. En quieto la magnitud ≈ gravedad con
 * ruido mínimo (< 0.15 m/s²); en movimiento oscila (> 0.6). Entre ambos hay
 * histéresis UNKNOWN (0.15..0.6) para no flapear con micro-jitter.
 * Menos de 6 muestras → UNKNOWN (insuficiente).
 */
object MotionSensorPolicy {

    const val STD_STATIONARY_MAX = 0.15
    const val STD_MOVING_MIN = 0.6
    const val MIN_SAMPLES = 6

    /**
     * @param magnitudes magnitudes por muestra (sqrt(x²+y²+z²)), orden de llegada.
     * @param gravity gravedad local (~9.81); se usa solo de referencia
     * documental: la clasificación depende de la desviación, no del valor
     * absoluto, así un offset de calibración no cambia el veredicto.
     */
    fun classify(
        magnitudes: List<Double>,
        gravity: Double = 9.81,
    ): MotionState {
        if (magnitudes.size < MIN_SAMPLES) return MotionState.UNKNOWN
        val mean = magnitudes.sum() / magnitudes.size
        if (!mean.isFinite()) return MotionState.UNKNOWN
        // Con calibración razonable la media debe rondar la gravedad; si se
        // aparta demasiado (sensor raro), igual la varianza es informativa.
        val variance = magnitudes.sumOf { (it - mean) * (it - mean) } / magnitudes.size
        val stdDev = Math.sqrt(variance)
        return when {
            stdDev < STD_STATIONARY_MAX -> MotionState.STATIONARY
            stdDev > STD_MOVING_MIN -> MotionState.MOVING
            else -> MotionState.UNKNOWN
        }
    }
}
