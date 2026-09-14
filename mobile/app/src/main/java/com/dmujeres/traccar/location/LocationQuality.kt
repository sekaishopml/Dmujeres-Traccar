package com.dmujeres.traccar.location

import android.location.Location
import android.os.Build

/**
 * Calidad del fix en el momento de la captura (indicador operacional, no
 * científico). UNKNOWN siempre es null (nunca 0 inventado). Lo consume el
 * envelope (confidence/gnss*) y los logs estructurados.
 */
data class LocationQuality(
    val horizontalAccuracyM: Double?,
    val speedAccuracyMps: Float?,
    val bearingAccuracyDeg: Float?,
    val altitudeAccuracyM: Double?,
    val fixAgeSec: Double?,
    val provider: String,
    val elapsedRealtimeValid: Boolean,
    val mock: Boolean,
    val gnssUsed: Int?,
    val gnssTotal: Int?,
    val lowQuality: Boolean,
    val reason: String?,
    val confidenceScore: Int,
    val qualityClass: QualityClass = QualityClass.GOOD,
) {
    companion object {

        /** UNKNOWN = null SIEMPRE; accuracy solo si > 0. minSdk 26: APIs directas OK. */
        fun from(
            location: Location,
            gnssUsed: Int?,
            gnssTotal: Int?,
            fixAgeSec: Double?,
        ): LocationQuality {
            val horizontal = runCatching { location.accuracy }.getOrNull()
                ?.takeIf { it > 0f }?.toDouble()
            // Android devuelve 0.0 cuando la API no está disponible o el
            // proveedor no aporta el dato: 0 es DESCONOCIDO, nunca un valor.
            val speedAcc = runCatching { location.speedAccuracyMetersPerSecond }.getOrNull()
                ?.takeIf { it.isFinite() && it > 0f }
            val bearingAcc = runCatching { location.bearingAccuracyDegrees }.getOrNull()
                ?.takeIf { it.isFinite() && it > 0f }
            val altitudeAcc = runCatching { location.verticalAccuracyMeters }.getOrNull()
                ?.takeIf { it.isFinite() && it > 0f }?.toDouble()
            val elapsedValid = runCatching { location.elapsedRealtimeNanos > 0L }.getOrDefault(false)
            val mock = readMock(location)
            val provider = location.provider ?: "unknown"
            return LocationQuality(
                horizontalAccuracyM = horizontal,
                speedAccuracyMps = speedAcc,
                bearingAccuracyDeg = bearingAcc,
                altitudeAccuracyM = altitudeAcc,
                fixAgeSec = fixAgeSec,
                provider = provider,
                elapsedRealtimeValid = elapsedValid,
                mock = mock,
                gnssUsed = gnssUsed,
                gnssTotal = gnssTotal,
                lowQuality = false,
                reason = null,
                confidenceScore = calculateConfidenceScore(
                    horizontalAccuracyM = horizontal,
                    speedAccuracyMps = speedAcc,
                    gnssUsableRatio = usableRatio(gnssUsed, gnssTotal),
                    fixAgeSec = fixAgeSec,
                    impliedSpeedMps = null,
                    dopplerValid = false,
                ),
                qualityClass = classify(
                    horizontalAccuracyM = horizontal,
                    fixAgeSec = fixAgeSec,
                    rejected = false,
                    invalidCoords = false,
                ),
            )
        }

        /** isMock (API 31) con fallback a isFromMockProvider (deprec); false si ambos fallan. */
        private fun readMock(location: Location): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { location.isMock }.getOrDefault(false)
            } else {
                @Suppress("DEPRECATION")
                runCatching { location.isFromMockProvider }.getOrDefault(false)
            }

        /** Ratio used/total solo con datos reales (null = desconocido → 0 puntos). */
        fun usableRatio(gnssUsed: Int?, gnssTotal: Int?): Double? {
            if (gnssUsed == null || gnssTotal == null) return null
            if (gnssTotal <= 0) return null
            val used = gnssUsed.coerceIn(0, gnssTotal)
            return used.toDouble() / gnssTotal.toDouble()
        }

        /**
         * Score de confianza 0..100 (pura): indicador operacional simple para
         * telemetría, NO una probabilidad. Pesos:
         * - base 40
         * - accuracy: <=10 → +20, <=30 → +12, <=80 → +5, >80 → -20 (suelo 0)
         * - speedAccuracy: <=2 → +15, <=5 → +8
         * - gnss ratio >= 0.5 → +15 (null → 0)
         * - fixAge: <=30 → +10, <=120 → +5, >120 → -15
         * - doppler válido → +5; implied conocida → +2
         */
        fun calculateConfidenceScore(
            horizontalAccuracyM: Double?,
            speedAccuracyMps: Float?,
            gnssUsableRatio: Double?,
            fixAgeSec: Double?,
            impliedSpeedMps: Float?,
            dopplerValid: Boolean,
        ): Int {
            var score = 40
            when {
                horizontalAccuracyM == null -> Unit
                horizontalAccuracyM <= 10.0 -> score += 20
                horizontalAccuracyM <= 30.0 -> score += 12
                horizontalAccuracyM <= 80.0 -> score += 5
                else -> score -= 20
            }
            when {
                speedAccuracyMps == null -> Unit
                speedAccuracyMps <= 2f -> score += 15
                speedAccuracyMps <= 5f -> score += 8
                else -> Unit
            }
            if (gnssUsableRatio != null && gnssUsableRatio >= 0.5) score += 15
            when {
                fixAgeSec == null -> Unit
                fixAgeSec <= 30.0 -> score += 10
                fixAgeSec <= 120.0 -> score += 5
                else -> score -= 15
            }
            if (dopplerValid) score += 5
            if (impliedSpeedMps != null) score += 2
            return score.coerceIn(0, 100)
        }
    }
}

