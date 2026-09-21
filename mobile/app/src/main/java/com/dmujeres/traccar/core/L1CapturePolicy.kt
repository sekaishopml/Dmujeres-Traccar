package com.dmujeres.traccar.core

/**
 * L1 (captura pasiva con `PendingIntent` + batching del FLP): política pura,
 * sin dependencias Android, para decidir cuándo usarla y con qué parámetros
 * registrar los updates.
 *
 * El panel puede pedir retardos de batching o mínimos de intervalo fuera del
 * rango seguro: acotarlos aquí (testeable en JVM) evita tanto el drenaje de
 * batería como la pérdida de trazo, y deja la decisión de L1 en una sola
 * expresión: switch ON + permiso fino concedido + jornada activa.
 */
object L1CapturePolicy {

    /** Retardo de batching por defecto si el panel no pide otro (60 s). */
    const val DEFAULT_MAX_UPDATE_DELAY_MS = 60_000L

    /** Piso del retardo de batching (30 s): por debajo no compensa la demora. */
    const val MIN_MAX_UPDATE_DELAY_MS = 30_000L

    /** Techo del retardo de batching (5 min): más demora pierde el trazo. */
    const val MAX_MAX_UPDATE_DELAY_MS = 300_000L

    /** Piso del intervalo efectivo (10 s): ritmo de muestreo de la industria (R8). */
    private const val MIN_INTERVAL_SECONDS = 10L

    /** Techo del intervalo efectivo (10 min): seguimiento sin trazo fino. */
    private const val MAX_INTERVAL_SECONDS = 600L

    /** Acota el retardo de batching pedido por el panel al rango [30s, 5min]. */
    fun clampDelay(requestedMs: Long): Long =
        requestedMs.coerceIn(MIN_MAX_UPDATE_DELAY_MS, MAX_MAX_UPDATE_DELAY_MS)

    /** L1 (PendingIntent+batching) solo con switch ON, permiso fino y jornada activa. */
    fun shouldUsePendingIntent(
        enabled: Boolean,
        fineGranted: Boolean,
        journeyActive: Boolean,
    ): Boolean = enabled && fineGranted && journeyActive

    /** Intervalo efectivo = max(base, minInterval) acotado a [10, 600] segundos. */
    fun effectiveIntervalSeconds(baseSeconds: Long, minIntervalSeconds: Long): Long =
        maxOf(baseSeconds, minIntervalSeconds)
            .coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
}
