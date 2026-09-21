package com.dmujeres.traccar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del estado del fallback GPS (GnssFallbackPolicy): el bug real era el
 * registro desde un thread sin Looper (Dispatchers.Default) que lanzaba
 * `Can't create handler inside thread` y dejaba el fallback SIN registrar.
 * La corrección usa el overload con Looper explícito; la política cubre el
 * ciclo de vida del estado (register/unregister/duplicados/permiso).
 */
class GnssFallbackPolicyTest {

    private fun clean() = GnssFallbackPolicy.State(
        registered = false,
        fineLocationGranted = true,
    )

    @Test
    fun `flp disponible - fallback registrado una sola vez`() {
        var state = clean()
        assertTrue(GnssFallbackPolicy.shouldRegister(state))
        state = GnssFallbackPolicy.registered(state)
        // segundo intento (watchdog re-lanza): NO duplica registro
        assertFalse(GnssFallbackPolicy.shouldRegister(state))
    }

    @Test
    fun `servicio stop - unregister correcto`() {
        var state = GnssFallbackPolicy.registered(clean())
        assertTrue(GnssFallbackPolicy.shouldUnregister(state))
        state = GnssFallbackPolicy.unregistered(state)
        assertFalse(state.registered)
        // tras unregister se puede volver a registrar (reinicio de sesión)
        assertTrue(GnssFallbackPolicy.shouldRegister(state))
    }

    @Test
    fun `permiso faltante - no intenta registrar`() {
        val state = clean().copy(fineLocationGranted = false)
        assertFalse(GnssFallbackPolicy.shouldRegister(state))
    }

    @Test
    fun `registro fallido - error registrado y permite reintento`() {
        val failed = GnssFallbackPolicy.failure(clean(), "Can't create handler inside thread")
        assertFalse(failed.registered)
        assertTrue(failed.lastError.contains("handler"))
        // el watchdog reintenta en el siguiente tick: permitido
        assertTrue(GnssFallbackPolicy.shouldRegister(failed))
    }

    @Test
    fun `multiple sesiones - estado limpio tras unregister`() {
        var state = GnssFallbackPolicy.registered(clean())
        state = GnssFallbackPolicy.unregistered(state)
        state = GnssFallbackPolicy.registered(state)
        assertTrue(state.registered)
        state = GnssFallbackPolicy.unregistered(state)
        assertFalse(state.registered)
        assertEquals("", state.lastError)
    }

    @Test
    fun `no registrado - no intenta unregister`() {
        assertFalse(GnssFallbackPolicy.shouldUnregister(clean()))
    }
}
