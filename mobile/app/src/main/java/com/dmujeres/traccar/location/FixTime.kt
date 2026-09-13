package com.dmujeres.traccar.location

/**
 * Tiempos de fix puros y testeables (sin Android).
 *
 * Fallbacks de dt/edad, en orden de confianza:
 * 1. elapsedRealtimeNanos (reloj monotónico del fix, robusto a saltos NTP).
 * 2. location.time (wall del fix).
 * 3. null (el llamador decide: omite el cálculo, nunca usa el wall de llegada).
 */
object FixTime {

    /** |dt| mayor que esto (huecos de horas) no sirve para juzgar velocidad. */
    const val MAX_DT_SECONDS = 3600.0

    /**
     * Dt real ENTRE FIJOS en segundos. Con ambos elapsedRealtimeNanos > 0 usa el
     * monotónico (null si dt <= 0 o |dt| > 3600); si no, cae a location.time de
     * ambos (mismas condiciones); si tampoco hay, null. Nunca wall de llegada.
     */
    fun dtSeconds(
        prevElapsedNanos: Long,
        prevTimeMs: Long,
        curElapsedNanos: Long,
        curTimeMs: Long,
    ): Double? {
        if (prevElapsedNanos > 0L && curElapsedNanos > 0L) {
            val dt = (curElapsedNanos - prevElapsedNanos) / 1_000_000_000.0
            return dt.takeIf { it > 0.0 && it <= MAX_DT_SECONDS }
        }
        if (prevTimeMs > 0L && curTimeMs > 0L) {
            val dt = (curTimeMs - prevTimeMs) / 1000.0
            return dt.takeIf { it > 0.0 && it <= MAX_DT_SECONDS }
        }
        return null
    }

    /**
     * Edad del fix en segundos: elapsed válido si ambos > 0 y el resultado es
     * >= 0; fallback (nowWallMs - locationTimeMs)/1000 con las mismas
     * condiciones; si no, null.
     */
    fun ageSeconds(
        locationElapsedNanos: Long,
        nowElapsedNanos: Long,
        locationTimeMs: Long,
        nowWallMs: Long,
    ): Double? {
        if (locationElapsedNanos > 0L && nowElapsedNanos > 0L) {
            val age = (nowElapsedNanos - locationElapsedNanos) / 1_000_000_000.0
            if (age >= 0.0) return age
        }
        if (locationTimeMs > 0L && nowWallMs > 0L) {
            val age = (nowWallMs - locationTimeMs) / 1000.0
            if (age >= 0.0) return age
        }
        return null
    }

    /**
     * Orden de llegada de un lote del FLP como índices sobre la lista original:
     * si TODOS los elapsed > 0 ordena por elapsed (monotónico, inmune a NTP);
     * si no, si TODOS los times > 0 ordena por time; si no, identidad. Estable.
     */
    fun orderIndices(elapsed: List<Long>, times: List<Long>): List<Int> {
        val indices = elapsed.indices.toList()
        if (indices.size < 2) return indices
        return when {
            elapsed.all { it > 0L } -> indices.sortedBy { elapsed[it] }
            times.size == elapsed.size && times.all { it > 0L } -> indices.sortedBy { times[it] }
            else -> indices
        }
    }
}
