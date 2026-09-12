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

    /**
     * Precisión nuevo detect(): "el usuario apagó X" vs "perdió señal". Los defaults
     * de los nuevos parámetros mantienen la tabla vieja (atrás) intacta.
     */
    @Test
    fun precisionTable() {
        data class PCase(
            val name: String,
            val expected: NetCause,
            val wifiOn: Boolean = true,
            val airplane: Boolean = false,
            val hasWifi: Boolean = false,
            val hasCell: Boolean = false,
            val validated: Boolean = false,
            val captive: Boolean = false,
            val previous: String = "",
            val mobileOff: Boolean = false,
            val wifiNetworkExists: Boolean = false,
            val dataEnabled: Boolean? = null,
            val dataState: Int? = null,
            val simAbsent: Boolean? = null,
            // Último switch conocido: solo true→false autoriza "apagaste".
            val prevData: Boolean? = null,
        )
        val cases = listOf(
            // "Apagaste el WiFi" exige switch-off + CERO redes WiFi; WIFI_ON solo no basta.
            PCase("wifi off sin red alguna -> apagaste", NetCause.WIFI_OFF_USER, wifiOn = false),
            PCase("WIFI_ON obsoleto con red wifi existiendo -> no es 'apagaste'", NetCause.WIFI_LOST, wifiOn = false, wifiNetworkExists = true, previous = "wifi"),
            // Datos apagados: SOLO la transición true→false prueba manipulación
            // manual. Switch apagado crónico (vivir en WiFi) + blip de red NO es
            // "apagaste": es sin cobertura (acusación grave exige certeza).
            PCase("switch off sin previo -> sin cobertura, no acusación", NetCause.NO_COVERAGE_SUSPECTED, dataEnabled = false),
            PCase("switch off estable (prev false) -> sin cobertura", NetCause.NO_COVERAGE_SUSPECTED, dataEnabled = false, prevData = false),
            PCase("transición on->off observada -> apagaste (certeza)", NetCause.MOBILE_DATA_OFF_USER, dataEnabled = false, prevData = true),
            PCase("switch off pisa al heurístico OEM solo con transición", NetCause.MOBILE_DATA_OFF_USER, mobileOff = true, dataEnabled = false, prevData = true),
            PCase("switch off + heurístico sin transición -> sin cobertura", NetCause.NO_COVERAGE_SUSPECTED, mobileOff = true, dataEnabled = false),
            PCase("heurístico solo -> sospecha", NetCause.MOBILE_DATA_OFF_SUSPECTED, mobileOff = true, dataEnabled = null),
            // "Encendidos pero sin antena" ≠ "apagados".
            PCase("datos on + state NONE -> sin cobertura", NetCause.NO_COVERAGE_SUSPECTED, dataEnabled = true, dataState = TEL_DATA_STATE_NONE),
            PCase("datos on + out_of_service -> sin cobertura", NetCause.NO_COVERAGE_SUSPECTED, dataEnabled = true, dataState = TEL_DATA_STATE_OUT_OF_SERVICE),
            PCase("datos on + disconnected -> operador pausó", NetCause.DATA_SUSPENDED, dataEnabled = true, dataState = TEL_DATA_STATE_DISCONNECTED),
            PCase("datos on + suspended -> operador pausó", NetCause.DATA_SUSPENDED, dataEnabled = true, dataState = TEL_DATA_STATE_SUSPENDED),
            // SIM ausente (certeza física) gana a sospechas de switch.
            PCase("sim ausente -> sim_missing", NetCause.SIM_MISSING, simAbsent = true),
            PCase("sim ausente gana a sospecha mobile_data", NetCause.SIM_MISSING, mobileOff = true, simAbsent = true),
            PCase("sim ausente gana a certeza de datos off", NetCause.SIM_MISSING, dataEnabled = false, simAbsent = true),
            // Precedencia: modo avión SIEMPRE primero, aunque todo lo altro grite.
            PCase("avion gana a datos off", NetCause.AIRPLANE, airplane = true, dataEnabled = false),
            PCase("avion gana a sim ausente", NetCause.AIRPLANE, airplane = true, simAbsent = true),
            PCase("avion gana a wifi off user", NetCause.AIRPLANE, airplane = true, wifiOn = false),
            // Red valida: ninguna certeza de switch debe opacar el OK.
            PCase("valida con datos off sigue OK", NetCause.OK, hasWifi = true, validated = true, previous = "wifi", dataEnabled = false),
            // WiFi perdido con todo encendido sigue siendo "señal", no "apagaste".
            PCase("wifi on, prev wifi, sin certeza -> wifi_lost", NetCause.WIFI_LOST, wifiOn = true, previous = "wifi"),
            // captive/no_internet inalterados.
            PCase("captive gana a certezas de transporte", NetCause.CAPTIVE_SUSPECTED, captive = true, hasWifi = true, dataEnabled = false),
            PCase("sin validar con transporte -> no_internet", NetCause.NO_INTERNET, hasCell = true, dataEnabled = false),
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
                wifiNetworkExists = c.wifiNetworkExists,
                dataEnabled = c.dataEnabled,
                dataState = c.dataState,
                simAbsent = c.simAbsent,
                previousDataEnabled = c.prevData,
            )
            assertEquals("${c.name}: esperado ${c.expected.value}", c.expected, got)
        }
    }

    @Test
    fun snapshotOverloadCarriesNewFields() {
        val shot = NetSnapshot(
            wifiOn = true,
            airplane = false,
            hasWifiTransport = false,
            hasCellTransport = false,
            validated = false,
            captive = false,
            dataEnabled = true,
            dataState = TEL_DATA_STATE_SUSPENDED,
        )
        assertEquals(NetCause.DATA_SUSPENDED, NetCause.detect(shot))
        // Defaults del data class = comportamiento Fase 1.
        val legacy = NetSnapshot(
            wifiOn = false,
            airplane = false,
            hasWifiTransport = false,
            hasCellTransport = false,
            validated = false,
            captive = false,
        )
        assertEquals(NetCause.WIFI_OFF_USER, NetCause.detect(legacy))
    }

    @Test
    fun valuesAreExactStrings() {
        assertEquals("ok", NetCause.OK.value)
        assertEquals("wifi_off_user", NetCause.WIFI_OFF_USER.value)
        assertEquals("wifi_lost", NetCause.WIFI_LOST.value)
        assertEquals("mobile_data_off_suspected", NetCause.MOBILE_DATA_OFF_SUSPECTED.value)
        assertEquals("no_coverage_suspected", NetCause.NO_COVERAGE_SUSPECTED.value)
        assertEquals("data_suspended", NetCause.DATA_SUSPENDED.value)
        assertEquals("airplane", NetCause.AIRPLANE.value)
        assertEquals("no_internet", NetCause.NO_INTERNET.value)
        assertEquals("captive_suspected", NetCause.CAPTIVE_SUSPECTED.value)
    }

    @Test
    fun userMessagesInSpanish() {
        assertEquals("Apagaste el WiFi — actívalo para no perder recorrido", NetCause.WIFI_OFF_USER.userMessage())
        assertEquals("Modo avión activado — desactívalo para seguir enviando", NetCause.AIRPLANE.userMessage())
        assertEquals("Sin cobertura — guardamos tu recorrido, se enviará solo", NetCause.NO_COVERAGE_SUSPECTED.userMessage())
        assertEquals(
            "El operador pausó tus datos — revisa tu plan; guardamos tu recorrido",
            NetCause.DATA_SUSPENDED.userMessage(),
        )
        assertEquals("WiFi sin acceso — quizá pide iniciar sesión", NetCause.CAPTIVE_SUSPECTED.userMessage())
        assertEquals("Se perdió la señal WiFi — acércate al router", NetCause.WIFI_LOST.userMessage())
        assertEquals("Conectado sin internet — revisa tu conexión", NetCause.NO_INTERNET.userMessage())
        assertEquals(null, NetCause.OK.userMessage())
    }

    @Test
    fun dataSuspendedDoesNotOpenWifiSettings() {
        assertEquals(false, NetCause.DATA_SUSPENDED.opensWifiSettings())
        assertEquals(NetCause.DATA_SUSPENDED, NetCause.fromValue("data_suspended"))
    }

    @Test
    fun fromValueRoundTrip() {
        NetCause.entries.forEach {
            assertEquals(it, NetCause.fromValue(it.value))
        }
    }
}
