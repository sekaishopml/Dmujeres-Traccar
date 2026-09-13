package com.dmujeres.traccar.mqtt

/**
 * Orden de envío de la cola: FIFO ESTRICTO por sequence.
 *
 * El replay offline debe salir completo y en orden: la posición más antigua
 * vencida se envía primero, sea posición, presence o señal de jornada. Una
 * versión anterior priorizaba los controles (journeyEnded saltaba la cola) y
 * eso rompía la ruta: si el servidor cerraba la jornada al ver `ended`, las
 * posiciones tardías llegaban fuera de ventana (expired/rejected) y se
 * borraban tras el NACK, perdiendo trozos de ruta para siempre. Con FIFO el
 * `ended` —que siempre tiene la sequence mayor— sale el último, tras drenar
 * las posiciones, y el panel lo muestra con pending=0 honesto.
 *
 * Reglas (puras y testeables, sin Android ni Room):
 * - Solo compiten los mensajes vencidos (retryAt ya cumplido o sin backoff).
 * - Gana el vencido con menor sequence. Sin clases prioritarias.
 * - Si nada vence, null (el llamador espera al próximo retryAt).
 */
object DispatchPolicy {

    data class QueueItem(
        val messageId: String,
        val sequence: Long,
        /** retryAt persistido en DB (0 = sin backoff). */
        val retryAtDb: Long,
        /** retryAt calculado en memoria (null = no hay). */
        val retryAtMem: Long?,
        val isControl: Boolean,
    )

    private fun effectiveRetry(item: QueueItem): Long? {
        val db = item.retryAtDb
        val mem = item.retryAtMem
        return when {
            db > 0L && mem != null -> maxOf(db, mem)
            db > 0L -> db
            else -> mem
        }
    }

    private fun isDue(item: QueueItem, now: Long): Boolean {
        val effective = effectiveRetry(item)
        return effective == null || effective <= now
    }

    fun selectNext(items: List<QueueItem>, now: Long): QueueItem? {
        var best: QueueItem? = null
        for (item in items) {
            if (!isDue(item, now)) {
                continue
            }
            if (best == null || item.sequence < best.sequence) {
                best = item
            }
        }
        return best
    }

    /** Próximo retryAt futuro (para dormir el loop), igual que antes. */
    fun nextRetryAt(items: List<QueueItem>, now: Long): Long? {
        var best: Long? = null
        for (item in items) {
            val effective = effectiveRetry(item) ?: continue
            if (effective > now && (best == null || effective < best)) {
                best = effective
            }
        }
        return best
    }

    // --- Robustez: backoff con jitter (puro, testeable sin Android ni Room) ---
    // Dispatch: 5s * 2^attempts ; reconnect: 30s base. Ambos con ±25% jitter y techo 5 min
    // para evitar thundering herd cuando muchos dispositivos reintentan a la vez.
    const val DISPATCH_BASE_MS = 5_000L
    const val CONNECT_RETRY_BASE_MS = 30_000L
    const val MAX_BACKOFF_MS = 300_000L
    const val JITTER_FRACTION = 0.25

    /**
     * Aplica ±[jitterFraction] de jitter uniforme a [baseDelayMs] y lo acota a [maxMs].
     * @param random01 uniforme en [0,1]; 0.5 = sin jitter (útil para tests deterministas).
     */
    fun jitteredDelay(
        baseDelayMs: Long,
        random01: Double = Math.random(),
        jitterFraction: Double = JITTER_FRACTION,
        maxMs: Long = MAX_BACKOFF_MS,
    ): Long {
        val clampedBase = baseDelayMs.coerceIn(0L, maxMs)
        val clampedRandom = random01.coerceIn(0.0, 1.0)
        val factor = 1.0 - jitterFraction + clampedRandom * 2.0 * jitterFraction
        return (clampedBase * factor).toLong().coerceIn(0L, maxMs)
    }

    /** Backoff de dispatch: 5s * 2^attempts con jitter y techo 5 min. */
    fun dispatchBackoffMs(
        attempts: Int,
        baseMs: Long = DISPATCH_BASE_MS,
        random01: Double = Math.random(),
        maxMs: Long = MAX_BACKOFF_MS,
    ): Long {
        val safeAttempts = attempts.coerceIn(0, 8)
        // 1L shl 8 = 256; base 5s -> 1.28M antes del techo.
        val exponential = baseMs.coerceAtLeast(0L) * (1L shl safeAttempts)
        val capped = exponential.coerceAtMost(maxMs)
        return jitteredDelay(capped, random01, JITTER_FRACTION, maxMs)
    }

    /** Espera de reconnect (base 30s) con jitter y techo 5 min. */
    fun connectRetryDelayMs(
        baseMs: Long = CONNECT_RETRY_BASE_MS,
        random01: Double = Math.random(),
        maxMs: Long = MAX_BACKOFF_MS,
    ): Long = jitteredDelay(baseMs, random01, JITTER_FRACTION, maxMs)

    /**
     * Ruta sin pérdida: un mensaje SIN ACK del servidor NUNCA se descarta por
     * agotar reintentos, ni posiciones ni controles (started/ended). Sin ACK
     * no se sabe si el servidor lo vio: borrarlo tras N intentos elimina
     * puntos reales de la ruta (o la señal de cierre) y el replay queda
     * incompleto para siempre. El backoff con techo (5 min) ya evita la
     * tormenta de reintentos; la cola acotada (db.PositionBufferPolicy) es el
     *     único mecanismo de purga. Solo un NACK explícito del servidor
     * (rejected/invalid/expired) es final y autoriza el borrado.
     */
    fun shouldDiscardUnacked(isControl: Boolean, attempts: Int, maxRetries: Int): Boolean = false

    // --- Clasificación de un HTTP no-2xx GLOBAL del lote (pura, testeable) ---

    /** Destino de un lote rechazado por HTTP no-2xx. */
    enum class HttpFailure {
        /** Transporte/red sobrecargado: sin attempts, reintento inmediato próximo evento. */
        TRANSIENT,
        /** Sobrecarga explícita del servidor: attempts+1 y backoff existente. */
        THROTTLED,
        /** Definitivo (auth/recurso/payload): cuarentena del lote, nunca delete directo. */
        TERMINAL,
    }

    /**
     * Clasifica UN código HTTP no-2xx global del lote. Solo se invoca con
     * códigos fuera de 2xx: 200 no llega aquí. 429/408 son temporales
     * (throttle/timeout del cliente); 401/403/404/405/413/414/422 no cambian
     * con reintentos (credenciales, ruta o payload); el resto (5xx, 0 sin
     * respuesta, <100 inválido, 4xx desconocidos, >499) se trata como
     * transitorio.
     */
    fun classifyHttpFailure(code: Int): HttpFailure = when (code) {
        429, 408 -> HttpFailure.THROTTLED
        401, 403, 404, 405, 413, 414, 422 -> HttpFailure.TERMINAL
        else -> HttpFailure.TRANSIENT
    }
}
