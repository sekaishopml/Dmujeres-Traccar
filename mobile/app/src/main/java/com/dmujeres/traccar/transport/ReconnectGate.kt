package com.dmujeres.traccar.transport

/**
 * Dueño único de la cadencia de reconexión MQTT (puerta anti-tormentas).
 *
 * Problema que cierra: antes convivían 4 emisores de `connect()` (Paho
 * `automaticReconnect` 10 s + `scheduleConnectRetry` 30 s en bucle + watchdog
 * 30 s + `NetworkCallback.onAvailable`) y cada `connect()` cerraba el cliente
 * anterior (`stale.close`), matando el retry de Paho: se peleaban entre sí y
 * encadenaban `subscribeAndDispatch` duplicados. Ahora Paho NO reconecta solo
 * (`isAutomaticReconnect=false`) y TODOS los disparadores pasan por esta
 * puerta: como máximo 1 intento por ventana, con backoff exponencial + jitter.
 *
 * Capas (sin solaparse):
 * - Paho: solo transporte TCP de UN intento (sin auto-retry).
 * - ReconnectGate (aquí): cadencia de intentos (debounce + backoff + jitter).
 * - Watchdog/`onAvailable`/`connectionLost`: EVENTOS que piden intento; la
 *   puerta decide si toca (inmediato en vuelta de red validada, backoff si no).
 *
 * Puro (JVM, sin Android): unit-testeable. El estado mutable
 * (`attempts/nextAllowedAt`) vive en [MqttManager], que es el único que llama.
 */
object ReconnectGate {

    /** Primer reintento rápido: cubre micro-cortes sin esperar 30 s. */
    const val FIRST_RETRY_MS = 5_000L

    /** Factor exponencial por fallo consecutivo. */
    const val FACTOR = 2.0

    /** Techo: ni en outage de días se intenta más de 1 vez / 5 min. */
    const val MAX_DELAY_MS = 300_000L

    /** Jitter uniforme ±25 % (anti thundering herd entre dispositivos). */
    const val JITTER_FRACTION = 0.25

    /**
     * Debounce mínimo entre intentos aunque la red flapee
     * (`onAvailable` puede disparar varias veces por handover WiFi↔datos).
     */
    const val MIN_DEBOUNCE_MS = 2_000L

    /** Debounce para el camino inmediato (vuelta de red validada). */
    const val IMMEDIATE_DEBOUNCE_MS = 2_000L

    /** Tope de attempts para el cálculo (2^10 ya supera el techo). */
    const val MAX_ATTEMPTS = 10

    /**
     * Espera antes del próximo intento tras [attempts] fallos consecutivos
     * (0 = primer reintento ≈ 5 s). Exponencial con jitter y techo 5 min.
     * @param random01 uniforme en [0,1]; 0.5 = sin jitter (tests deterministas).
     */
    fun connectDelayMs(attempts: Int, random01: Double = Math.random()): Long {
        val safe = attempts.coerceIn(0, MAX_ATTEMPTS)
        val exponential = (FIRST_RETRY_MS * Math.pow(FACTOR, safe.toDouble())).toLong()
        val capped = exponential.coerceAtMost(MAX_DELAY_MS)
        return DispatchPolicy.jitteredDelay(capped, random01, JITTER_FRACTION, MAX_DELAY_MS)
    }

    /**
     * ¿Toca intentar ahora por la vía con backoff (watchdog / fallo previo)?
     * @param nextAllowedAt fijado por el dueño al programar el reintento.
     */
    fun shouldAttempt(nowMs: Long, nextAllowedAtMs: Long, lastAttemptAtMs: Long): Boolean =
        nowMs >= nextAllowedAtMs && nowMs - lastAttemptAtMs >= MIN_DEBOUNCE_MS

    /** ¿Toca intentar por la vía inmediata (vuelta de red validada)? Solo debounce. */
    fun shouldAttemptImmediate(nowMs: Long, lastAttemptAtMs: Long): Boolean =
        nowMs - lastAttemptAtMs >= IMMEDIATE_DEBOUNCE_MS
}
