package com.dmujeres.traccar.tracking

/**
 * Datos del resumen de cierre de jornada, extraídos de `finishedJourneyText`
 * como decisión pura (JVM). El servicio conserva la lectura de recursos y el
 * reset del estado persistido; aquí solo se decide si hay resumen (jornada
 * abierta) y con qué números.
 */
data class JourneySummary(
    val durationMs: Long,
    val distanceM: Double,
    val points: Long,
    val confirmedPoints: Long,
)

object JourneySummaryPolicy {

    /**
     * `startAtMs <= 0` = no había jornada abierta → sin resumen (el servicio
     * devuelve el texto genérico "jornada finalizada").
     */
    fun from(
        startAtMs: Long,
        elapsedMs: Long,
        distanceM: Double,
        points: Long,
        confirmedPoints: Long,
    ): JourneySummary? =
        if (startAtMs <= 0L) {
            null
        } else {
            JourneySummary(
                durationMs = elapsedMs,
                distanceM = distanceM,
                points = points,
                confirmedPoints = confirmedPoints,
            )
        }
}
