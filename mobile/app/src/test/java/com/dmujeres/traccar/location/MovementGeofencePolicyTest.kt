package com.dmujeres.traccar.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R9: política de la geofence de arranque (canal exento por el OS). */
class MovementGeofencePolicyTest {

    private val haversine = { lat1: Double, lon1: Double, lat2: Double, lon2: Double ->
        // distancia aproximada en metros para el test (1° lat ≈ 111 km)
        val dLat = Math.abs(lat2 - lat1) * 111_000.0
        val dLon = Math.abs(lon1 - lon2) * Math.cos(Math.toRadians(lat1)) * 111_000.0
        Math.sqrt(dLat * dLat + dLon * dLon)
    }

    @Test
    fun registraConJornadaActivaYFixFresco() {
        assertTrue(MovementGeofencePolicy.shouldRegister(journeyActive = true, hasFreshFix = true))
    }

    @Test
    fun noRegistraSinJornadaNiFix() {
        assertFalse(MovementGeofencePolicy.shouldRegister(journeyActive = false, hasFreshFix = true))
        assertFalse(MovementGeofencePolicy.shouldRegister(journeyActive = true, hasFreshFix = false))
    }

    @Test
    fun reRegistraSiElAnclaSeAlejo() {
        // mismo punto → no re-registrar
        assertFalse(
            MovementGeofencePolicy.shouldReRegister(
                true, 0.0, 0.0, 0.0, 0.0, haversine,
            )
        )
        // 0.001° lat ≈ 111 m > 50 m → re-registrar
        assertTrue(
            MovementGeofencePolicy.shouldReRegister(
                registered = true, registeredLat = 0.0, registeredLon = 0.0,
                anchorLat = 0.001, anchorLon = 0.0,
                distanceMeters = haversine,
            )
        )
        // sin geofence registrada → registrar
        assertTrue(
            MovementGeofencePolicy.shouldReRegister(
                registered = false, registeredLat = null, registeredLon = null,
                anchorLat = 0.0, anchorLon = 0.0,
                distanceMeters = haversine,
            )
        )
    }

    @Test
    fun retiraAlMoverseOSinJornada() {
        assertTrue(MovementGeofencePolicy.shouldRemove(stationary = false, journeyActive = true))
        assertTrue(MovementGeofencePolicy.shouldRemove(stationary = true, journeyActive = false))
        assertFalse(MovementGeofencePolicy.shouldRemove(stationary = true, journeyActive = true))
    }

    @Test
    fun radioRazonable() {
        assertTrue(MovementGeofencePolicy.RADIUS_M in 100.0..300.0)
    }
}
