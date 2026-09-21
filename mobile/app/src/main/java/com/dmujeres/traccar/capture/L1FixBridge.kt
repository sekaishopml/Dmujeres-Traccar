package com.dmujeres.traccar.capture

import android.location.Location

/**
 * L1: puente entre [L1LocationReceiver] (captura por PendingIntent, el
 * broadcast despierta el proceso) y el pipeline del servicio VIVO.
 *
 * El servicio setea [listener] al arrancar la captura (startTracking) y lo
 * limpia al parar (stopTracking/onDestroy). Con listener vacío el receptor NO
 * descarta el fix: lo persiste por su cuenta en la cola Room sin arrancar el
 * servicio (ver [L1LocationReceiver]).
 *
 * @Volatile: el receptor corre en el thread del broadcast y el servicio en
 * main/Default; la lectura debe ver siempre el valor vigente.
 */
object L1FixBridge {

    @Volatile
    var listener: ((Location) -> Unit)? = null

    /** Entrega el fix al listener vigente; no-op si no hay servicio vivo. */
    fun deliver(location: Location) {
        listener?.invoke(location)
    }
}
