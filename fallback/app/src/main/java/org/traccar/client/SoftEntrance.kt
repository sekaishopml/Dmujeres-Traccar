package org.traccar.client

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Entrada suave de las pantallas del asistente.
 *
 * Optimización: el desenfoque se aplica UNA sola vez (un único RenderEffect) y
 * se retira pasado un instante; animarlo por frame disparaba una pasada de blur
 * de GPU en cada frame (varios bloques a la vez) y en los equipos de la flota se
 * sentía el lag. La entrada normal es desvanecido + desplazamiento corto + un
 * zoom mínimo, todo con ViewPropertyAnimator (barato y a 60 fps).
 */
object SoftEntrance {

    /** Ventana del desenfoque de un solo tiro (ms); después se retira. */
    private const val BLUR_WINDOW_MS = 160L

    private const val BLUR_RADIUS = 10f

    fun animate(
        view: View,
        delayMs: Long = 0L,
        durationMs: Long = 420L,
        slideDp: Float = 16f,
        withBlur: Boolean = false,
    ) {
        if (animationsDisabled(view)) {
            // Accesibilidad o rendimiento: sin animación, contenido visible ya.
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        val density = view.resources.displayMetrics.density
        view.alpha = 0f
        view.translationY = slideDp * density
        // Zoom mínimo: da la sensación de "aparecer" sin mover el layout.
        view.scaleX = 0.985f
        view.scaleY = 0.985f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(delayMs)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator())
            .start()
        if (withBlur) applySingleShotBlur(view, delayMs)
    }

    /** Transición entre pasos: misma entrada, un poco más corta. */
    fun transition(view: View, durationMs: Long = 260L) {
        animate(view, delayMs = 0L, durationMs = durationMs, slideDp = 10f)
    }

    /**
     * Desenfoque de un tiro: se aplica una vez al arrancar la entrada y se
     * retira a los [BLUR_WINDOW_MS]. Sin animadores de blur por frame.
     */
    private fun applySingleShotBlur(view: View, delayMs: Long) {        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        view.postDelayed({
            view.setRenderEffect(
                RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP),
            )
            view.postDelayed({ view.setRenderEffect(null) }, BLUR_WINDOW_MS)
        }, delayMs)
    }

    /** ¿El usuario desactivó las animaciones (accesibilidad) o el sistema las escala a 0? */
    private fun animationsDisabled(view: View): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(
            view.context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)
}
