package org.traccar.client

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd

/**
 * Entrada suave de las pantallas del asistente: aparece con un desvanecido,
 * un desplazamiento corto y un desenfoque que se disuelve (blur real desde
 * Android 12). Al terminar se retira el efecto para no dejar trabajo extra a
 * la GPU: es una animación corta y de un solo tiro, no cuesta batería.
 */
object SoftEntrance {

    private const val BLUR_START = 16f

    fun animate(
        view: View,
        delayMs: Long = 0L,
        durationMs: Long = 520L,
        slideDp: Float = 16f,
        withBlur: Boolean = true,
    ) {
        val density = view.resources.displayMetrics.density
        view.alpha = 0f
        view.translationY = slideDp * density
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator())
            .start()
        animateBlur(view, delayMs, durationMs, withBlur)
    }

    /** Transición entre pasos: entra con un desenfoque más corto y leve. */
    fun transition(view: View, durationMs: Long = 300L) {
        animate(view, delayMs = 0L, durationMs = durationMs, slideDp = 10f, withBlur = true)
    }

    private fun animateBlur(view: View, delayMs: Long, durationMs: Long, enabled: Boolean) {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val animator = android.animation.ValueAnimator.ofFloat(BLUR_START, 0f)
        animator.duration = durationMs
        animator.startDelay = delayMs
        animator.addUpdateListener { value ->
            val radius = value.animatedValue as Float
            view.setRenderEffect(
                if (radius > 0.5f) {
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                } else {
                    null
                },
            )
        }
        animator.doOnEnd { view.setRenderEffect(null) }
        animator.start()
    }
}
