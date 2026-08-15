package com.dmujeres.traccar.db

/** Regla única para mantener acotada la cola antes de insertar una posición. */
object PositionBufferPolicy {

    fun discardCount(currentCount: Int, maximum: Int): Int {
        require(maximum > 0) { "maximum must be positive" }
        return (currentCount - maximum + 1).coerceAtLeast(0)
    }
}
