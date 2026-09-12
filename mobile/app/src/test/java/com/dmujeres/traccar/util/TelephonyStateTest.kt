package com.dmujeres.traccar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tabla de refine(base, tel): certeza anti-trampas con telefonía opcional.
 * refine es pura (sin Context), así que corre en unit tests JVM.
 */
class TelephonyStateTest {

    private fun noPerm() = TelInfo(
        dataEnabled = null,
        simPresent = null,
        service = null,
        hasPermission = false,
    )

    private fun withPerm(
        dataEnabled: Boolean? = null,
        simPresent: Boolean? = null,
        service: String? = null,
    ) = TelInfo(
        dataEnabled = dataEnabled,
        simPresent = simPresent,
        service = service,
        hasPermission = true,
    )

    @Test
    fun noPermissionFactoryHasNulls() {
        assertEquals(TelInfo(null, null, null, false), TelInfo.noPermission())
    }

    @Test
    fun withoutPermissionKeepsBaseAsSuspected() {
        listOf(
            NetCause.OK,
            NetCause.WIFI_LOST,
            NetCause.MOBILE_DATA_OFF_SUSPECTED,
            NetCause.NO_COVERAGE_SUSPECTED,
        ).forEach { base ->
            assertEquals("$base sin permiso es sospecha", base to "suspected", refine(base, noPerm()))
        }
    }

    @Test
    fun withoutPermissionCertainCausesStayConfirmed() {
        listOf(
            NetCause.WIFI_OFF_USER,
            NetCause.AIRPLANE,
            NetCause.CAPTIVE_SUSPECTED,
            NetCause.NO_INTERNET,
        ).forEach { base ->
            assertEquals("$base sin permiso ya es cierta", base to "confirmed", refine(base, noPerm()))
        }
    }

    @Test
    fun withPermissionDataOffNeedsTransitionForAccusation() {
        // Transición observada on->off = manipulación manual cierta.
        assertEquals(
            NetCause.MOBILE_DATA_OFF_USER to "confirmed",
            refine(
                NetCause.NO_COVERAGE_SUSPECTED,
                withPerm(dataEnabled = false),
                previousDataEnabled = true,
            ),
        )
        assertEquals(
            NetCause.MOBILE_DATA_OFF_USER to "confirmed",
            refine(
                NetCause.MOBILE_DATA_OFF_SUSPECTED,
                withPerm(dataEnabled = false, simPresent = true, service = "in_service"),
                previousDataEnabled = true,
            ),
        )
        // Switch apagado crónico o sin lectura previa: NO se acusa; se queda
        // la base (sin cobertura) como sospecha.
        assertEquals(
            NetCause.NO_COVERAGE_SUSPECTED to "suspected",
            refine(NetCause.NO_COVERAGE_SUSPECTED, withPerm(dataEnabled = false)),
        )
        assertEquals(
            NetCause.NO_COVERAGE_SUSPECTED to "suspected",
            refine(
                NetCause.NO_COVERAGE_SUSPECTED,
                withPerm(dataEnabled = false),
                previousDataEnabled = false,
            ),
        )
    }

    @Test
    fun withPermissionMissingSimIsConfirmed() {
        assertEquals(
            NetCause.SIM_MISSING to "confirmed",
            refine(
                NetCause.NO_COVERAGE_SUSPECTED,
                withPerm(dataEnabled = true, simPresent = false, service = "unknown"),
            ),
        )
    }

    @Test
    fun simMissingWinsOverDataSwitch() {
        // Certeza física: sin chip no hay dato posible, el switch no importa.
        assertEquals(
            NetCause.SIM_MISSING to "confirmed",
            refine(
                NetCause.NO_COVERAGE_SUSPECTED,
                withPerm(dataEnabled = false, simPresent = false),
                previousDataEnabled = true,
            ),
        )
        assertEquals(
            NetCause.SIM_MISSING to "confirmed",
            refine(NetCause.NO_COVERAGE_SUSPECTED, withPerm(dataEnabled = false, simPresent = false)),
        )
    }

