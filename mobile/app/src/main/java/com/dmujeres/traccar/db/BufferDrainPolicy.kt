package com.dmujeres.traccar.db

/**
 * Drenaje del buffer tras recuperar conexión (arreglo de almacenamiento).
 *
 * Causa raíz: al volver la red solo se hacía UN flush HTTP (50 puntos) y el
 * resto goteaba por MQTT single-flight (~1 msg / 15 s de timeout en enlace
 * débil): un backlog de miles de puntos tardaba horas en subir y parecía
 * "ruta perdida". Ahora se drena por lotes hasta vaciar (tope por evento).
 */
object BufferDrainPolicy {

    /** Lotes máximos por evento de reconexión (40 × 50 = 2000 puntos). */
    const val MAX_BATCHES_PER_EVENT = 40

    /**
     * ¿Seguir drenando? Sí mientras el último lote confirmó algo y queden
     * lotes. Si un lote confirma 0 (backlog vacío o enlace caído) se para: el
     * próximo evento de red/MQTT reintenta. Pura y testeable.
     */
    fun continueDraining(lastConfirmed: Int, batchesDone: Int): Boolean =
        batchesDone < MAX_BATCHES_PER_EVENT && lastConfirmed > 0
}
