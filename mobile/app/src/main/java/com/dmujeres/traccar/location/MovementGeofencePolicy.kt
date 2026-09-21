package com.dmujeres.traccar.location

/**
 * R9 (investigación final): canal de despertar por GEOFENCE.
 *
 * Técnica de transistorsoft (background geolocation, años en campo): Android
 * PERMITE lanzar/activar el servicio en primer plano por una transición de
 * geofence aunque el resto de arranques en segundo plano estén bloqueados
 * (exención explícita de la lista oficial de "background start restrictions").
 *
 * Uso: con jornada activa y el equipo estacionario, se registra una geofence
 * (~150 m) alrededor del ancla estacionaria. Cuando el equipo SALE del radio
 * (arrancó a moverse), el sistema entrega la transición aunque el proceso esté
 * congelado → la app pide fix inmediato y refuerza el guardián.
 *
 * Radio 150 m: ni un paso en casa ni la acera de enfrente dispara; sí lo hace
 * salir del parqueo/edificio. El coste del sistema es mínimo (lo gestiona el
 * GPS del sistema, no nuestra app).
 */
object MovementGeofencePolicy {

    /** Radio de la geofence alrededor del ancla estacionaria (m). */
    const val RADIUS_M = 150.0f

    /** Distancia desde el centro registrado para RE-registrar la geofence (m). */
    const val RE_REGISTER_DRIFT_M = 50.0

    /** ¿Registrar la geofence de arranque? (jornada activa + ancla + quieto) */
    fun shouldRegister(journeyActive: Boolean, hasFreshFix: Boolean): Boolean =
        journeyActive && hasFreshFix

    /** ¿Re-registrar? (la geofence actual se alejó del nuevo ancla). */
    fun shouldReRegister(
        registered: Boolean,
        registeredLat: Double?,
        registeredLon: Double?,
        anchorLat: Double,
        anchorLon: Double,
        distanceMeters: (Double, Double, Double, Double) -> Double,
    ): Boolean {
        if (!registered || registeredLat == null || registeredLon == null) return true
        return distanceMeters(registeredLat, registeredLon, anchorLat, anchorLon) >= RE_REGISTER_DRIFT_M
    }

    /** ¿Retirar la geofence? (dejó de estar quieto o se detuvo la jornada) */
    fun shouldRemove(stationary: Boolean, journeyActive: Boolean): Boolean =
        !stationary || !journeyActive
}
