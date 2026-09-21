package com.dmujeres.traccar.data

/**
 * R8: política pura de reanudación de captura tras pausa por buffer lleno
 * (stop_capture). Antes se reanudaba al 80 % del tope: con 100 000 de tope son
 * 20 000 mensajes de histéresis (~horas a 100/min) con la captura apagada.
 * Con margen fijo la captura vuelve apenas hay espacio real.
 */
object BufferPausePolicy {

    /** Margen para reanudar: con espacio para ~500 mensajes basta. */
    const val RESUME_MARGIN = 500

    /** ¿Reanudar captura? (pending por debajo del tope menos el margen) */
    fun shouldResume(pendingCount: Int, retentionMax: Int, margin: Int = RESUME_MARGIN): Boolean =
        retentionMax > 0 && pendingCount < maxOf(0, retentionMax - margin)
}
