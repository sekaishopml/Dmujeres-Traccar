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

    /** Techo del seed legacy en recuperación: 24 h (saltos de reloj no inflan la jornada). */
    const val MAX_SEED_ON_RECOVERY_MS = 24L * 3_600_000L

    /** Horas y minutos de una duración en ms (siempre >= 0). */
    fun durationParts(durationMs: Long): Pair<Long, Long> {
        val safe = durationMs.coerceAtLeast(0)
        return (safe / 3_600_000) to ((safe % 3_600_000) / 60_000)
    }

    /**
     * Duración mostrable inmune a saltos de reloj: elapsed acumulado por el
     * servicio (reloj monotónico, persistido) + gap wall desde SU último
     * anclaje (no desde el inicio de jornada).
     *
     * - startAt <= 0 (sin jornada) → 0.
     * - anchor = persistedWallMs si el servicio ya sincronizó al menos una vez;
     *   si no, journeyStartWallMs (comportamiento legacy de arranque).
     * - resultado = persistedElapsedMs + max(0, nowWallMs - anchor).
     *
     * Nunca negativo: un paso NTP hacia atrás solo acorta el gap (acotado a 0)
     * y uno hacia adelante suma como máximo el salto desde el último anclaje
     * (segundos), no toda la jornada. Puro, testeable en JVM.
     */
    fun displayElapsedMs(
        persistedElapsedMs: Long,
        persistedWallMs: Long,
        journeyStartWallMs: Long,
        nowWallMs: Long,
    ): Long {
        if (journeyStartWallMs <= 0L) return 0L
        val anchor = if (persistedWallMs > 0L) persistedWallMs else journeyStartWallMs
        val elapsed = persistedElapsedMs.coerceAtLeast(0L)
        val gap = (nowWallMs - anchor).coerceAtLeast(0L)
        return elapsed + gap
    }

    /**
     * Seed del elapsed al recuperar una jornada tras muerte del proceso.
     * - persisted > 0 → se respeta el acumulador del servicio.
     * - persisted = 0 y jornada iniciada (legacy, versión sin acumulador) →
     *   naive wall (now - start) acotado a [0, MAX_SEED_ON_RECOVERY_MS].
     * - sin jornada (startAt <= 0) → 0.
     */
    fun seedElapsedOnRecovery(persistedElapsedMs: Long, journeyStartWallMs: Long, nowWallMs: Long): Long =
        when {
            persistedElapsedMs > 0L -> persistedElapsedMs
            journeyStartWallMs <= 0L -> 0L
            else -> (nowWallMs - journeyStartWallMs).coerceIn(0L, MAX_SEED_ON_RECOVERY_MS)
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
