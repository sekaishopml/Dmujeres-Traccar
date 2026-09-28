package org.traccar.client

/**
 * Antiduplicado de entrega del fused (cliente, barato, sin queries).
 *
 * Caso real (dmt-db, dispositivo 50, 2026-09-28): el fused entregó la misma
 * observación dos veces (mismo fijado_en al milisegundo, mismas coordenadas,
 * mismo boot_id) y el almacén la guardó dos veces (seq 35/36 y 37/38). El
 * dedupe del servidor no las atrapa porque la identidad (boot_id,
 * local_sequence) se asigna POR INSERCION en [DatabaseHelper.withIdentity]:
 * dos inserciones de la misma observación tienen identidad distinta.
 *
 * Mecanismo: [GooglePositionProvider] entrega por dos caminos al mismo
 * listener (callback periódico con [PositionProvider.processLocation] y
 * `getCurrentLocation` directo en `requestSingleLocation` /
 * `requestFreshLocation`, sin pasar por el filtro ni actualizar
 * `lastLocation`). [TrackingController] además pide fixes sueltos
 * (cambio a cadencia fina, significant motion, giro + refuerzo, refresco
 * manual, rescate): cuando el fix suelto coincide con el periódico, la misma
 * observación llega dos veces a `onPositionUpdate` y se almacena dos veces.
 *
 * El guard compara contra el ÚLTIMO fix almacenado (O(1), en memoria,
 * sembrado desde el historial al arrancar: cero queries en régimen). Solo si
 * la memoria está vacía (arranque a medias) se lee UNA fila de SQLite, y solo
 * para fixes que ya pasaron todos los filtros.
 *
 * No toca cadencia (la máquina sigue viendo todos los fixes), ni protocolo,
 * ni jornada, ni recuperación: solo evita el INSERT duplicado.
 */
object DuplicateFixGuard {

    /**
     * Ventana de entrega duplicada (ms): el mismo punto con dt < 5 s no es
     * cadencia real (ACTIVE 10 s, STATIONARY 120 s), es la misma observación
     * entregada por dos caminos.
     */
    const val DUPLICATE_WINDOW_MS = 5_000L

    /** Último fix almacenado: captured_at en ms + coordenadas exactas. */
    data class Fix(
        val capturedAtMs: Long,
        val latitude: Double,
        val longitude: Double,
    )

    /**
     * ¿Es [candidate] una re-entrega de [last] ya almacenado?
     *
     * Regla 1: mismo captured_at (ms) Y mismas lat/lon exactas → duplicado
     * (la misma observación del fused, re-entregada; vale aunque el dt de
     * pared sea grande, p. ej. redelivery de last-known con su captured_at
     * original).
     *
     * Regla 2: mismas lat/lon exactas (desplazamiento 0 m) con
     * 0 <= dt < 5 s → duplicado (misma entrega por dos caminos con
     * captured_at ligeramente distinto). La cadencia legítima con
     * desplazamiento 0 es >= 10 s, así que < 5 s nunca es un punto válido.
     */
    fun isDuplicate(candidate: Fix, last: Fix): Boolean {
        if (candidate.latitude != last.latitude || candidate.longitude != last.longitude) {
            return false
        }
        if (candidate.capturedAtMs == last.capturedAtMs) {
            return true
        }
        val dt = candidate.capturedAtMs - last.capturedAtMs
        return dt >= 0L && dt < DUPLICATE_WINDOW_MS
    }
}
