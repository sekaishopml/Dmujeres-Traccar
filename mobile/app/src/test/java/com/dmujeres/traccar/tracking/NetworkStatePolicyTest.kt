package com.dmujeres.traccar.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

/** Caracteriza etiquetas de red/proveedor y umbral de fix rancio. */
class NetworkStatePolicyTest {

    @Test
    fun networkLabelRequiresValidatedInternet() {
        assertEquals("none", NetworkStatePolicy.label(validated = false, hasWifiTransport = true))
        assertEquals("none", NetworkStatePolicy.label(validated = false, hasWifiTransport = false))
        assertEquals("wifi", NetworkStatePolicy.label(validated = true, hasWifiTransport = true))
        assertEquals("mobile", NetworkStatePolicy.label(validated = true, hasWifiTransport = false))
    }

    @Test
    fun validatedLabelKeepsPreviousWithoutKnownTransport() {
        assertEquals("wifi", NetworkStatePolicy.validatedLabel("mobile", hasWifiTransport = true, hasCellTransport = true))
        assertEquals("mobile", NetworkStatePolicy.validatedLabel("wifi", hasWifiTransport = false, hasCellTransport = true))
        assertEquals("wifi", NetworkStatePolicy.validatedLabel("wifi", hasWifiTransport = false, hasCellTransport = false))
    }

    @Test
    fun providerLabelMatchesServerContract() {
        assertEquals("gps", NetworkStatePolicy.providerLabel("gps"))
        assertEquals("gps", NetworkStatePolicy.providerLabel("GPS"))
        assertEquals("network", NetworkStatePolicy.providerLabel("network"))
        assertEquals("fused", NetworkStatePolicy.providerLabel("fused"))
        assertEquals("unknown", NetworkStatePolicy.providerLabel(null))
        assertEquals("unknown", NetworkStatePolicy.providerLabel(""))
        assertEquals("fused", NetworkStatePolicy.providerLabel("passive"))
    }

    @Test
    fun fixStaleAfterFloorIsSixtySeconds() {
        assertEquals(60_000L, NetworkStatePolicy.fixStaleAfterMs(3))
        assertEquals(60_000L, NetworkStatePolicy.fixStaleAfterMs(19))
        assertEquals(60_000L, NetworkStatePolicy.fixStaleAfterMs(20))
        assertEquals(180_000L, NetworkStatePolicy.fixStaleAfterMs(60))
        assertEquals(3_000_000L, NetworkStatePolicy.fixStaleAfterMs(1_000L))
    }
}
