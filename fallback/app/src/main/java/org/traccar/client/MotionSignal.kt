package org.traccar.client

/**
 * Señal de movimiento por velocidad real, como red de seguridad del sensor.
 *
 * El acelerómetro puede quedarse en "quieto" con el teléfono en un soporte
 * suave o mal pegado: la cadencia adaptativa se quedaba en 120 s mientras el
 * vehículo rodaba y la ruta salía con huecos de minutos (caso Manzaba). Si el
 * GPS o la distancia entre fixes dicen que el equipo se mueve, se fuerza la
 * cadencia fina aunque el sensor no lo note.
 */
object MotionSignal {

    /** Velocidad (nudos) desde la que se considera movimiento real. */
    const val MOVING_SPEED_KN = 3.0

    /**
     * ¿La velocidad justifica tratar el equipo como en movimiento?
     * [speedKn] es la velocidad reportada por el GPS y [impliedKn] la derivada
     * de la distancia/tiempo entre los dos últimos fixes aceptados.
     */
    fun shouldMove(speedKn: Double, impliedKn: Double): Boolean =
        maxOf(speedKn, impliedKn) >= MOVING_SPEED_KN
}
