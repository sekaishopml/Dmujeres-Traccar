package com.dmujeres.traccar.tracking

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.BuildConfig
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.diagnostics.NETCONF_SUSPECTED
import com.dmujeres.traccar.diagnostics.NetCause
import com.dmujeres.traccar.diagnostics.NetSnapshot
import com.dmujeres.traccar.diagnostics.TelInfo
import com.dmujeres.traccar.diagnostics.Transport
import com.dmujeres.traccar.diagnostics.readTelInfo
import com.dmujeres.traccar.diagnostics.refine
import com.dmujeres.traccar.diagnostics.snapshot
import com.dmujeres.traccar.location.GnssState
import com.dmujeres.traccar.platform.LocationState
import com.dmujeres.traccar.platform.SentryLog

/**
 * FASE R3: muestreo de telemetría del dispositivo extraído de `TrackingService`
 * SIN cambio de comportamiento. Una sola fuente para:
 * - foto de red + causa probable ([NetSnapshot]/[NetCause]) y su persistencia,
 * - etiqueta/transporte/señal de la red activa,
 * - snapshot completo para presence/position ([Telemetry]),
 * - batería y permisos de ubicación.
 *
 * Es solo LECTURA del sistema + persistencia de diagnóstico: no decide
 * tracking ni transporta.
 */
