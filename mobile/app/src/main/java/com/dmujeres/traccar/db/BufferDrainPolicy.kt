package com.dmujeres.traccar.db

/**
 * Drenaje del buffer tras recuperar conexión (arreglo de almacenamiento).
 *
 * Causa raíz: al volver la red solo se hacía UN flush HTTP (50 puntos) y el
 * resto goteaba por MQTT single-flight (~1 msg / 15 s de timeout en enlace
 * débil): un backlog de miles de puntos tardaba horas en subir y parecía
 * "ruta perdida". Ahora se drena por lotes hasta vaciar (tope por evento).
 *
 * Tope 200 lotes × 50 = 10 000 puntos/evento: 72 h offline a 10 s son
 * 25 920 posiciones (≈520 lotes) y se drenan en ~3 eventos + el goteo del
 * watchdog; el peor caso (100 000) en ~10 eventos. Cada flush es 1 POST
 * acotado (10 s connect / 15 s read) y corre fuera del hilo principal, así que
 * subir el tope no bloquea la UI ni agota la batería de golpe; el servidor
 * procesa cada lote en serie y el Mutex global lo serializa con el dispatch
 * MQTT sin deadlock. Nunca borra: el flush solo elimina lo confirmado.
 */
object BufferDrainPolicy {

    /** Lotes máximos por evento de reconexión (200 × 50 = 10 000 puntos). */
    const val MAX_BATCHES_PER_EVENT = 200

    /**
     * ¿Seguir drenando? Sí mientras el último lote confirmó algo y queden
     * lotes. Si un lote confirma 0 (backlog vacío o enlace caído) se para: el
     * próximo evento de red/MQTT reintenta. Pura y testeable.
     */
    fun continueDraining(lastConfirmed: Int, batchesDone: Int): Boolean =
        batchesDone < MAX_BATCHES_PER_EVENT && lastConfirmed > 0
}
