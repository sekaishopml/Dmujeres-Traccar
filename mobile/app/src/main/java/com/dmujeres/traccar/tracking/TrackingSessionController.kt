package com.dmujeres.traccar.tracking

import android.os.SystemClock
import android.util.Log
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.diagnostics.DiagnosticsCollector
import com.dmujeres.traccar.core.JourneyFormatter
import com.dmujeres.traccar.platform.SentryLog

/**
 * FASE 3 (§3): controlador de sesión/jornada extraído de `TrackingService`
 * SIN cambio de comportamiento. Es dueño del paso del tiempo de la jornada:
 *
 * - arranque (`begin`): id de sesión nuevo, bootId, ancla monotónica y
 *   siembra del elapsed persistido en recuperación,
 * - duración (`elapsedNowMs`/`persistJourneyElapsed`) con reloj monotónico
 *   (inmune a pasos NTP; centinela 0 = aún sin arraigar),
 * - detección de paso de reloj (wall vs monotónico, umbral
 *   [DiagnosticsCollector.CLOCK_STEP_THRESHOLD_MS]) con contador diario.
 */
class TrackingSessionController(private val config: AppConfig) {

    @Volatile var startedTrackingAt: Long = 0L
        private set

    /**
     * Acumulador monotónico de la duración de jornada (inmune a saltos NTP):
     * elapsed de la última sesión + base de elapsedRealtime al arrancar.
     * monoBaseElapsed == 0 es centinela de "aún no corrió begin".
     */
    @Volatile private var monoBasePersisted = 0L
    @Volatile private var monoBaseElapsed = 0L

    /** Anclaje rodante (wall + elapsedRealtime) para [maybeDetectClockStep]. */
    @Volatile private var clockAnchorWallMs = 0L
    @Volatile private var clockAnchorMonoMs = 0L

    /**
     * Arranque de la ejecución lógica: nuevo sessionId (agrupa fixes en el
     * servidor), bootId refrescado y anclas del reloj. `recoveringJourney`
     * hereda el inicio y el elapsed persistidos.
     */
    fun begin(recoveringJourney: Boolean) {
        startedTrackingAt = if (recoveringJourney) config.journeyStartAt else System.currentTimeMillis()
        // El único reloj digno de confianza mientras el servicio vive es el
        // monotónico: se arraiga en elapsedRealtime y parte del elapsed
        // persistido (o del seed legacy acotado si nunca se persistió).
        monoBasePersisted = if (recoveringJourney) {
            JourneyFormatter.seedElapsedOnRecovery(
                config.journeyElapsedMs, config.journeyStartAt, System.currentTimeMillis(),
            )
        } else {
            0L
        }
        monoBaseElapsed = SystemClock.elapsedRealtime()
        clockAnchorWallMs = 0L
        clockAnchorMonoMs = 0L
        runCatching { config.newSessionId() }
        runCatching { config.bootIdRefresh(SystemClock.elapsedRealtime()) }
    }

    /**
     * Duración de jornada según el reloj monotónico del servicio: base
     * persistida (o sembrada en recuperación) + tiempo desde que se arraigó.
     * Con centinela sin arraigar cae al valor persistido + gap desde su ancla,
     * nunca al wall bruto desde el inicio (inmune a pasos NTP en ambos sentidos).
     */
    fun elapsedNowMs(): Long {
        val base = monoBaseElapsed
        return if (base == 0L) {
            JourneyFormatter.displayElapsedMs(
                persistedElapsedMs = config.journeyElapsedMs,
                persistedWallMs = config.journeyElapsedWallMs,
                journeyStartWallMs = config.journeyStartAt,
                nowWallMs = System.currentTimeMillis(),
            )
        } else {
            (monoBasePersisted + (SystemClock.elapsedRealtime() - base)).coerceAtLeast(0L)
        }
    }

    /** Fija el par (elapsed monotónico, ancla wall) que lee la UI y otras sesiones. */
    fun persistJourneyElapsed() {
        config.journeyElapsedMs = elapsedNowMs()
        config.journeyElapsedWallMs = System.currentTimeMillis()
    }

    /**
     * Paso de reloj (NTP/zona/manual) durante la jornada: en la misma
     * ventana, el delta del wall clock se desvía del monotónico más de
     * [DiagnosticsCollector.CLOCK_STEP_THRESHOLD_MS]. Al detectar:
     * clockSteps24h++ (bucket diario) + breadcrumb + log.
     */
    fun maybeDetectClockStep() {
        val nowWall = System.currentTimeMillis()
        val nowMono = SystemClock.elapsedRealtime()
        if (clockAnchorMonoMs == 0L) {
            clockAnchorWallMs = nowWall
            clockAnchorMonoMs = nowMono
            return
        }
        val wallDiff = nowWall - clockAnchorWallMs
        val monoDiff = nowMono - clockAnchorMonoMs
        clockAnchorWallMs = nowWall
        clockAnchorMonoMs = nowMono
        if (DiagnosticsCollector.clockStepDelta(wallDiff, monoDiff)) {
            runCatching { config.incClockStep24h() }
            Log.w(TAG, "Paso de reloj: wall=$wallDiff ms monotónico=$monoDiff ms")
            runCatching {
                SentryLog.breadcrumb("diag", "clock_step", "Salto de reloj ${wallDiff - monoDiff} ms")
            }
        }
    }

    private companion object {
        const val TAG = "TrackingSession"
    }
}
