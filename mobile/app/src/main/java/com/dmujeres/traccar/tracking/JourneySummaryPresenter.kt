package com.dmujeres.traccar.tracking

import android.content.Context
import com.dmujeres.traccar.R
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.core.JourneyFormatter
import com.dmujeres.traccar.recovery.SessionKeeper

/**
 * FASE R3: presentación y limpieza del cierre de jornada, extraída de
 * `TrackingService.finishedJourneyText` SIN cambio de comportamiento:
 * - resumen con el elapsed monotónico capturado ANTES de limpiar el acumulador,
 * - resets de jornada (elapsed, distancia, puntos, última posición),
 * - cancelación del guardián de sesión,
 * - texto final de la notificación.
 *
 * Las decisiones puras viven en [JourneySummaryPolicy] y [JourneyFormatter].
 */
class JourneySummaryPresenter(
    private val context: Context,
    private val config: AppConfig,
    private val session: TrackingSessionController,
    /** Resets de referencias del servicio (accepted/bearing) no persistidas. */
    private val resetLegacyRefs: () -> Unit,
) {

    fun summarizeAndReset(): String {
        // El resumen usa el elapsed monotónico capturado ANTES de limpiar el
        // acumulador; así un salto NTP durante la jornada no deforma el cierre.
        // La decisión "hay jornada abierta -> hay resumen" es la política pura.
        val summary = JourneySummaryPolicy.from(
            startAtMs = config.journeyStartAt,
            elapsedMs = session.elapsedNowMs(),
            distanceM = config.journeyDistanceM,
            points = config.journeyPoints,
            confirmedPoints = config.journeyConfirmedPoints,
        )
        config.journeyStartAt = 0
        // La jornada cerró: el guardián de sesión deja de revivir el proceso.
        runCatching { SessionKeeper.cancel(context) }
        config.journeyElapsedMs = 0L
        config.journeyElapsedWallMs = 0L
        config.journeyDistanceM = 0.0
        config.journeyPoints = 0
        config.journeyConfirmedPoints = 0
        config.journeyLastLat = 0.0
        config.journeyLastLon = 0.0
        config.journeyHasLastLocation = false
        resetLegacyRefs()
        if (summary == null) {
            config.journeyStopRequested = false
            return context.getString(R.string.notif_journey_finished)
        }
        val (hours, minutes) = JourneyFormatter.durationParts(summary.durationMs)
        val duration = context.getString(R.string.journey_duration, hours, minutes)
        val km = JourneyFormatter.formatKm(summary.distanceM)
        return if (summary.points > 0) {
            context.getString(
                R.string.notif_journey_finished_body,
                duration, km, summary.points, summary.confirmedPoints,
            )
        } else {
            context.getString(R.string.notif_journey_finished_duration, duration)
        }
    }
}
