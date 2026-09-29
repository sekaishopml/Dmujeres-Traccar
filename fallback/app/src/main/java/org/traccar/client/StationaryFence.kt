package org.traccar.client

/**
 * Cerca de quietud: despertador de movimiento independiente del acelerómetro.
 *
 * Por qué existe: en quietud la petición es BALANCED/120 s y, en teléfonos con
 * gestor de energía agresivo (Infinix/Tecno con XOS/HiOS), el IMU y el sensor
 * significativo dejan de llegar con la pantalla apagada. El caso Alejandro
 * (Infinix X6876, Android 16): días de 5-18 km con un punto cada 15 min (solo
 * la alarma de rescate). La geocerca la vigila Google Play Services, fuera del
 * proceso de la app: al salir del radio avisa aunque el sensor esté dormido.
 *
 * El controlador la arma al entrar en STATIONARY (centro = último fix) y la
 * desarma al volver a cadencia fina. El sabor sin Play Services no la tiene.
 */
interface StationaryFence {
    fun arm(latitude: Double, longitude: Double)
    fun disarm()

    companion object {
        /** Radio de la cerca: por encima del ruido de un fix BALANCED urbano. */
        const val RADIUS_M = 150f
    }
}

/** Sin Play Services: no hay geocercas; queda la alarma de rescate. */
object NoStationaryFence : StationaryFence {
    override fun arm(latitude: Double, longitude: Double) = Unit
    override fun disarm() = Unit
}
