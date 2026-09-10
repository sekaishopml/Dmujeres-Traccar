package com.dmujeres.traccar.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test de la única lógica pura de MetricsRow: la escala de la fuente adaptativa
 * (FitText). Sin Android: se trabaja directamente en px.
 */
class FitFontScaleTest {

    // titleMedium 16 sp a densidad 3 (48 px de base); suelos en escala = minSp/baseSp.
    private val minScaleValue = 11f / 16f

    @Test
    fun `si cabe no se toca el tamano base`() {
        val scale = fitFontScale(textWidthAtBasePx = 60f, availableWidthPx = 90f, minScale = minScaleValue, maxScale = 1.15f)
        // Sobra espacio: solo puede crecer hasta maxScale.
        assertEquals(1.15f, scale, 0.001f)
    }

    @Test
    fun `encoge lo justo cuando el texto no cabe`() {
        // Caso real del informe: 320 dp, 3 tarjetas -> ~80 dp de hueco para "12 h 34 min".
        val scale = fitFontScale(textWidthAtBasePx = 88f, availableWidthPx = 79f, minScale = minScaleValue, maxScale = 1.15f)
        assertEquals(79f / 88f, scale, 0.001f)
        // 16 sp * 0,898 = ~14,4 sp y por debajo sigue quedando margen hasta 11 sp.
        assertTrue(scale > minScaleValue)
        assertEquals(14.36f, 16f * scale, 0.05f)
    }

    @Test
    fun `nunca baja del suelo aunque sea incomprensible`() {
        val scale = fitFontScale(textWidthAtBasePx = 500f, availableWidthPx = 79f, minScale = minScaleValue, maxScale = 1.15f)
        assertEquals(minScaleValue, scale, 0.001f)
        assertEquals(11f, 16f * scale, 0.01f)
    }

    @Test
    fun `sin medir todavia se deja el tamano base`() {
        // availablePx = 0 en la primera composición (onSizeChanged aún no ha llegado).
        assertEquals(1f, fitFontScale(88f, 0f, minScaleValue, 1.15f), 0.001f)
        assertEquals(1f, fitFontScale(0f, 79f, minScaleValue, 1.15f), 0.001f)
    }

    @Test
    fun `etiquetas con suelo de 9 sp sobre labelSmall de 11 sp`() {
        val minScaleLabel = 9f / 11f
        val scale = fitFontScale(textWidthAtBasePx = 160f, availableWidthPx = 79f, minScale = minScaleLabel, maxScale = 1.15f)
        assertEquals(minScaleLabel, scale, 0.001f)
        assertEquals(9f, 11f * scale, 0.01f)
    }
}
