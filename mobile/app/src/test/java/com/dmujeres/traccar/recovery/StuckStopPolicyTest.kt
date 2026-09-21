package com.dmujeres.traccar.recovery

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R7 STUCK-STOP: si en boot/recovery hay trackingEnabled==true Y
 * journeyStopRequested==true (crash a mitad de stop), se prefiere START
 * (limpiar stopRequested y arrancar) en vez de forzar stop. El stop real pone
 * trackingEnabled=false y sigue funcionando.
 */
class StuckStopPolicyTest {

    // ---- Boot ----

    @Test
    fun bootPrefersStartWhenTrackingAndStopRequested() {
        // Crash a mitad de stop: preferir START, no forzar stop.
        assertEquals(
            StuckStopPolicy.BootAction.START_PREFER_RECOVERY,
            StuckStopPolicy.decideBoot(
                trackingEnabled = true,
                stopRequested = true,
                journeyStartAt = 123L,
            ),
        )
    }

    @Test
    fun bootForcesStopForRealStop() {
        // Stop real: trackingEnabled=false + stopRequested=true sigue forzando stop.
        assertEquals(
            StuckStopPolicy.BootAction.STOP,
            StuckStopPolicy.decideBoot(
                trackingEnabled = false,
                stopRequested = true,
                journeyStartAt = 123L,
            ),
        )
    }

    @Test
    fun bootStartsWhenOnlyTracking() {
        assertEquals(
            StuckStopPolicy.BootAction.START,
            StuckStopPolicy.decideBoot(
                trackingEnabled = true,
                stopRequested = false,
                journeyStartAt = 123L,
            ),
        )
    }

    @Test
    fun bootDoesNothingWhenIdle() {
        assertEquals(
            StuckStopPolicy.BootAction.NONE,
            StuckStopPolicy.decideBoot(
                trackingEnabled = false,
                stopRequested = false,
                journeyStartAt = 0L,
            ),
        )
    }

    @Test
    fun bootIgnoresStopWithoutJourney() {
        // stopRequested sin journey no es un stop real.
        assertEquals(
            StuckStopPolicy.BootAction.NONE,
            StuckStopPolicy.decideBoot(
                trackingEnabled = false,
                stopRequested = true,
                journeyStartAt = 0L,
            ),
        )
    }

    // ---- Recovery (Worker) ----

    @Test
    fun recoveryPrefersStartWhenTrackingAndStopRequestedServiceDown() {
        assertEquals(
            StuckStopPolicy.RecoveryAction.START_PREFER_RECOVERY,
            StuckStopPolicy.decideRecovery(
                trackingEnabled = true,
                stopRequested = true,
                journeyStartAt = 123L,
                isRunning = false,
            ),
        )
    }

    @Test
    fun recoveryPrefersStartWhenTrackingAndStopRequestedServiceUp() {
        // Aunque el servicio siga vivo, no se fuerza stop: se limpia y se sigue.
        assertEquals(
            StuckStopPolicy.RecoveryAction.START_PREFER_RECOVERY,
            StuckStopPolicy.decideRecovery(
                trackingEnabled = true,
                stopRequested = true,
                journeyStartAt = 123L,
                isRunning = true,
            ),
        )
    }

    @Test
    fun recoveryRestartsCrashedService() {
        assertEquals(
            StuckStopPolicy.RecoveryAction.START,
            StuckStopPolicy.decideRecovery(
                trackingEnabled = true,
                stopRequested = false,
                journeyStartAt = 123L,
                isRunning = false,
            ),
        )
    }

    @Test
    fun recoveryForcesStopForRealStopWhileRunning() {
        // Stop real (tracking=false) con servicio vivo: forzar stop sigue funcionando.
        assertEquals(
            StuckStopPolicy.RecoveryAction.STOP,
            StuckStopPolicy.decideRecovery(
                trackingEnabled = false,
                stopRequested = true,
                journeyStartAt = 123L,
                isRunning = true,
            ),
        )
    }

    @Test
    fun recoveryDoesNothingWhenHealthy() {
        assertEquals(
            StuckStopPolicy.RecoveryAction.NONE,
            StuckStopPolicy.decideRecovery(
                trackingEnabled = true,
                stopRequested = false,
                journeyStartAt = 123L,
                isRunning = true,
            ),
        )
        assertEquals(
            StuckStopPolicy.RecoveryAction.NONE,
            StuckStopPolicy.decideRecovery(
                trackingEnabled = false,
                stopRequested = false,
                journeyStartAt = 0L,
                isRunning = false,
            ),
        )
    }
}
