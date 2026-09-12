package com.dmujeres.traccar.db

import kotlinx.coroutines.sync.Mutex

/**
 * Single-flight entre el dispatch MQTT (presencia) y el dispatcher HTTP
 * (posiciones, propietario único).
 *
 * Ambos tocan la misma tabla `pending_positions` y finalizan por `messageId`.
 * Sin este Mutex, dispatchLoop y PositionOutboxDispatcher.flushOnce() pueden
 * actuar sobre el mismo messageId en paralelo (doble publish / doble delete).
 *
 * Uso: `DispatchLock.mutex.withLock { ... }` (suspend, sin bloqueo de hilo).
 * Un solo Mutex global => no hay orden de locks => no hay deadlock.
 * No usar `lock()`/`unlock()` manuales ni `runBlocking` dentro del lock.
 */
object DispatchLock {
    val mutex = Mutex()
}
