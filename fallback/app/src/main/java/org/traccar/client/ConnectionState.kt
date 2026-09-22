package org.traccar.client

/**
 * Estado real de envío del cliente (sin consultar la base): lo actualiza el
 * [TrackingController] en cada intento. La pantalla principal lo usa para el
 * estado "SIN CONEXIÓN" (hay internet pero el servidor no recibe).
 */
object ConnectionState {

    @Volatile
    private var lastSuccessAt = 0L

    @Volatile
    private var lastFailureAt = 0L

    fun noteSuccess(nowMs: Long) {
        lastSuccessAt = nowMs
    }

    fun noteFailure(nowMs: Long) {
        lastFailureAt = nowMs
    }

    /** true si el último intento falló y no hubo uno exitoso después. */
    fun isFailing(): Boolean = lastFailureAt > lastSuccessAt
}
