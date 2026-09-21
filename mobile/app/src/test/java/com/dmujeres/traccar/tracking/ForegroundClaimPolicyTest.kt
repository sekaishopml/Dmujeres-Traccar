package com.dmujeres.traccar.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tabla pura de ForegroundClaimPolicy: quién reclama el foreground como PRIMER
 * acto de onStartCommand (anti ForegroundServiceDidNotStartInTimeException) y
 * quién jamás debe llamar startForeground (STOP sobre servicio no arrancado).
 */
class ForegroundClaimPolicyTest {

    @Test
    fun startAlwaysClaims() {
        // ACTION_START con servicio frío: hay promesa de startForegroundService viva.
        assertTrue(ForegroundClaimPolicy.shouldClaimForeground(isStopAction = false, serviceAlive = false))
        // START_STICKY con null-intent: el sistema exige de nuevo el reclamo.
        assertTrue(ForegroundClaimPolicy.shouldClaimForeground(isStopAction = false, serviceAlive = true))
        // START mientras se está drenando el stop: el drenaje puede durar más que
        // el timeout → hay que pagar la promesa YA (era la ruta del crash real).
        assertTrue(ForegroundClaimPolicy.shouldClaimForeground(isStopAction = false, serviceAlive = true))
    }

    @Test
    fun stopClaimsOnlyWhenAlive() {
        // Servicio vivo: companion.stop puede haber caído a startForegroundService
        // (startService rechazado en background) → re-afirmar barato.
        assertTrue(ForegroundClaimPolicy.shouldClaimForeground(isStopAction = true, serviceAlive = true))
        // Servicio no arrancado: NUNCA startForeground (crash Android 14+ con
        // ForegroundServiceStartNotAllowedException); el camino hace stopSelf ya.
        assertFalse(ForegroundClaimPolicy.shouldClaimForeground(isStopAction = true, serviceAlive = false))
    }
}