/**
 * Clasificación INFORMATIVA de la calidad del fix (no cambia decisiones del
 * filtro). Ordenada de peor a mejor con jerarquía EXCELLENT ⊆ GOOD ⊆
 * DEGRADED ⊆ POOR (todo EXCELLENT es también GOOD, etc.).
 */
enum class QualityClass { INVALID, POOR, DEGRADED, GOOD, EXCELLENT }

/**
 * Reglas (orden de evaluación: peor a mejor, primera que aplica):
 * - INVALID: invalidCoords (fuera de lat/lon) || rejected (rechazado por el filtro).
 * - POOR: accuracy null && fixAge > 120 s, o accuracy > 150 m, o fixAge > 300 s.
 * - DEGRADED: accuracy > 30 m (31..150, la banda > 150 ya es POOR), o
 *   fixAge > 120 s. Accuracy null con edad reciente (<= 120 s) = DEGRADED
 *   (calidad DESCONOCIDA, no se asume buena).
 * - GOOD: accuracy <= 30 m && fixAge <= 120 s.
 * - EXCELLENT: accuracy <= 10 m && fixAge <= 30 s.
 * Fronteras inclusivas por <= / >: accuracy 30 → GOOD, 31 → DEGRADED;
 * fixAge 120 → GOOD/DEGRADED según accuracy, 121 → DEGRADED.
 */
fun classify(
    horizontalAccuracyM: Double?,
    fixAgeSec: Double?,
    rejected: Boolean,
    invalidCoords: Boolean,
): QualityClass = when {
    invalidCoords || rejected -> QualityClass.INVALID
    horizontalAccuracyM == null && (fixAgeSec ?: 0.0) > 120.0 -> QualityClass.POOR
    (horizontalAccuracyM ?: 0.0) > 150.0 -> QualityClass.POOR
    (fixAgeSec ?: 0.0) > 300.0 -> QualityClass.POOR
    (horizontalAccuracyM ?: 0.0) > 30.0 -> QualityClass.DEGRADED
    (fixAgeSec ?: 0.0) > 120.0 -> QualityClass.DEGRADED
    horizontalAccuracyM == null -> QualityClass.DEGRADED
    horizontalAccuracyM <= 10.0 && (fixAgeSec ?: 0.0) <= 30.0 -> QualityClass.EXCELLENT
    else -> QualityClass.GOOD
}
