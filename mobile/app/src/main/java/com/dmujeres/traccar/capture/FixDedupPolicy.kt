package com.dmujeres.traccar.capture

import java.util.ArrayDeque
import kotlin.math.roundToLong

/**
 * Dedupe de fixes para L1: el sistema puede re-entregar el mismo fix por
 * PendingIntent (reintentos del FLP) y no debe duplicarse en la cola.
 * Clave: elapsedRealtimeNanos + coordenadas redondeadas a 5 decimales (~1 m).
 */
class FixDedupPolicy(private val window: Int = DEFAULT_WINDOW) {

    /** Clave de dedupe: monotónico del fix + posición redondeada a ~1 m. */
    data class Key(val elapsedNanos: Long, val latE5: Long, val lonE5: Long)

    /** Claves en orden de llegada (para el desalojo FIFO). */
    private val order = ArrayDeque<Key>()

    /** Índice de claves vivas: responde "duplicado" en O(1). */
    private val seen = HashSet<Key>()

    /** true = fix nuevo (y lo recuerda); false = duplicado. */
    fun accept(elapsedRealtimeNanos: Long, latitude: Double, longitude: Double): Boolean {
        val key = Key(
            elapsedNanos = elapsedRealtimeNanos,
            latE5 = latitude.toE5(),
            lonE5 = longitude.toE5(),
        )
        if (!seen.add(key)) return false
        order.addLast(key)
        // Al superar la ventana se desaloja la clave más vieja (FIFO), no la
        // menos usada: un reintento tardío del mismo fix ya no interesa.
        while (order.size > window) {
            seen.remove(order.removeFirst())
        }
        return true
    }

    /** Claves recordadas ahora mismo (tope: [window]). */
    fun size(): Int = seen.size

    /**
     * Redondeo a 5 decimales (~1 m). Coordenadas no finitas (NaN/Inf) caen a
     * un centinela para no romper `roundToLong` ni tumbar el receptor de L1.
     */
    private fun Double.toE5(): Long =
        if (isFinite()) (this * E5).roundToLong() else NON_FINITE_E5

    companion object {
        const val DEFAULT_WINDOW = 64

        /** Escala de 5 decimales: 0.00001° ≈ 1.1 m. */
        private const val E5 = 100_000.0

        /** Centinela para lat/lon no finitas. */
        private const val NON_FINITE_E5 = Long.MIN_VALUE
    }
}
