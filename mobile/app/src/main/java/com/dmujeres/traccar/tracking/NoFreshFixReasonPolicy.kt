package com.dmujeres.traccar.tracking

/**
 * F0/E7: política PURA de la razón honesta de `NO_FRESH_FIX`.
 *
 * El estado "sin fix fresco" decía QUÉ pasa pero no POR QUÉ, y el motivo cambia
 * la acción (no es lo mismo un fallo del proveedor que estar quieto en un
 * parqueadero). La razón se anexa al evento STATE_CHANGE como
 * `NO_FRESH_FIX:<MOTIVO>` y viaja al servidor en la columna `reason` (≤64).
 *
 * Entradas: movimiento del sensor (string de [com.dmujeres.traccar.sensors.MotionState]),
 * satélites usados del último fix (`null` = sin dato) y si el proveedor fused
 * viene fallando ([com.dmujeres.traccar.location.FusedFailurePolicy]).
 */
object NoFreshFixReasonPolicy {

    enum class Reason {
        /** El proceso despertó tarde o los callbacks llegan con retraso: había cielo. */
        PROCESS_WOKE_LATE,

        /** Quieto: los huecos largos son esperados, no un fallo. */
        STATIONARY,

        /** En movimiento y sin satélites usados: interior/cañón urbano. */
        NO_SKY,

        /** El proveedor de ubicación viene fallando (fused no fiable). */
        PROVIDER_FAIL,

        /** Sin datos suficientes para distinguir (honesto, no inventa causa). */
        UNKNOWN,
    }

    fun classify(motion: String, satsUsed: Int?, providerFail: Boolean): Reason = when {
        providerFail -> Reason.PROVIDER_FAIL
        motion == "STATIONARY" -> Reason.STATIONARY
        motion != "MOVING" -> Reason.UNKNOWN
        satsUsed == null -> Reason.UNKNOWN
        satsUsed <= 0 -> Reason.NO_SKY
        else -> Reason.PROCESS_WOKE_LATE
    }

    /**
     * Razón a persistir: para [stateName] `NO_FRESH_FIX` agrega el motivo;
     * cualquier otro estado conserva su nombre (compatibilidad total).
     */
    fun reasonFor(stateName: String, motion: String, satsUsed: Int?, providerFail: Boolean): String =
        if (stateName == "NO_FRESH_FIX") {
            "$stateName:${classify(motion, satsUsed, providerFail).name}"
        } else {
            stateName
        }
}
