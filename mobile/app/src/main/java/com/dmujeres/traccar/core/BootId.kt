package com.dmujeres.traccar.core

import java.util.UUID

/**
 * Lógica de sessionId/bootId pura y testeable (sin Android).
 *
 * - sessionId: se regenera en cada startTracking (ejecución lógica, aunque la
 *   jornada se recupere).
 * - bootId: estable mientras el reloj monotónico no retroceda. Un
 *   elapsedRealtime menor que el persistido solo puede ser un REBOOT (el
 *   monotónico no puede retroceder en el mismo boot), con margen de 60 s.
 */
object BootId {

    /** Margen (ms) tolerado antes de declarar reboot (drift de lectura del reloj). */
    const val REBOOT_TOLERANCE_MS = 60_000L

    /** UUID corto sin guiones (16 hex). */
    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    /**
     * Devuelve (bootId a usar, elapsedMs a persistir):
     * - bootId vacío/absente → genera uno nuevo con el elapsed actual.
     * - persistedElapsed > nowElapsed + 60_000 → reboot detectado → nuevo id.
     * - si no → mismo id, elapsed actualizado.
     */
    fun refresh(currentBootId: String?, persistedElapsedMs: Long, nowElapsedMs: Long): Pair<String, Long> {
        val current = currentBootId.orEmpty()
        val rebootDetected = persistedElapsedMs >= 0L &&
            persistedElapsedMs > nowElapsedMs + REBOOT_TOLERANCE_MS
        return when {
            current.isBlank() -> newId() to nowElapsedMs
            rebootDetected -> newId() to nowElapsedMs
            else -> current to nowElapsedMs
        }
    }
}
