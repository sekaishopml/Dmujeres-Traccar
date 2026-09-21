package com.dmujeres.traccar.data

/**
 * Retención dura del outbox offline (Room `pending_positions`).
 *
 * Garantía: con jornada activa, una posición aceptada por el filtro NUNCA se
 * pierde en silencio dentro de la ventana de retención, haya o no Internet.
 * Solo se elimina tras el ACK de aplicación del servidor
 * (`accepted/duplicate/rejected/invalid/expired`) o al superar la retención
 * dura (con alerta + log, nunca en silencio).
 *
 * La retención se define por DOS topes independientes ("lo que ocurra primero"):
 * - [MAX_POSITIONS]: 100 000 registros.
 * - [MAX_AGE_MS]: 7 días desde el encolado.
 *
 * ¿Por qué estos valores?
 * - 72 h offline a 10 s (intervalo por defecto) = 25 920 posiciones < 100 000.
 * - 7 días a 10 s = 60 480 posiciones < 100 000.
 * - 7 días a 3 s (intervalo mínimo configurable) = 201 600 > 100 000: en ese
 *   extremo rige el tope por count (documentado, con alerta).
 * - El servidor rechaza `observedAt` con más de 7 días (`expired`, terminal):
 *   retener más allá de 7 días es inútil (igual se borraría tras el NACK) y
 *   solo gastaría radio/batería enviando mensajes condenados.
 * - Tamaño: payload ~0,4-0,6 KB → fila ~1 KB con índices → 100 000 ≈ 50-100 MB
 *   SQLite. Aceptable en teléfonos actuales; todas las lecturas del dispatcher
 *   están paginadas (100/50) y usan los índices `sequence/retryAt/isControl`.
 * - Drenaje: 100 000 / 50 por lote = 2 000 lotes; a ~0,5-1,5 s/lote son
 *   ~20-50 min en el peor caso. No es tiempo real: es replay de recuperación,
 *   y es preferible a perder la ruta.
 *
 * Los controles de jornada (`started/ended`, `isControl=1`) están exentos de
 * ambas purgas: son 1-2 filas y sin ellas el servidor no abre/cierra la jornada.
 *
 * Pura (JVM, sin Android ni Room): unit-testeable.
 */
object OutboxRetentionPolicy {

    /** Tope por count: cubre 72 h incluso al intervalo mínimo (72h@3s = 86 400). */
    const val MAX_POSITIONS = 100_000

    /** Tope por edad: alinea con el `MAX_AGE 7d` del validador del servidor. */
    const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    /** Corte de expiración para `enqueuedAt`: todo `isControl=0` anterior se purga. */
    fun expiredCutoffMs(nowMs: Long): Long = nowMs - MAX_AGE_MS

    /** true si la fila (no-control) ya superó la retención por edad. */
    fun isExpired(enqueuedAt: Long, nowMs: Long): Boolean =
        enqueuedAt > 0L && enqueuedAt < expiredCutoffMs(nowMs)

    /**
     * Cuántas filas hay que desalojar por overflow ANTES de insertar, igual que
     * [PositionBufferPolicy.discardCount] pero contra la retención dura.
     */
    fun overflowEvictCount(currentCount: Int, maximum: Int = MAX_POSITIONS): Int {
        require(maximum > 0) { "maximum must be positive" }
        return (currentCount - maximum + 1).coerceAtLeast(0)
    }

    /**
     * Tope efectivo de evicción/pausa: sanea instalaciones con el default
     * antiguo (5 000 ≈ 14 h, insuficiente para 24-72 h offline) sin migración
     * de prefs; respeta valores configurados mayores.
     */
    fun effectiveMax(configuredMax: Int): Int =
        maxOf(configuredMax, MAX_POSITIONS)

    /**
     * Estimación de posiciones para dimensionar: horas offline / intervalo.
     * 72 h @10s = 25 920; 7 d @10s = 60 480; 7 d @3s = 201 600.
     */
    fun estimatePositions(offlineHours: Double, intervalSeconds: Double): Long {
        require(offlineHours >= 0 && intervalSeconds > 0)
        return (offlineHours * 3600.0 / intervalSeconds).toLong()
    }

    /** true si la estimación cabe en la retención por count. */
    fun fitsRetention(offlineHours: Double, intervalSeconds: Double): Boolean =
        estimatePositions(offlineHours, intervalSeconds) <= MAX_POSITIONS
}
