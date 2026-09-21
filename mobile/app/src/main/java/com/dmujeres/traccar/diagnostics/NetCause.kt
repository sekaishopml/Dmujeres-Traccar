package com.dmujeres.traccar.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * Causa probable de pérdida de red.
 *
 * Fuentes sin permiso peligroso:
 * - Settings.Global.WIFI_ON / AIRPLANE_MODE_ON / MOBILE_DATA (heurísticos, OEM-dependientes).
 * - NetworkCapabilities: transportes de la red activa + cualquier red WiFi existente
 *   (un NetworkAgent con TRANSPORT_WIFI solo existe con el WiFi encendido: distingue
 *   "apagué el WiFi" de "perdí la señal").
 * - TelephonyManager.isDataEnabled (API 30+, SIN permiso: el switch real de datos) y
 *   getDataState (sin permiso: NONE/OUT_OF_SERVICE = sin cobertura, DISCONNECTED/SUSPENDED
 *   = registrado pero el dato caído/pausado).
 * - TelephonyManager.getSimState SOLO con READ_PHONE_STATE concedido (declarado en el
 *   manifest; sin permiso es null, no se miente).
 *
 * Prioridad estricta (ver [NetCause.detect]): avión > certeza de switch (WiFi apagado,
 * SIM ausente, datos apagados por el usuario) > cobertura > heurísticos > wifi perdido.
 */
enum class NetCause(val value: String) {
    OK("ok"),
    WIFI_OFF_USER("wifi_off_user"),
    WIFI_LOST("wifi_lost"),
    MOBILE_DATA_OFF_SUSPECTED("mobile_data_off_suspected"),
    /** Certeza del switch real: isDataEnabled==false (API 30+, sin permiso) o refine con permiso. */
    MOBILE_DATA_OFF_USER("mobile_data_off_user"),
    NO_COVERAGE_SUSPECTED("no_coverage_suspected"),
    /**
     * Datos encendidos y el módem reporta DATA_SUSPENDED/DISCONNECTED sostenido: el
     * operador pausó el servicio (límite de saldo, deuda). Distinto de [NO_COVERAGE_SUSPECTED]
     * (antenas): aquí el mensaje pide revisar el plan, no moverse.
     */
    DATA_SUSPENDED("data_suspended"),
    /** Certeza con READ_PHONE_STATE: no hay SIM presente. */
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
        DATA_SUSPENDED -> "El operador pausó tus datos — revisa tu plan; guardamos tu recorrido"
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
         * Lógica pura y testeable. Orden estricto:
         * 1. airplane == true → [AIRPLANE] (gana a cualquier certeza de switch: el usuario
         *    lo activó y apagarlo resuelve todo).
         * 2. !validated && captive → [CAPTIVE_SUSPECTED].
         * 3. !validated && (wifi||cell) → [NO_INTERNET] (conectado sin internet).
         * 4. red validada → [OK].
         * 5. sin transporte usable ("none"):
         *    - !wifiOn && !wifiNetworkExists → [WIFI_OFF_USER] (el switch apagado SOLO
         *      convence si además no existe ninguna red WiFi: WIFI_ON está deprecado y
         *      puede leerse obsoleto en OEMs; nunca es fuente única).
         *    - simAbsent == true → [SIM_MISSING] (certeza física: sin chip no hay datos).
         *    - dataEnabled == false + previousDataEnabled == true → [MOBILE_DATA_OFF_USER]
         *      (TRANSICIÓN observada encendido→apagado = manipulación manual cierta;
         *      solo así se acusa "apagaste". Con switch apagado de forma estable o
         *      sin lectura previa se informa [NO_COVERAGE_SUSPECTED]: el dato
         *      apagado crónico (p.ej. vivir en WiFi) no prueba que LO APAGARAS ahora).
         *    - dataEnabled == false sin transición → [NO_COVERAGE_SUSPECTED].
         *    - dataEnabled == true + dataState ∈ {NONE, OUT_OF_SERVICE} →
         *      [NO_COVERAGE_SUSPECTED] ("encendidos pero sin antena" ≠ "apagados").
         *    - dataEnabled == true + dataState ∈ {DISCONNECTED, SUSPENDED} →
         *      [DATA_SUSPENDED] (registrado pero el operador cae/pausa el dato).
         *    - mobileDataOffSuspected (solo si no hay certeza de dataEnabled) →
         *      [MOBILE_DATA_OFF_SUSPECTED].
         *    - previousLabel == "wifi" → [WIFI_LOST].
         *    - en otro caso → [NO_COVERAGE_SUSPECTED].
         *
         * @param mobileDataOffSuspected heurístico: true si Settings.Global MOBILE_DATA == 0.
         * Clave OEM-dependiente: solo se usa en ausencia de la certeza de isDataEnabled.
         * @param previousLabel última etiqueta network conocida ("wifi"|"mobile"|"none"|"").
         * @param wifiNetworkExists true si existe algún NetworkAgent con TRANSPORT_WIFI.
         * Con el WiFi apagado no existe: corrobora (junto con WIFI_ON==0) el "Apagaste el WiFi".
         * @param dataEnabled TelephonyManager.isDataEnabled (API 30+, sin permiso); null = sin dato.
         * @param dataState TelephonyManager.dataState (sin permiso); null = sin dato.
         * @param simAbsent TelephonyManager.simState == ABSENT; SOLO con READ_PHONE_STATE,
         * null sin permiso (no se afirma lo que no se puede leer).
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
            wifiNetworkExists: Boolean = false,
            dataEnabled: Boolean? = null,
            dataState: Int? = null,
            simAbsent: Boolean? = null,
            // Último dataEnabled conocido (AppConfig.lastDataEnabled). Solo una
            // transición true→false autoriza "apagaste los datos".
            previousDataEnabled: Boolean? = null,
        ): NetCause {
            if (airplane) return AIRPLANE
            if (!validated && captive) return CAPTIVE_SUSPECTED
            if (!validated && (hasWifiTransport || hasCellTransport)) return NO_INTERNET
            if (validated) return OK
            // Sin red usable (none). Los casos con transporte sin validar ya salieron arriba.
            if (!hasWifiTransport && !hasCellTransport) {
                // "Apagaste el WiFi": solo con switch off + cero redes WiFi existentes.
                if (!wifiOn && !wifiNetworkExists) return WIFI_OFF_USER
                // "El usuario apagó datos" vs "perdió cobertura": primero las certezas.
                if (simAbsent == true) return SIM_MISSING
                if (dataEnabled == false) {
                    return if (previousDataEnabled == true) MOBILE_DATA_OFF_USER
                    else NO_COVERAGE_SUSPECTED
                }
                if (dataEnabled == true && dataState != null) {
                    when (dataState) {
                        TEL_DATA_STATE_NONE, TEL_DATA_STATE_OUT_OF_SERVICE ->
                            return NO_COVERAGE_SUSPECTED
                        TEL_DATA_STATE_DISCONNECTED, TEL_DATA_STATE_SUSPENDED ->
                            return DATA_SUSPENDED
                    }
                }
                // El heurístico MOBILE_DATA==0 solo vale si no hay lectura cierta del switch.
                if (mobileDataOffSuspected && dataEnabled == null) return MOBILE_DATA_OFF_SUSPECTED
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
            wifiNetworkExists = snapshot.wifiNetworkExists,
            dataEnabled = snapshot.dataEnabled,
            dataState = snapshot.dataState,
            simAbsent = snapshot.simAbsent,
            previousDataEnabled = snapshot.previousDataEnabled,
        )
    }
}