    @Test
    fun withPermissionNoServiceIsCoverageConfirmed() {
        assertEquals(
            NetCause.NO_COVERAGE_SUSPECTED to "confirmed",
            refine(
                NetCause.NO_COVERAGE_SUSPECTED,
                withPerm(dataEnabled = true, simPresent = true, service = "out_of_service"),
            ),
        )
        assertEquals(
            NetCause.NO_COVERAGE_SUSPECTED to "confirmed",
            refine(
                NetCause.NO_COVERAGE_SUSPECTED,
                withPerm(dataEnabled = true, simPresent = true, service = "emergency"),
            ),
        )
    }

    @Test
    fun withPermissionInServiceStaysSuspected() {
        assertEquals(
            NetCause.NO_COVERAGE_SUSPECTED to "suspected",
            refine(
                NetCause.NO_COVERAGE_SUSPECTED,
                withPerm(dataEnabled = true, simPresent = true, service = "in_service"),
            ),
        )
    }

    @Test
    fun withPermissionUnknownTelephonyStaysSuspected() {
        assertEquals(
            NetCause.NO_COVERAGE_SUSPECTED to "suspected",
            refine(NetCause.NO_COVERAGE_SUSPECTED, withPerm()),
        )
    }

    @Test
    fun certainBaseCausesAreNotOverriddenByTelephony() {
        // En avión la radio está apagada: out_of_service no puede reclasificar a cobertura.
        val tel = withPerm(dataEnabled = true, simPresent = true, service = "out_of_service")
        assertEquals(NetCause.AIRPLANE to "confirmed", refine(NetCause.AIRPLANE, tel))
        assertEquals(NetCause.WIFI_OFF_USER to "confirmed", refine(NetCause.WIFI_OFF_USER, tel))
        assertEquals(NetCause.CAPTIVE_SUSPECTED to "confirmed", refine(NetCause.CAPTIVE_SUSPECTED, tel))
        assertEquals(NetCause.NO_INTERNET to "confirmed", refine(NetCause.NO_INTERNET, tel))
    }

    @Test
    fun certainCausesStayConfirmedWithPermission() {
        val tel = withPerm(dataEnabled = true, simPresent = true, service = "in_service")
        assertEquals(NetCause.AIRPLANE to "confirmed", refine(NetCause.AIRPLANE, tel))
        assertEquals(NetCause.WIFI_OFF_USER to "confirmed", refine(NetCause.WIFI_OFF_USER, tel))
    }

    @Test
    fun newCauseValuesAreExactStrings() {
        assertEquals("sim_missing", NetCause.SIM_MISSING.value)
        assertEquals("mobile_data_off_user", NetCause.MOBILE_DATA_OFF_USER.value)
        assertEquals(NetCause.SIM_MISSING, NetCause.fromValue("sim_missing"))
        assertEquals(NetCause.MOBILE_DATA_OFF_USER, NetCause.fromValue("mobile_data_off_user"))
    }

    @Test
    fun newCauseMessagesInSpanishWithoutWifiAction() {
        assertEquals(
            "Sin chip — revisa tu tarjeta SIM para seguir enviando",
            NetCause.SIM_MISSING.userMessage(),
        )
        assertEquals(
            "Apagaste los datos — actívalos para seguir enviando",
            NetCause.MOBILE_DATA_OFF_USER.userMessage(),
        )
        assertFalse(NetCause.SIM_MISSING.opensWifiSettings())
        assertFalse(NetCause.MOBILE_DATA_OFF_USER.opensWifiSettings())
    }

    @Test
    fun confidenceConstantsMatchWireValues() {
        assertEquals("confirmed", NETCONF_CONFIRMED)
        assertEquals("suspected", NETCONF_SUSPECTED)
        assertEquals("in_service", TEL_SERVICE_IN_SERVICE)
        assertEquals("out_of_service", TEL_SERVICE_OUT_OF_SERVICE)
        assertEquals("emergency", TEL_SERVICE_EMERGENCY)
        assertEquals("unknown", TEL_SERVICE_UNKNOWN)
    }

    @Test
    fun existingValuesUnchanged() {
        assertEquals("ok", NetCause.OK.value)
        assertEquals("mobile_data_off_suspected", NetCause.MOBILE_DATA_OFF_SUSPECTED.value)
        assertEquals("no_coverage_suspected", NetCause.NO_COVERAGE_SUSPECTED.value)
        assertNull(NetCause.OK.userMessage())
        assertTrue(NetCause.WIFI_OFF_USER.opensWifiSettings())
    }
}
