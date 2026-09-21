package com.dmujeres.traccar.recovery

/**
 * R7 STUCK-STOP: si en boot/recovery hay trackingEnabled==true Y
 * journeyStopRequested==true (crash a mitad de stop), se prefiere START
 * (limpiar stopRequested y arrancar) en vez de forzar stop. El stop real pone
 * trackingEnabled=false y sigue funcionando por la rama STOP/DRAIN.
 *
 * Puro (testeable en JVM, sin dependencias Android).
 */
object StuckStopPolicy {

    enum class BootAction {
        /** Crash a mitad de stop: limpiar stopRequested y arrancar. */
        START_PREFER_RECOVERY,
        /** Stop real pendiente (trackingEnabled=false): forzar stop. */
        STOP,
        /** Tracking activo sin stop pendiente: arrancar. */
        START,
        NONE,
    }

    fun decideBoot(
        trackingEnabled: Boolean,
        stopRequested: Boolean,
        journeyStartAt: Long,
    ): BootAction = when {
        trackingEnabled && stopRequested -> BootAction.START_PREFER_RECOVERY
        stopRequested && journeyStartAt > 0L -> BootAction.STOP
        trackingEnabled -> BootAction.START
        else -> BootAction.NONE
    }

    enum class RecoveryAction {
        START_PREFER_RECOVERY,
        START,
        STOP,
        NONE,
    }

    fun decideRecovery(
        trackingEnabled: Boolean,
        stopRequested: Boolean,
        journeyStartAt: Long,
        isRunning: Boolean,
    ): RecoveryAction = when {
        trackingEnabled && stopRequested -> RecoveryAction.START_PREFER_RECOVERY
        trackingEnabled && !isRunning -> RecoveryAction.START
        stopRequested && journeyStartAt > 0L && isRunning -> RecoveryAction.STOP
        else -> RecoveryAction.NONE
    }
}