class DeviceTelemetrySampler(
    private val context: Context,
    private val config: AppConfig,
    /** Conteo de la cola Room (lectura IO en el llamador). */
    private val pendingCount: suspend () -> Int,
    /** ¿Hay polling one-shot de GNSS en vuelo? (LocationEngine). */
    private val pollInFlight: () -> Boolean,
) {

    /**
     * Última etiqueta de red vista por los observadores (callback de
     * capacidades y presencia). Se usa como `previousLabel` en [snapshot].
     */
    @Volatile var reportedNetwork: String = ""

    data class Telemetry(
        val pending: Int,
        val battery: Int,
        val network: String,
        val vendor: String,
        val model: String,
        val appVersion: String,
        val gps: String,
        val signal: Int,
        val rttMs: Int,
        val shot: NetSnapshot,
        val cause: NetCause,
        /** Telefonía opcional (null sin permiso READ_PHONE_STATE). */
        val dataEnabled: Boolean?,
        val simPresent: Boolean?,
        val service: String?,
        /** Confianza de la causa: "confirmed" | "suspected". */
        val netConf: String,
        /** Observabilidad anti "cero capturas en silencio". */
        val fixReceived: Long,
        val fixRejected: Long,
        val fixEnqueued: Long,
        val permFine: Boolean,
        val permBackground: Boolean,
        val gpsEnabled: Boolean,
        /** GNSS real (null si aún sin eventos) + polling one-shot en vuelo. */
        val gnssUsed: Int?,
        val gnssTotal: Int?,
        val pollActive: Boolean,
        /** R9: fallos del fused en la jornada (0 = sin fallos). */
        val fusedFailures: Int,
    )

    /** Foto de red sin fricción + causa probable (Fase 1). Persiste para la UI. */
    fun snapshot(): NetSnapshot =
        runCatching { snapshot(context, reportedNetwork, config.lastDataEnabled) }.getOrDefault(
            NetSnapshot(
                wifiOn = true, airplane = false,
                hasWifiTransport = false, hasCellTransport = false,
                validated = false, captive = false,
                previousLabel = reportedNetwork,
                previousDataEnabled = config.lastDataEnabled,
            ),
        )

    fun cause(shot: NetSnapshot = snapshot()): NetCause =
        runCatching { NetCause.detect(shot) }.getOrDefault(NetCause.OK)

    fun persistNetState(shot: NetSnapshot, cause: NetCause) {
        runCatching {
            val previous = NetCause.fromValue(config.netCause)
            // Breadcrumbs de red: SOLO transiciones (lost/restored), sin spam.
            if (previous == NetCause.OK && cause != NetCause.OK) {
                SentryLog.breadcrumb("net", "network_lost", cause.name)
            } else if (previous != NetCause.OK && cause == NetCause.OK) {
                SentryLog.breadcrumb("net", "network_restored", "")
            }
            config.netCause = cause.value
            // netLabel guarda la última etiqueta usable para discriminar wifi_lost.
            if (shot.validated) {
                config.netLabel = NetworkStatePolicy.validatedLabel(
                    previous = config.netLabel,
                    hasWifiTransport = shot.hasWifiTransport,
                    hasCellTransport = shot.hasCellTransport,
                )
            }
            // lastDataEnabled: referencia para detectar manipulación MANUAL del
            // switch (transición true→false). Solo se avanza con lectura real.
            if (shot.dataEnabled != null) {
                config.lastDataEnabled = shot.dataEnabled
            }
        }
    }

    /**
     * Transporte del activeNetwork (sin implicar Internet: la validación la
     * aporta [isNetworkAvailable]).
     */
    fun transport(): Transport {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = runCatching {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        }.getOrNull() ?: return Transport.NONE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            else -> Transport.OTHER
        }
    }

    /**
     * Etiqueta común de red (wifi|mobile|none) para presence y position.
     * Requiere NET_CAPABILITY_VALIDATED; un socket sin validar es "none".
     */
    fun networkLabel(): String {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?: return NetworkStatePolicy.LABEL_NONE
        return NetworkStatePolicy.label(
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            hasWifiTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        )
    }

    /**
     * Nivel de señal 0-4 de la red activa. Con SDK>=29 usa signalStrength (-1..4;
     * -1 = desconocido → fallback); el fallback mapea el ancho de banda estimado a buckets.
     */
    fun signalLevel(): Int {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?: return 0
        val strength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            capabilities.signalStrength
        } else {
            -1
        }
        return DeviceTelemetryPolicy.signalLevel(
            strength = strength,
            downstreamKbps = capabilities.linkDownstreamBandwidthKbps.toLong(),
        )
    }

    suspend fun telemetry(): Telemetry {
        val pending = runCatching { pendingCount() }.getOrDefault(0)
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val network = networkLabel()
        val gpsOn = LocationState.isEnabled(context)
        val shot = snapshot()
        val base = cause(shot)
        // Telefonía opcional (READ_PHONE_STATE bajo demanda): sin permiso se
        // degrada a la heurística sin permiso; nunca bloquea ni lanza.
        val tel = runCatching { readTelInfo(context) }.getOrDefault(TelInfo.noPermission())
        val (cause, conf) = runCatching { refine(base, tel, config.lastDataEnabled) }
            .getOrDefault(base to NETCONF_SUSPECTED)
        val gnssHasData = GnssState.hasData()
        return Telemetry(
            pending = pending,
            battery = battery,
            network = network,
            vendor = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            appVersion = BuildConfig.VERSION_NAME,
            gps = if (gpsOn) "on" else "off",
            signal = signalLevel(),
            rttMs = config.mobileRttMs,
            shot = shot,
            cause = cause,
            dataEnabled = tel.dataEnabled,
            simPresent = tel.simPresent,
            service = tel.service,
            netConf = conf,
            fixReceived = runCatching { config.fixReceived }.getOrDefault(0L),
            fixRejected = runCatching { config.fixRejected }.getOrDefault(0L),
            fixEnqueued = runCatching { config.fixEnqueued }.getOrDefault(0L),
            permFine = hasFineLocation(),
            permBackground = hasBackgroundLocation(),
            gpsEnabled = gpsOn,
            // GNSS real solo si hay eventos; pollActive solo viaja en true.
            gnssUsed = GnssState.satsUsed.takeIf { gnssHasData },
            gnssTotal = GnssState.satsTotal.takeIf { gnssHasData },
            fusedFailures = GnssState.fusedFailures,
            pollActive = pollInFlight(),
        )
    }

    /** Fija la etiqueta reportada por los observadores (misma semántica previa). */
    fun noteReportedNetwork(label: String) {
        reportedNetwork = label
    }

    fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasFineLocation()
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun batteryLevel(): Int =
        (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}
