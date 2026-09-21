package com.dmujeres.traccar.recovery

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * R8: ventana de rescate al recibir un probe FCM válido.
 *
 * El despertador (FCM) despierta el proceso, pero el GPS puede tardar 30–60 s
 * en enganchar tras dormir. Sin CPU despierta, el sistema vuelve a dormirlo
 * antes del fix (caso joseph: 21 despertares, 2 posiciones). Esta ventana es
 * ACOTADA con timeout nativo (se libera sola) y SOLO se abre en probes
 * válidos — no es el wakelock de jornada (ese sigue detrás del interruptor
 * experimental).
 */
object RescueWindow {

    private const val TAG = "RescueWindow"

    @Volatile
    private var lock: PowerManager.WakeLock? = null

    fun open(context: Context, windowMs: Long = RescueWindowPolicy.WINDOW_MS) {
        if (!RescueWindowPolicy.isBounded(windowMs)) {
            Log.w(TAG, "Ventana fuera de cota, ignorada: $windowMs")
            return
        }
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java) ?: return
            val wl = lock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dmj:rescue")
                .also {
                    it.setReferenceCounted(false)
                    lock = it
                }
            if (wl.isHeld) wl.release()
            // acquire(timeout) se libera SOLO al vencer: imposible dejarlo prendido.
            wl.acquire(windowMs)
            Log.i(TAG, "Ventana de rescate abierta ${windowMs}ms")
        }.onFailure { Log.w(TAG, "No se pudo abrir la ventana de rescate", it) }
    }

    fun isOpen(): Boolean = lock?.isHeld == true
}
