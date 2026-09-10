package com.dmujeres.traccar.util

import android.content.Context
import com.dmujeres.traccar.R
import java.util.Locale

/**
 * Formato único de jornada/distancia/tiempos relativos.
 *
 * Antes duplicado entre MainActivity (agoText, showJourneySummary,
 * log_journey_on), TrackingService (finishedJourneyText, notif_journey_duration)
 * y DiagnosticsActivity (agoText). Toda la aritmética pura vive aquí para que
 * sea testeable sin Android; los helpers con Context solo aplican strings.xml.
 */
object JourneyFormatter {

    /** Horas y minutos de una duración en ms (siempre >= 0). */
    fun durationParts(durationMs: Long): Pair<Long, Long> {
        val safe = durationMs.coerceAtLeast(0)
        return (safe / 3_600_000) to ((safe % 3_600_000) / 60_000)
    }

    /** Km con un decimal en Locale.US (mismo formato en app y notificaciones). */
    fun formatKm(distanceM: Double): String =
        String.format(Locale.US, "%.1f", distanceM / 1000.0)

    /** Texto de duración usando R.string.journey_duration. */
    fun journeyDuration(context: Context, durationMs: Long): String {
        val (hours, minutes) = durationParts(durationMs)
        return context.getString(R.string.journey_duration, hours, minutes)
    }

    /** "ahora mismo / hace X min / hace X h" usando strings.xml. */
    fun agoText(context: Context, timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = ((now - timestamp).coerceAtLeast(0)) / 60_000
        return when {
            minutes < 1 -> context.getString(R.string.ago_now)
            minutes < 60 -> context.getString(R.string.ago_minutes, minutes)
            else -> context.getString(R.string.ago_hours, minutes / 60)
        }
    }

    /** Resumen persistido "duration|km|points|confirmed" (formato de AppConfig). */
    fun buildSummary(duration: String, km: String, points: Long, confirmedPoints: Long): String =
        "$duration|$km|$points|$confirmedPoints"
}
