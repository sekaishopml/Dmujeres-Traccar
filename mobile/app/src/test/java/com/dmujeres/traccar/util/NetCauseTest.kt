package com.dmujeres.traccar.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NetCauseTest {

    data class Case(
        val name: String,
        val wifiOn: Boolean,
        val airplane: Boolean,
        val hasWifi: Boolean,
        val hasCell: Boolean,
        val validated: Boolean,
        val captive: Boolean,
        val previous: String,
        val mobileOff: Boolean = false,
        val expected: NetCause,
    )

    @Test
    fun detectionTable() {
        val cases = listOf(
            Case("validada wifi ok", true, false, true, false, true, false, "wifi", false, NetCause.OK),
            Case("validada mobile ok", true, false, false, true, true, false, "mobile", false, NetCause.OK),
            Case("avion gana aunque haya red", true, true, true, false, true, false, "wifi", false, NetCause.AIRPLANE),
            Case("avion sin red", true, true, false, false, false, false, "", false, NetCause.AIRPLANE),
            Case("captive sin validar", true, false, true, false, false, true, "wifi", false, NetCause.CAPTIVE_SUSPECTED),
            Case("wifi sin validar -> no_internet", true, false, true, false, false, false, "wifi", false, NetCause.NO_INTERNET),
            Case("cell sin validar -> no_internet", true, false, false, true, false, false, "mobile", false, NetCause.NO_INTERNET),
            Case("sin nada wifi off -> wifi_off_user", false, false, false, false, false, false, "", false, NetCause.WIFI_OFF_USER),
            Case("sin nada wifi off prev wifi -> wifi_off_user", false, false, false, false, false, false, "wifi", false, NetCause.WIFI_OFF_USER),
            Case("sin nada wifi on prev wifi -> wifi_lost", true, false, false, false, false, false, "wifi", false, NetCause.WIFI_LOST),
            Case("sin nada wifi on sin previo -> no_coverage", true, false, false, false, false, false, "", false, NetCause.NO_COVERAGE_SUSPECTED),
            Case("sin nada wifi on prev mobile -> no_coverage", true, false, false, false, false, false, "mobile", false, NetCause.NO_COVERAGE_SUSPECTED),
            Case("datos apagados sospecha", true, false, false, false, false, false, "mobile", true, NetCause.MOBILE_DATA_OFF_SUSPECTED),
            Case("datos apagados no pisa avion", true, true, false, false, false, false, "", true, NetCause.AIRPLANE),
            Case("datos apagados no pisa ok", true, false, false, true, true, false, "mobile", true, NetCause.OK),
        )
        cases.forEach { c ->
            val got = NetCause.detect(
                wifiOn = c.wifiOn,
                airplane = c.airplane,
                hasWifiTransport = c.hasWifi,
                hasCellTransport = c.hasCell,
                validated = c.validated,
                captive = c.captive,
                previousLabel = c.previous,
                mobileDataOffSuspected = c.mobileOff,
            )
            assertEquals("${c.name}: esperado ${c.expected.value}", c.expected, got)
        }
    }

    @Test
    fun topLevelDetectMatchesCompanion() {
        val got = detect(true, false, false, false, false, false, "wifi")
        assertEquals(NetCause.WIFI_LOST, got)
    }

    @Test
    fun valuesAreExactStrings() {
        assertEquals("ok", NetCause.OK.value)
        assertEquals("wifi_off_user", NetCause.WIFI_OFF_USER.value)
        assertEquals("wifi_lost", NetCause.WIFI_LOST.value)
        assertEquals("mobile_data_off_suspected", NetCause.MOBILE_DATA_OFF_SUSPECTED.value)
        assertEquals("no_coverage_suspected", NetCause.NO_COVERAGE_SUSPECTED.value)
        assertEquals("airplane", NetCause.AIRPLANE.value)
        assertEquals("no_internet", NetCause.NO_INTERNET.value)
        assertEquals("captive_suspected", NetCause.CAPTIVE_SUSPECTED.value)
    }

    @Test
    fun userMessagesInSpanish() {
        assertEquals("Apagaste el WiFi — actívalo para no perder recorrido", NetCause.WIFI_OFF_USER.userMessage())
        assertEquals("Modo avión activado — desactívalo para seguir enviando", NetCause.AIRPLANE.userMessage())
        assertEquals("Sin cobertura — guardamos tu recorrido, se enviará solo", NetCause.NO_COVERAGE_SUSPECTED.userMessage())
        assertEquals("WiFi sin acceso — quizá pide iniciar sesión", NetCause.CAPTIVE_SUSPECTED.userMessage())
        assertEquals("Se perdió la señal WiFi — acércate al router", NetCause.WIFI_LOST.userMessage())
        assertEquals("Conectado sin internet — revisa tu conexión", NetCause.NO_INTERNET.userMessage())
        assertEquals(null, NetCause.OK.userMessage())
    }

    @Test
    fun fromValueRoundTrip() {
        NetCause.entries.forEach {
            assertEquals(it, NetCause.fromValue(it.value))
        }
    }
}
