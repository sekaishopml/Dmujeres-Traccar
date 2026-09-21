package com.dmujeres.traccar.tracking

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * R7: wakelock PARCIAL acotado de jornada (experimento medible).
 *
 * Solo se activa con el interruptor oculto (menú de diseño, 5 toques en la
 * versión dentro del diagnóstico). La ventana se renueva con cada fix; al
 * vencer, el sistema vuelve a dormir por su cuenta si la app dejó de reportar.
 */
object JourneyWakeLock {

    private const val TAG = "JourneyWakeLock"

    @Volatile
    private var lock: PowerManager.WakeLock? = null

    fun acquire(context: Context, windowMs: Long) {
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java) ?: return
            val wl = lock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dmj:journey")
                .also {
                    it.setReferenceCounted(false)
                    lock = it
                }
            if (wl.isHeld) wl.release()
            wl.acquire(windowMs)
        }.onFailure { Log.w(TAG, "No se pudo tomar el wakelock de jornada", it) }
    }

    fun release() {
        runCatching {
            val wl = lock ?: return
            if (wl.isHeld) wl.release()
        }
    }

    fun isHeld(): Boolean = lock?.isHeld == true

    /** Para diagnóstico (menú oculto): ventana restante aproximada no expuesta. */
    fun heldForDiagnostics(): String = if (isHeld()) "held" else "free"
}
