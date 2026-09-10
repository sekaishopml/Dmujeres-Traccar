package com.dmujeres.traccar.util

/**
 * EWMA thread-safe del RTT de aplicación (publish → ACK). Alpha 0.3: el primer valor
 * fija la media inicial y cada muestra posterior la desplaza un 30 % hacia el nuevo dato.
 */
object RttMeter {

    private const val ALPHA = 0.3

    private val lock = Any()
    private var initialized = false
    private var ewmaMs = 0.0

    /** Registra una muestra y devuelve el EWMA actualizado (-1 si la muestra no es válida). */
    fun update(rttMs: Long): Long {
        if (rttMs <= 0L) return current()
        synchronized(lock) {
            ewmaMs = if (initialized) ALPHA * rttMs + (1 - ALPHA) * ewmaMs else rttMs.toDouble()
            initialized = true
            return ewmaMs.toLong().coerceAtLeast(1L)
        }
    }

    /** Valor actual o -1 si aún no hay muestras. */
    fun current(): Long = synchronized(lock) {
        if (initialized) ewmaMs.toLong() else -1L
    }

    /** Solo para pruebas: vuelve al estado sin muestras. */
    fun reset() = synchronized(lock) {
        initialized = false
        ewmaMs = 0.0
    }
}
