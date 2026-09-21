package com.dmujeres.traccar.location

/**
 * FASE 8 (§9): fusión de evidencia GPS + sensores. PURA (JVM, sin Android).
 *
 * Entrada: edad/accuracy del último fix, estado de movimiento del acelerómetro,
 * estado del giroscopio (opcional) y velocidad efectiva.
 * Salida: salud del GPS, probabilidad de movimiento y necesidad de
 * re-adquisición agresiva.
 *
 * REGLA ABSOLUTA: esta clase NO produce latitud/longitud ni hace dead
 * reckoning. Un "MOVEMENT_LIKELY" sin GPS solo autoriza re-intentar adquirir
 * satélites; jamás inventa una posición.
 *
 * Sin sensores opcionales (giroscopio ausente) degrada con honestidad: usa
 * acelerómetro y velocidad; si tampoco hay evidencia → UNKNOWN.
 */
object LocationSensorFusion {

    /** Ventana de fix fresco para GPS_HEALTHY. */
    const val FRESH_FIX_MS = 60_000L

    /** Ventana de fix degradado (aún sirve, con cautela). */
    const val STALE_FIX_MS = 5 * 60_000L

    /** Accuracy considerada buena. */
    const val GOOD_ACCURACY_M = 50.0

    /** Velocidad mínima de movimiento (m/s) coherente con stop detection. */
    const val MOVING_SPEED_MPS = 1.5f

    const val GPS_HEALTHY = "GPS_HEALTHY"
    const val GPS_DEGRADED = "GPS_DEGRADED"
    const val GPS_LOST = "GPS_LOST"

    const val MOVEMENT_LIKELY = "MOVEMENT_LIKELY"
    const val STATIONARY_LIKELY = "STATIONARY_LIKELY"
    const val MOTION_UNKNOWN = "MOTION_UNKNOWN"

    data class Input(
        /** Edad del último fix aceptado (ms); null = nunca hubo fix. */
        val fixAgeMs: Long?,
        val accuracyM: Double?,
        /** MotionSensor.currentState(): STATIONARY|MOVING|UNKNOWN. */
        val motionState: String,
        /** GyroSensor.currentState(): STEADY|ROTATING|UNKNOWN (o null sin sensor). */
        val gyroState: String?,
        val speedMps: Float?,
    )

    data class Output(
        val gpsHealth: String,
        val movement: String,
        val reacquisitionRequired: Boolean,
        val reason: String,
    )

    fun fuse(input: Input): Output {
        val gpsHealth = when {
            input.fixAgeMs == null -> GPS_LOST
            input.fixAgeMs <= FRESH_FIX_MS &&
                (input.accuracyM == null || input.accuracyM <= GOOD_ACCURACY_M) -> GPS_HEALTHY
            input.fixAgeMs <= STALE_FIX_MS -> GPS_DEGRADED
            else -> GPS_LOST
        }
        val motionMoving = input.motionState == "MOVING"
        val gyroMoving = input.gyroState == "ROTATING"
        val speedMoving = (input.speedMps ?: 0f) >= MOVING_SPEED_MPS
        val sensorStationary = input.motionState == "STATIONARY" &&
            (input.gyroState == null || input.gyroState == "STEADY") &&
            (input.speedMps == null || input.speedMps < MOVING_SPEED_MPS)
        val movement = when {
            motionMoving || gyroMoving || speedMoving -> MOVEMENT_LIKELY
            sensorStationary -> STATIONARY_LIKELY
            else -> MOTION_UNKNOWN
        }
        val reacquire = gpsHealth != GPS_HEALTHY && movement == MOVEMENT_LIKELY
        val reason = when {
            reacquire && input.fixAgeMs == null -> "no_fix_with_movement"
            reacquire -> "stale_fix_with_movement"
            gpsHealth == GPS_HEALTHY -> "fix_fresh"
            movement == STATIONARY_LIKELY -> "gps_lost_stationary"
            else -> "insufficient_evidence"
        }
        return Output(gpsHealth, movement, reacquire, reason)
    }

    /** Exactamente el mismo cálculo que [fuse] pero desde datos de telemetría crudos. */
    fun fromTelemetry(
        lastFixAtMs: Long,
        nowMs: Long,
        accuracyM: Double?,
        motionState: String,
        gyroAvailable: Boolean,
        gyroState: String?,
        speedMps: Float?,
    ): Output = fuse(
        Input(
            fixAgeMs = if (lastFixAtMs > 0L) (nowMs - lastFixAtMs).coerceAtLeast(0L) else null,
            accuracyM = accuracyM,
            motionState = motionState,
            gyroState = if (gyroAvailable) gyroState else null,
            speedMps = speedMps,
        ),
    )
}