/** Valores crudos de TelephonyManager.getDataState() (evita importar android en los tests). */
const val TEL_DATA_STATE_NONE = 0
const val TEL_DATA_STATE_OUT_OF_SERVICE = 1
const val TEL_DATA_STATE_DISCONNECTED = 2
const val TEL_DATA_STATE_SUSPENDED = 3

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
)

/** Foto del estado de red leída sin permisos peligrosos (simState exige READ_PHONE_STATE ya declarado). */
data class NetSnapshot(
    val wifiOn: Boolean,
    val airplane: Boolean,
    val hasWifiTransport: Boolean,
    val hasCellTransport: Boolean,
    val validated: Boolean,
    val captive: Boolean,
    val previousLabel: String = "",
    val mobileDataOffSuspected: Boolean = false,
    /** Existe alguna red (NetworkAgent) con TRANSPORT_WIFI: false con el WiFi apagado. */
    val wifiNetworkExists: Boolean = false,
    /** TelephonyManager.isDataEnabled (API 30+, sin permiso). null = sin lectura. */
    val dataEnabled: Boolean? = null,
    /** TelephonyManager.dataState (sin permiso). null = sin lectura. */
    val dataState: Int? = null,
    /** simState == ABSENT, solo con READ_PHONE_STATE. null = sin permiso/lectura. */
    val simAbsent: Boolean? = null,
    /**
     * Último dataEnabled conocido antes de esta lectura (AppConfig).
     * Solo la transición true → false actual autoriza "apagaste los datos".
     */
    val previousDataEnabled: Boolean? = null,
)

/**
 * Lee [NetSnapshot] desde el sistema (no puro: toca Settings.Global + ConnectivityManager
 * + telefonía sin-permiso). Única fuente de verdad de conectividad (la consumen
 * TrackingService, MainActivity y DiagnosticsCollector solo-lectura).
 * Cada lectura protegida: un fallo OEM degrada a null/false, nunca lanza.
 */
@Suppress("DEPRECATION")
fun snapshot(
    context: Context,
    previousLabel: String = "",
    previousDataEnabled: Boolean? = null,
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
    // Un NetworkAgent WiFi solo existe con el WiFi encendido: distingue "apagado
    // por el usuario" de "apagado por el usuario pero WIFI_ON obsoleto" y de
    // "WiFi on sin señal" (aquí false aunque WIFI_ON==1).
    val wifiNetworkExists = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
        runCatching {
            cm.allNetworks.any { network ->
                runCatching {
                    cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }.getOrDefault(false)
            }
        }.getOrDefault(false)
    val tel = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
    // isDataEnabled: API 30+ y SIN permiso dangerous (el switch real del usuario).
    val dataEnabled: Boolean? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { tel?.isDataEnabled }.getOrNull()
    } else {
        null
    }
    // dataState: público sin permiso. NONE/OUT_OF_SERVICE = sin antena;
    // DISCONNECTED/SUSPENDED con datos on = el operador cae/pausa el dato.
    val dataState: Int? = runCatching { tel?.dataState }.getOrNull()
    // simState necesita READ_PHONE_STATE (ya declarado): sin permiso null.
    val simAbsent: Boolean? = if (
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        runCatching { tel?.simState == TelephonyManager.SIM_STATE_ABSENT }.getOrNull()
    } else {
        null
    }
    return NetSnapshot(
        wifiOn = wifiOn,
        airplane = airplane,
        hasWifiTransport = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
        hasCellTransport = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
        validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        captive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
        previousLabel = previousLabel,
        mobileDataOffSuspected = mobileDataOffSuspected,
        wifiNetworkExists = wifiNetworkExists,
        dataEnabled = dataEnabled,
        dataState = dataState,
        simAbsent = simAbsent,
        previousDataEnabled = previousDataEnabled,
    )
}
