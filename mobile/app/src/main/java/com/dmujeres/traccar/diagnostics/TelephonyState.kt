package com.dmujeres.traccar.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** Confianza de la causa de red enviada en presence (campo `netConf`). */
const val NETCONF_CONFIRMED = "confirmed"
const val NETCONF_SUSPECTED = "suspected"

/** Valores del campo `service` de [TelInfo]. */
const val TEL_SERVICE_IN_SERVICE = "in_service"
const val TEL_SERVICE_OUT_OF_SERVICE = "out_of_service"
const val TEL_SERVICE_EMERGENCY = "emergency"
const val TEL_SERVICE_UNKNOWN = "unknown"

/**
 * Foto de telefonía para dar certeza anti-trampas a [NetCause].
 *
 * El permiso READ_PHONE_STATE es dangerous, OPCIONAL y nunca bloqueante:
 * sin permiso todo es null y [hasPermission] es false (se sigue con la
 * heurística de Fase 1). No se lee número, IMEI ni ningún identificador.
 *
 * Compatibilidad: solo se usan APIs de nivel 26 (minSdk 26):
 * - SubscriptionManager.getDefaultDataSubscriptionId (API 24+)
 * - TelephonyManager.createForSubscriptionId (API 24+)
 * - TelephonyManager.isDataEnabled (API 26+)
 * - TelephonyManager.getServiceState (API 26+)
 */
data class TelInfo(
    val dataEnabled: Boolean?,
    val simPresent: Boolean?,
    val service: String?,
    val hasPermission: Boolean,
) {
    companion object {
        /** Sin permiso: todo desconocido, se sigue con la heurística. */
        fun noPermission(): TelInfo = TelInfo(
            dataEnabled = null,
            simPresent = null,
            service = null,
            hasPermission = false,
        )
    }
}

/**
 * Lee [TelInfo] desde el sistema. Nunca lanza: cada lectura está protegida
 * con try/catch (SecurityException si el permiso se revocó entre el chequeo
 * y la lectura, u otras en dispositivos solo WiFi sin telefonía).
 *
 * Usa la suscripción de datos por defecto
 * (SubscriptionManager.getDefaultDataSubscriptionId + createForSubscriptionId)
 * con fallback al TelephonyManager base.
 */
fun readTelInfo(context: Context): TelInfo {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return TelInfo.noPermission()
    }
    return try {
        val base = context.getSystemService(TelephonyManager::class.java)
            ?: return TelInfo(null, null, null, true)
        val tel = runCatching {
            val subId = SubscriptionManager.getDefaultDataSubscriptionId()
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                base.createForSubscriptionId(subId)
            } else {
                base
            }
        }.getOrNull() ?: base
        val dataEnabled: Boolean? = runCatching { tel.isDataEnabled }.getOrNull()
        val simPresent: Boolean? = runCatching {
            when (tel.simState) {
                TelephonyManager.SIM_STATE_ABSENT -> false
                TelephonyManager.SIM_STATE_UNKNOWN -> null
                else -> true
            }
        }.getOrNull()
        val service: String? = runCatching {
            when (tel.serviceState?.state) {
                ServiceState.STATE_IN_SERVICE -> TEL_SERVICE_IN_SERVICE
                ServiceState.STATE_OUT_OF_SERVICE -> TEL_SERVICE_OUT_OF_SERVICE
                ServiceState.STATE_EMERGENCY_ONLY -> TEL_SERVICE_EMERGENCY
                ServiceState.STATE_POWER_OFF -> TEL_SERVICE_OUT_OF_SERVICE
                else -> TEL_SERVICE_UNKNOWN
            }
        }.getOrNull() ?: TEL_SERVICE_UNKNOWN
        TelInfo(
            dataEnabled = dataEnabled,
            simPresent = simPresent,
            service = service,
            hasPermission = true,
        )
    } catch (e: SecurityException) {
        TelInfo.noPermission()
    } catch (e: Exception) {
        TelInfo(dataEnabled = null, simPresent = null, service = null, hasPermission = true)
    }
}

/**
 * Refina la causa heurística [base] con telefonía. Función pura y testeable.
 * Devuelve la causa + confianza ("confirmed" | "suspected").
 *
 * Sin permiso → (base, suspected), EXCEPTO wifi_off_user/airplane/
 * captive_suspected/no_internet → (base, confirmed), que ya son ciertas sin
 * permiso (no dependen del switch de datos ni de la SIM).
 *
 * Con permiso (las ciertas anteriores se preservan: la telefonía no puede
 * contradecir al modo avión ni al WiFi apagado por el usuario):
 * - dataEnabled == false + previousDataEnabled == true → (mobile_data_off_user,
 *   confirmed): TRANSICIÓN observada = manipulación manual cierta.
 * - dataEnabled == false sin transición → (base, suspected): el switch apagado
 *   de forma estable (p.ej. vivir en WiFi) no prueba que LO APAGARAS ahora.
 * - simPresent == false → (sim_missing, confirmed).
 * - service out_of_service/emergency + dataEnabled == true →
 *   (no_coverage_suspected, confirmed).
 * - resto → (base, suspected).
 *
 * @param previousDataEnabled último dataEnabled conocido (AppConfig); null si
 * nunca se observó (arranque): sin transición no hay acusación.
 */
fun refine(
    base: NetCause,
    tel: TelInfo,
    previousDataEnabled: Boolean? = null,
): Pair<NetCause, String> {
    if (base == NetCause.WIFI_OFF_USER ||
        base == NetCause.AIRPLANE ||
        base == NetCause.CAPTIVE_SUSPECTED ||
        base == NetCause.NO_INTERNET
    ) {
        return base to NETCONF_CONFIRMED
    }
    if (!tel.hasPermission) return base to NETCONF_SUSPECTED
    // Certeza física primero (igual que NetCause.detect): sin chip no hay
    // dato móvil posible y el switch es irrelevante.
    if (tel.simPresent == false) return NetCause.SIM_MISSING to NETCONF_CONFIRMED
    if (tel.dataEnabled == false) {
        return if (previousDataEnabled == true) NetCause.MOBILE_DATA_OFF_USER to NETCONF_CONFIRMED
        else base to NETCONF_SUSPECTED
    }
    if ((tel.service == TEL_SERVICE_OUT_OF_SERVICE || tel.service == TEL_SERVICE_EMERGENCY) &&
        tel.dataEnabled == true
    ) {
        return NetCause.NO_COVERAGE_SUSPECTED to NETCONF_CONFIRMED
    }
    return base to NETCONF_SUSPECTED
}
