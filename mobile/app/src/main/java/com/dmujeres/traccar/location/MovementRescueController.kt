package com.dmujeres.traccar.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R5: controlador runtime del rescate de localización. Única responsabilidad:
 * cuando la política lo pide (GPS degradado/lost + movimiento), activar UNA
 * ventana temporal de reacquisición GNSS con acciones reales del motor:
 *
 *   onRescueTick()  →  [LocationEngine.nudgeRefresh() + requestOneShotFix()]
 *
 * INVARIANTES:
 *  - NO produce nunca un Location (los fixes llegan solo por el pipeline real).
 *  - single-flight (una sola ventana de rescate simultánea),
 *  - cooldown entre rescates ([MovementRescuePolicy.RESCUE_COOLDOWN_MS]),
 *  - duración máxima ([MovementRescuePolicy.RESCUE_DURATION_MS]),
 *  - no corre si no hay jornada; se cancela con el scope del servicio.
 *
 * La evidencia (motion/desplazamiento) proviene de MotionSensor (histéresis
 * propia, no flapea con un pico) y del ancla estacionaria ya existente.
 */
class MovementRescueController(
    // R8: provider, no valor — el servicio puede recrear su scope en el camino
    // pendingStart (stop+start en la misma instancia) y el rescate debe usar el
    // scope VIVO, no el cancelado.
    private val scopeProvider: () -> CoroutineScope,
    private val deps: Deps,
) {

    data class Deps(
        /** jornada activa (config.journeyStartAt > 0). */
        val journeyActive: () -> Boolean,
        /** ms del último fix aceptado o null. */
        val lastFixAtMs: () -> Long?,
        /** MotionSensor.currentState() == MOVING. */
        val sensorMoving: () -> Boolean,
        /** desplazamiento real desde el ancla (m) o null. */
        val displacementM: () -> Float?,
        /** acciones reales de reacquisición (nudge + one-shot). */
        val onRescueActions: () -> Unit,
        /** telemetría por la vía existente (health snapshots/breadcrumbs). */
        val onEvent: (eventType: String, reason: String, durationMs: Long?) -> Unit,
    )

    @Volatile var state: MovementRescuePolicy.RescueState = MovementRescuePolicy.RescueState.NORMAL
        private set
    @Volatile private var rescueJob: Job? = null
    @Volatile private var lastRescueAtMs = 0L
    private var movementStreak = 0
    private val rescuing = AtomicBoolean(false)

    fun start() {
        scopeProvider().launch {
            while (isActive) {
                tick()
                delay(15_000L)
            }
        }
    }

    /** Tick del observador (15 s): aplica la política pura y acciona el rescate. */
    fun tick(nowMs: Long = System.currentTimeMillis()) {
        val input = MovementRescuePolicy.Input(
            journeyActive = deps.journeyActive(),
            fixAgeMs = deps.lastFixAtMs()?.let { nowMs - it },
            sensorMoving = deps.sensorMoving(),
            displacementM = deps.displacementM(),
            rescueActive = rescuing.get(),
            movementStreak = movementStreak,
            lastRescueAtMs = lastRescueAtMs.takeIf { it > 0L },
            nowMs = nowMs,
        )
        val previous = state
        val decision = MovementRescuePolicy.decide(previous, input)
        state = decision.state
        movementStreak = if (decision.state == MovementRescuePolicy.RescueState.MOVEMENT_CANDIDATE) {
            movementStreak + 1
        } else {
            0
        }
        if (decision.rescueRequested && previous != MovementRescuePolicy.RescueState.RESCUE) {
            startRescue(decision.reason)
        }
    }

    /** Fix real aceptado por el pipeline: la política vuelve a NORMAL. */
    fun onRealFix(nowMs: Long = System.currentTimeMillis()) {
        movementStreak = 0
        if (state == MovementRescuePolicy.RescueState.RESCUE) {
            cancelRescue()
            state = MovementRescuePolicy.RescueState.FIX_RECOVERED
            deps.onEvent("GPS_RESCUE_FIX_RECOVERED", "real-fix", durationMs(nowMs))
        }
        state = MovementRescuePolicy.RescueState.NORMAL
    }

    private fun startRescue(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRescueAtMs < MovementRescuePolicy.RESCUE_COOLDOWN_MS) return // cooldown
        if (!rescuing.compareAndSet(false, true)) return // single-flight
        lastRescueAtMs = now
        // Primer nudge inmediato y síncrono (nudge + one-shot, vías reales).
        deps.onRescueActions()
        deps.onEvent("GPS_RESCUE_STARTED", reason, null)
        rescueJob = scopeProvider().launch {
            val startedAt = System.currentTimeMillis()
            try {
                while (System.currentTimeMillis() - startedAt < MovementRescuePolicy.RESCUE_DURATION_MS) {
                    deps.onRescueActions()
                    delay(5_000L) // tick del burst cada 5 s (acotado a 90 s)
                }
                deps.onEvent("GPS_RESCUE_TIMEOUT", "sin-fix-en-${MovementRescuePolicy.RESCUE_DURATION_MS}", MovementRescuePolicy.RESCUE_DURATION_MS)
            } finally {
                rescuing.set(false)
            }
        }
    }

    private fun cancelRescue() {
        rescueJob?.cancel()
        rescueJob = null
        rescuing.set(false)
    }

    fun stop() {
        cancelRescue()
        state = MovementRescuePolicy.RescueState.NORMAL
    }

    private fun durationMs(nowMs: Long): Long? = lastRescueAtMs.takeIf { it > 0 }?.let { nowMs - it }
}
