package com.dmujeres.traccar.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings

/**
 * Causa probable de pérdida de red (Fase 1, sin fricción: sin READ_PHONE_STATE).
 *
 * Todo lo que necesita permiso peligroso está prohibido aquí. Solo se lee:
 * - Settings.Global.WIFI_ON (wifi habilitado por el usuario)
 * - Settings.Global.AIRPLANE_MODE_ON (modo avión)
 * - NetworkCapabilities (transportes + VALIDATED + CAPTIVE_PORTAL)
 * - Settings.Global.MOBILE_DATA como heurístico de "sospecha" (ver abajo).
 *
 * ACCESS_WIFI_STATE es permiso normal (sin diálogo) y no se usa directamente
 * porque WIFI_ON ya cubre "wifi apagado por el usuario" sin fricción.
 */
enum class NetCause(val value: String) {
    OK("ok"),
    WIFI_OFF_USER("wifi_off_user"),
    WIFI_LOST("wifi_lost"),
    MOBILE_DATA_OFF_SUSPECTED("mobile_data_off_suspected"),
    /** Certeza con READ_PHONE_STATE (refine): el switch de datos está apagado. */
    MOBILE_DATA_OFF_USER("mobile_data_off_user"),
    NO_COVERAGE_SUSPECTED("no_coverage_suspected"),
    /** Certeza con READ_PHONE_STATE (refine): no hay SIM presente. */
    SIM_MISSING("sim_missing"),
    AIRPLANE("airplane"),
    NO_INTERNET("no_internet"),
    CAPTIVE_SUSPECTED("captive_suspected");

    /** Mensaje humano (español, no técnico) para la UI. null = todo bien, no mostrar. */
    fun userMessage(): String? = when (this) {
        OK -> null
        WIFI_OFF_USER -> "Apagaste el WiFi — actívalo para no perder recorrido"
        WIFI_LOST -> "Se perdió la señal WiFi — acércate al router"
        MOBILE_DATA_OFF_SUSPECTED -> "Parece que los datos están apagados — actívalos para seguir enviando"
        MOBILE_DATA_OFF_USER -> "Apagaste los datos — actívalos para seguir enviando"
        NO_COVERAGE_SUSPECTED -> "Sin cobertura — guardamos tu recorrido, se enviará solo"
        SIM_MISSING -> "Sin chip — revisa tu tarjeta SIM para seguir enviando"
        AIRPLANE -> "Modo avión activado — desactívalo para seguir enviando"
        NO_INTERNET -> "Conectado sin internet — revisa tu conexión"
        CAPTIVE_SUSPECTED -> "WiFi sin acceso — quizá pide iniciar sesión"
    }

    /** true si tiene sentido llevar al usuario a Ajustes WiFi. */
    fun opensWifiSettings(): Boolean = when (this) {
        WIFI_OFF_USER, WIFI_LOST, CAPTIVE_SUSPECTED, NO_INTERNET -> true
        else -> false
    }

