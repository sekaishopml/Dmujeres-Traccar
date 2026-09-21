package com.dmujeres.traccar.location

/**
 * Política pura (JVM) del fallback GPS_PROVIDER del TrackingService.
 *
 * BUG corregido (evidencia: logcat `RuntimeException: Can't create handler
 * inside thread Thread[DefaultDispatcher-worker-…]`): el registro del fallback
 * se llamaba desde la corutina del watchdog (serviceScope = Dispatchers.Default,
 * thread SIN Looper) y `LocationManager.requestLocationUpdates(provider, …,
 * listener)` crea un `Handler()` interno sobre el thread llamador → excepción →
 * el fallback nunca quedaba registrado.
 *
 * SOLUCIÓN (a nivel de llamada): usar el overload con `Looper` explícito
 * (requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, looper);
 * ver LocationManager AOSP: `new Handler(looper)` en vez de `new Handler()`),
 * con el MAIN looper del proceso. Los callbacks llegan al thread principal,
 * igual que los callbacks del FLP, y no hay HandlerThread que gestionar:
 * unregister en stop/destroy cubre el ciclo de vida del servicio.
 *
 * Esta política solo decide el estado (registered/desea registrar/permiso);
 * el registro Android real lo hace TrackingService con el overload seguro.
 */
object GnssFallbackPolicy {

    data class State(
        val registered: Boolean,
        val fineLocationGranted: Boolean,
        val lastError: String = "",
    )

    /** ¿Intentar registrar el fallback ahora? */
    fun shouldRegister(state: State): Boolean =
        !state.registered && state.fineLocationGranted

    /** ¿Retirar el registro ahora (fix fresco recuperado / servicio detenido)? */
    fun shouldUnregister(state: State): Boolean = state.registered

    /** Estado siguiente tras un registro exitoso. */
    fun registered(state: State): State = state.copy(
        registered = true,
        lastError = "",
    )

    /** Estado siguiente tras un registro fallido (con el error honesto). */
    fun failure(state: State, error: String): State = state.copy(
        registered = false,
        lastError = error.take(120),
    )

    /** Estado siguiente tras retirar el registro. */
    fun unregistered(state: State): State = state.copy(registered = false, lastError = "")
}
