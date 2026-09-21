package com.dmujeres.traccar.tracking

/**
 * Política pura (JVM) del wakelock de jornada (experimento R7).
 *
 * El FGS location NO garantiza CPU despierta (investigación plataforma): el
 * wakelock parcial cubre los huecos entre fixes. Se usa ACOTADO (ventana con
 * timeout que se renueva con cada fix): si el proceso muere, la ventana expira
 * y Doze vuelve solo — nunca queda encendido indefinidamente.
 */
object WakeLockPolicy {

    /** Ventana = 2 ciclos del guardián + margen (cubre 1 fallo del guardián). */
    fun windowMs(keeperPeriodMs: Long): Long = keeperPeriodMs * 2 + 30_000L

    /** Piso defensivo: ventanas menores no cubren ni un ciclo. */
    const val MIN_WINDOW_MS = 60_000L

    /** ¿Corresponde mantener el wakelock? (jornada activa con tracking). */
    fun shouldHold(trackingEnabled: Boolean, journeyActive: Boolean): Boolean =
        trackingEnabled && journeyActive

    /** Ventana efectiva con piso. */
    fun effectiveWindowMs(keeperPeriodMs: Long): Long =
        maxOf(MIN_WINDOW_MS, windowMs(keeperPeriodMs))
}