    companion object {
        fun fromValue(value: String?): NetCause? =
            entries.firstOrNull { it.value == value }

        /**
         * Lógica pura y testeable. Orden estricto (especificación Fase 1):
         * 1. airplane == true → [AIRPLANE] (aunque haya red validada: el usuario lo activó).
         * 2. !validated && captive → [CAPTIVE_SUSPECTED].
         * 3. !validated && (wifi||cell) → [NO_INTERNET] (conectado sin internet).
         * 4. red validada → [OK].
         * 5. sin transporte usable ("none"):
         *    - wifiOn == false → [WIFI_OFF_USER].
         *    - wifiOn == true + mobileDataOffSuspected → [MOBILE_DATA_OFF_SUSPECTED].
         *    - wifiOn == true + previousLabel == "wifi" → [WIFI_LOST].
         *    - en otro caso → [NO_COVERAGE_SUSPECTED].
         *
         * @param mobileDataOffSuspected heurístico: true si Settings.Global MOBILE_DATA == 0.
         * Se llama "sospecha" porque esa clave es dependiente de OEM/versión, puede no
         * existir o no reflejar el switch real en todos los dispositivos; nunca se usa
         * para bloquear nada, solo para el mensaje humano.
         * @param previousLabel última etiqueta network conocida ("wifi"|"mobile"|"none"|"").
         * Sirve para distinguir "se perdió el wifi" ([WIFI_LOST]) de "sin cobertura"
         * ([NO_COVERAGE_SUSPECTED]) cuando no hay transporte usable y el wifi sigue on.
         */
        fun detect(
            wifiOn: Boolean,
            airplane: Boolean,
            hasWifiTransport: Boolean,
            hasCellTransport: Boolean,
            validated: Boolean,
            captive: Boolean,
            previousLabel: String,
            mobileDataOffSuspected: Boolean = false,
        ): NetCause {
            if (airplane) return AIRPLANE
            if (!validated && captive) return CAPTIVE_SUSPECTED
            if (!validated && (hasWifiTransport || hasCellTransport)) return NO_INTERNET
            if (validated) return OK
            // Sin red usable (none). Los casos con transporte sin validar ya salieron arriba.
            if (!hasWifiTransport && !hasCellTransport) {
                if (!wifiOn) return WIFI_OFF_USER
                if (mobileDataOffSuspected) return MOBILE_DATA_OFF_SUSPECTED
                if (previousLabel == "wifi") return WIFI_LOST
                return NO_COVERAGE_SUSPECTED
            }
            // Defensivo, inalcanzable con el orden de arriba.
            if (!wifiOn && !hasWifiTransport) return WIFI_OFF_USER
            return NO_INTERNET
        }

        fun detect(snapshot: NetSnapshot): NetCause = detect(
            wifiOn = snapshot.wifiOn,
            airplane = snapshot.airplane,
            hasWifiTransport = snapshot.hasWifiTransport,
            hasCellTransport = snapshot.hasCellTransport,
            validated = snapshot.validated,
            captive = snapshot.captive,
            previousLabel = snapshot.previousLabel,
            mobileDataOffSuspected = snapshot.mobileDataOffSuspected,
        )
    }
}

/**
 * Función top-level pura (alias de [NetCause.detect]) para la firma pedida:
 * detect(wifiOn, airplane, hasWifiTransport, hasCellTransport, validated, captive, previousLabel).
 */
fun detect(
    wifiOn: Boolean,
    airplane: Boolean,
    hasWifiTransport: Boolean,
    hasCellTransport: Boolean,
    validated: Boolean,
    captive: Boolean,
    previousLabel: String,
): NetCause = NetCause.detect(
    wifiOn = wifiOn,
    airplane = airplane,
    hasWifiTransport = hasWifiTransport,
    hasCellTransport = hasCellTransport,
    validated = validated,
    captive = captive,
    previousLabel = previousLabel,
    mobileDataOffSuspected = false,
)

/** Foto del estado de red leída sin permisos peligrosos. */
data class NetSnapshot(
    val wifiOn: Boolean,
    val airplane: Boolean,
    val hasWifiTransport: Boolean,
    val hasCellTransport: Boolean,
    val validated: Boolean,
    val captive: Boolean,
    val previousLabel: String = "",
    val mobileDataOffSuspected: Boolean = false,
)

/**
 * Lee [NetSnapshot] desde el sistema (no puro: toca Settings.Global + ConnectivityManager).
 * Sin fricción: no pide ningún permiso en tiempo de ejecución.
 */
fun snapshot(
    context: Context,
    previousLabel: String = "",
): NetSnapshot {
    val resolver = context.contentResolver
    val wifiOn = runCatching {
        Settings.Global.getInt(resolver, Settings.Global.WIFI_ON, 1) == 1
    }.getOrDefault(true)
    val airplane = runCatching {
        Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    }.getOrDefault(false)
    // Heurístico "sospecha": MOBILE_DATA == 0 sugiere datos apagados, pero la clave
    // es OEM-dependiente y puede no existir; por defecto false (no sospecha).
    val mobileDataOffSuspected = runCatching {
        Settings.Global.getInt(resolver, "mobile_data", 1) == 0
    }.getOrDefault(false)
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps: NetworkCapabilities? = runCatching {
        cm.getNetworkCapabilities(cm.activeNetwork)
    }.getOrNull()
    return NetSnapshot(
        wifiOn = wifiOn,
        airplane = airplane,
        hasWifiTransport = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
        hasCellTransport = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
        validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        captive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        previousLabel = previousLabel,
        mobileDataOffSuspected = mobileDataOffSuspected,
    )
}
