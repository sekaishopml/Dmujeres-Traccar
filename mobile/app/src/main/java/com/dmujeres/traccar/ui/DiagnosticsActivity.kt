package com.dmujeres.traccar.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.BuildConfig
import com.dmujeres.traccar.DmujeresApp
import com.dmujeres.traccar.R
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.readiness.DeviceReadinessChecker
import com.dmujeres.traccar.location.GnssState
import com.dmujeres.traccar.location.GnssSummary
import com.dmujeres.traccar.core.TrackingState
import com.dmujeres.traccar.tracking.TrackingService
import com.dmujeres.traccar.transport.MqttManager
import com.dmujeres.traccar.core.MqttStatus
import com.dmujeres.traccar.platform.UpdateManager
import com.dmujeres.traccar.platform.LocationState
import com.dmujeres.traccar.oem.DeviceCaps
import com.dmujeres.traccar.diagnostics.DiagnosticsReporter
import com.dmujeres.traccar.sensors.MotionSensor
import com.dmujeres.traccar.oem.OemProtection
import com.dmujeres.traccar.recovery.RecoveryJournal
import com.dmujeres.traccar.recovery.RecoveryStatus
import com.dmujeres.traccar.diagnostics.SilenceDiagnosis
import com.dmujeres.traccar.oem.VendorSettings
import com.dmujeres.traccar.ui.theme.DmujeresTheme
import com.dmujeres.traccar.ui.theme.Ink
import com.dmujeres.traccar.core.JourneyFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticsActivity : ComponentActivity() {

    private lateinit var config: AppConfig
    private var refreshKey by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)
        setContent {
            DmujeresTheme {
                DiagnosticsContent(
                    refreshKey = refreshKey,
                    onBack = {
                        finish()
                        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right)
                    },
                    onTestConnection = { testConnection() },
                    onPermissions = {
                        startActivity(Intent(this, OnboardingActivity::class.java))
                    },
                    onRecoverService = { recoverService() },
                    onSendReport = { sendReportNow() },
                    onRunContinuity = {
                        runCatching { com.dmujeres.traccar.readiness.ContinuityTracker.startTest(this) }
                        refreshKey++
                        Toast.makeText(this, getString(R.string.readiness_continuity_start), Toast.LENGTH_LONG).show()
                    },
                    onActivationGuide = {
                        startActivity(Intent(this, ActivationGuideActivity::class.java))
                    },
                    onDebugDesign = {
                        startActivity(Intent(this, DebugDesignActivity::class.java))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKey++
    }

    private fun testConnection() {
        MqttManager.testConnection(
            config.serverUrl, config.username, config.password
        ) { success, message ->
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refreshKey++
            }
        }
    }

    private fun recoverService() {
        if (config.trackingEnabled) {
            config.trackingState = TrackingState.SERVICE_RECOVERY.name
            val started = TrackingService.start(this)
            Toast.makeText(
                this,
                if (started) R.string.service_recovery_title else R.string.start_error,
                Toast.LENGTH_LONG,
            ).show()
            refreshKey++
        }
    }

    /**
     * "Enviar reporte ahora": reason "manual" (salta el throttle cliente). El
     * conteo de pendientes se hace en un hilo IO propio (nunca en main) y el
     * POST viaja en el executor de [DiagnosticsReporter]; al terminar se
     * refresca la tarjeta Monitor con refreshKey++.
     */
    private fun sendReportNow() {
        Thread {
            val pending = runCatching {
                runBlocking(Dispatchers.IO) {
                    (applicationContext as DmujeresApp).database.positionDao().count()
                }
            }.getOrDefault(-1)
            runCatching { DiagnosticsReporter.report(this, "manual", pending) }
            runOnUiThread { refreshKey++ }
        }.start()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DiagnosticsContent(
        refreshKey: Int,
        onBack: () -> Unit,
        onTestConnection: () -> Unit,
        onPermissions: () -> Unit,
        onRecoverService: () -> Unit,
        onSendReport: () -> Unit,
        onRunContinuity: () -> Unit,
        onActivationGuide: () -> Unit,
        onDebugDesign: () -> Unit,
    ) {
        val context = LocalContext.current
        val rows = rememberDiagRows(context, refreshKey)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.diag_title),
                        color = Ink,
                        fontWeight = FontWeight.Medium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = Ink,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )

            // Secciones técnicas (DEBUG, scroll permitido): DEVICE,
            // PERMISSIONS, LOCATION, TRACKING, NETWORK, OUTBOX, OEM, RECOVERY.
            // Cada sección con título en mayúsculas; lo no legible marca
            // "NO VERIFICABLE" (nunca se inventa).
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = onTestConnection,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                ) {
                    Text(
                        stringResource(R.string.diag_test),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Button(
                    onClick = onRecoverService,
                    enabled = rows.recoverEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                ) {
                    Text(
                        stringResource(R.string.diag_retry_service),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Button(
                    onClick = onPermissions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                ) {
                    Text(
                        stringResource(R.string.permissions_button),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Button(
                    onClick = onActivationGuide,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                ) {
                    Text(
                        stringResource(R.string.activation_guide_button),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Acceso OCULTO (QA): 5 toques en la versión abren el menú de
                // diseño (splash/onboarding/dash) junto al diagnóstico.
                var versionTaps by remember { mutableIntStateOf(0) }
                var lastTapAt by remember { mutableLongStateOf(0L) }
                Text(
                    text = stringResource(R.string.debug_design_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val now = System.currentTimeMillis()
                            versionTaps = if (now - lastTapAt > 1500L) 1 else versionTaps + 1
                            lastTapAt = now
                            if (versionTaps >= 5) {
                                versionTaps = 0
                                onDebugDesign()
                            }
                        }
                        .padding(vertical = 10.dp),
                )

                Button(
                    onClick = onSendReport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                ) {
                    Text(
                        stringResource(R.string.diag_send_report),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                DiagSection(
                    title = stringResource(R.string.diag_section_device),
                    text = rows.device,
                )
                DiagSection(
                    title = stringResource(R.string.diag_section_permissions),
                    text = rows.permissions,
                )
                DiagSection(
                    title = stringResource(R.string.diag_section_location),
                    text = rows.gps,
                )
                DiagSection(
                    title = stringResource(R.string.diag_section_tracking),
                    text = rows.tracking,
                )
                DiagSection(
                    title = stringResource(R.string.diag_section_network),
                    text = rows.network,
                )
                DiagSection(
                    title = stringResource(R.string.diag_section_outbox),
                    text = rows.outbox,
                )
                DiagSection(
                    title = stringResource(R.string.diag_section_oem),
                    text = rows.oem,
                )
                DiagSection(
                    title = stringResource(R.string.diag_section_recovery),
                    text = rows.recovery,
                )

                // Tubería por capa (§12/§13): con estos relojes se sabe DÓNDE se
                // rompió (callback vs filtro vs cola vs envío vs ACK).
                DiagSection(
                    title = stringResource(R.string.diag_section_pipeline),
                    text = rows.pipeline,
                )
                DiagSection(
                    title = stringResource(R.string.diag_section_update),
                    text = rows.update,
                )
                DiagSection(
                    title = stringResource(R.string.diag_section_monitor),
                    text = rows.monitor,
                )

                // DeviceReadinessGate (re-evaluación en vivo, barata):
                // veredicto auditable READY/NOT_READY + checks con estado,
                // valor, fuente y hora. La sección RECOVERY ya existe arriba
                // (diag_section_recovery): NO se duplica; el outcome de la
                // prueba de recuperación sale en la fila recovery_test.
                DiagSection(
                    title = stringResource(R.string.readiness_diag_section),
                    text = rows.readiness,
                )
                DiagSection(
                    title = stringResource(R.string.readiness_diag_continuity),
                    text = rows.continuity,
                )
                // Prueba de continuidad: herramienta de diagnóstico (decisión de
                // producto: salió del asistente). Inicia tracking sin jornada y
                // mide la captura durante 90 s con pantalla apagada.
                Button(
                    onClick = onRunContinuity,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                ) {
                    Text(
                        stringResource(R.string.readiness_continuity_start),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    private fun DiagSection(title: String, text: String) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DiagCell(text = text, modifier = Modifier.fillMaxWidth(), maxLines = 18)
        }
    }

    @Composable
    private fun DiagCell(text: String, modifier: Modifier = Modifier, maxLines: Int = 3) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(PanelColor)
                .padding(8.dp),
        )
    }
}

private val PanelColor = Color(0xFFFFE9F0)

private data class DiagRows(
    val device: String = "",
    val permissions: String = "",
    val gps: String = "",
    val tracking: String = "",
    val network: String = "",
    val outbox: String = "",
    val oem: String = "",
    val recovery: String = "",
    val pipeline: String = "",
    val update: String = "",
    val monitor: String = "",
    val readiness: String = "",
    val continuity: String = "",
    val recoverEnabled: Boolean = false,
)

@Composable
private fun rememberDiagRows(context: Context, refreshKey: Int): DiagRows {
    var rows by remember { mutableStateOf(DiagRows()) }
    LaunchedEffect(refreshKey) {
        rows = computeDiagRows(context)
    }
    return rows
}

private suspend fun computeDiagRows(context: Context): DiagRows {
    val config = AppConfig(context)

    val storedState = TrackingState.fromName(config.trackingState)
    val state = if (config.trackingEnabled && !TrackingService.isRunning) {
        TrackingState.SERVICE_RECOVERY
    } else storedState
    val stateText = state.label +
        if (TrackingService.isRunning) "" else " · " + context.getString(R.string.diag_service_stopped)

    val gpsOn = LocationState.isEnabled(context)
    val lastFix = config.lastFixAt
    val nowMs = System.currentTimeMillis()
    // GPS útil (Fase A): satélites reales de GnssState + último fix + polling
    // inferido. NO se toca TrackingService: el polling se infiere como
    // "sin fix > 90 s con jornada activa" (mismo umbral que ActivePollPolicy).
    val hasGnssData = GnssState.hasData()
    val satsUsed = GnssState.satsUsed
    val satsTotal = GnssState.satsTotal
    val hasRecentFix = GpsDiagPolicy.hasRecentFix(lastFix, nowMs)
    val skyBlocked = hasGnssData && satsTotal != null &&
        GnssSummary.isSkyBlocked(satsTotal, hasRecentFix)
    val diag = GpsDiagPolicy.describe(
        hasGnssData = hasGnssData,
        skyBlocked = skyBlocked,
        hasRecentFix = hasRecentFix,
        hasAnyFix = lastFix > 0L,
        trackingActive = config.trackingEnabled,
        withoutFixMs = GpsDiagPolicy.withoutFixMs(lastFix, config.journeyStartAt, nowMs),
    )
    val gpsText = when {
        !gpsOn -> context.getString(R.string.diag_gps_off)
        hasGnssData && satsUsed != null && satsTotal != null -> buildString {
            append(
                context.getString(
                    R.string.diag_gps_sats,
                    satsUsed,
                    satsTotal,
                    if (lastFix > 0L) agoText(context, lastFix)
                    else context.getString(R.string.diag_never),
                ),
            )
            if (diag.indoor) {
                append("\n").append(context.getString(R.string.diag_gps_indoor))
            }
            if (diag.searching) {
                append(" · ").append(context.getString(R.string.diag_gps_searching))
            }
        }
        lastFix > 0 && nowMs - lastFix < 5 * 60_000 ->
            context.getString(R.string.diag_gps_ok)
        else -> buildString {
            append(context.getString(R.string.diag_gps_waiting))
            if (diag.firstWait) {
                append("\n").append(context.getString(R.string.diag_gps_first_signal))
            } else if (diag.indoor) {
                append("\n").append(context.getString(R.string.diag_gps_indoor))
            }
            if (diag.searching) {
                append(" · ").append(context.getString(R.string.diag_gps_searching))
            }
        }
    }

    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val online = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    val recoverEnabled = config.trackingEnabled && !TrackingService.isRunning

    val pending = withContext(Dispatchers.IO) {
        runCatching { (context.applicationContext as DmujeresApp).database.positionDao().count() }.getOrDefault(0)
    }
    val oldest = runCatching {
        (context.applicationContext as DmujeresApp).database.positionDao().oldestEnqueuedAt()
    }.getOrNull()
    val pendingText = if (pending > 0 && oldest != null && oldest > 0L) {
        context.getString(R.string.diag_pending_age, pending, agoText(context, oldest))
    } else {
        context.getString(R.string.diag_pending, pending)
    }

    // battery sin lectura aquí: la sección PERMISSIONS muestra el estado real
    // de batería (exención) y OutboxRoom/Network usan sus propias fuentes.
    fun agoOrNever(ts: Long): String =
        ts.takeIf { it > 0L }?.let { agoText(context, it) } ?: context.getString(R.string.diag_never)
    val breakdown = runCatching { config.rejectBreakdown() }.getOrDefault("")
    val pipelineText = context.getString(
        R.string.diag_pipeline,
        config.fixReceived,
        config.fixRejected,
        if (breakdown.isBlank()) "—" else breakdown,
        config.fixEnqueued,
        agoOrNever(config.lastLocationCallbackAt),
        agoOrNever(config.lastAckAt),
        agoOrNever(config.lastHttpAt),
        agoOrNever(config.lastMqttAt),
    )
    // Diagnóstico de silencio por capa (Fase 9): causa prioritaria derivada
    // de los MISMOS relojes que lee la fila pipeline (pura, SilenceDiagnosis).
    val silence = SilenceDiagnosis.diagnose(
        SilenceDiagnosis.SilenceLayers(
            callbackAt = config.lastLocationCallbackAt,
            acceptedAt = config.lastAcceptedAt,
            storedAt = config.lastEnqueuedAt,
            ackAt = config.lastAckAt,
            httpAt = config.lastHttpAt,
            mqttAt = config.lastMqttAt,
            fixReceived = config.fixReceived,
            fixRejected = config.fixRejected,
            journeyActive = config.trackingEnabled,
            nowMs = nowMs,
        ),
    )
    val pipelineFull = pipelineText + "\n" + context.getString(R.string.diag_silence, silence)

    // Fase 9: fila de recuperación (estado real del pipeline de recuperación
    // del guardián + SessionKeeper + último intento + veredicto + contador).
    val recoveryState = RecoveryJournal.classifyRecovery(
        journeyActive = config.trackingEnabled,
        isRunning = TrackingService.isRunning,
        attempts24h = config.recoveryAttempts,
        lastOutcome = config.lastRecoveryResult,
        nowMs = nowMs,
        lastRecoveryAtMs = config.lastRecoveryAt,
    )
    val recoveryResultText = when (config.lastRecoveryResult) {
        RecoveryJournal.RESULT_OK -> context.getString(R.string.diag_recovery_result_ok)
        RecoveryJournal.RESULT_BLOCKED -> context.getString(R.string.diag_recovery_result_blocked)
        else -> context.getString(R.string.diag_recovery_result_unknown)
    }
    val recoveryText = context.getString(
        R.string.diag_recovery,
        recoveryStatusLabel(context, recoveryState.status),
        if (config.trackingEnabled) context.getString(R.string.diag_keeper_scheduled)
        else context.getString(R.string.diag_keeper_off),
        config.lastRecoveryAt.takeIf { it > 0L }?.let { agoText(context, it) }
            ?: context.getString(R.string.diag_never),
        recoveryResultText,
        config.recoveryAttempts,
    )

    // Fase 9: capacidades del equipo (DeviceCaps, lectura segura sin permisos).
    val capsText = runCatching { DeviceCaps.from(context) }.getOrNull()?.let { caps ->
        context.getString(
            R.string.diag_caps,
            (caps.manufacturer + " " + caps.model).trim(),
            "Android " + caps.androidRelease,
            context.getString(
                if (caps.hasAccelerometer) R.string.diag_caps_available
                else R.string.diag_caps_not_available,
            ),
            context.getString(
                if (caps.hasGyroscope) R.string.diag_caps_available
                else R.string.diag_caps_not_available,
            ),
        )
    }.orEmpty()

    val updateText = when {
        config.lastUpdateCheckAt <= 0L ->
            context.getString(R.string.diag_update_never)
        config.lastUpdateError.isNotBlank() ->
            context.getString(
                R.string.diag_update_error,
                agoText(context, config.lastUpdateCheckAt),
                config.lastUpdateError,
            )
        config.lastUpdateLatest.isNotBlank() &&
            UpdateManager.isNewer(BuildConfig.VERSION_NAME, config.lastUpdateLatest) ->
            context.getString(
                R.string.diag_update_available,
                BuildConfig.VERSION_NAME,
                config.lastUpdateLatest,
            )
        else ->
            context.getString(
                R.string.diag_update_ok,
                BuildConfig.VERSION_NAME,
                agoText(context, config.lastUpdateCheckAt),
            )
    }

    // Tarjeta Monitor: reporte + contadores de salud del bucket diario (AppConfig).
    val monitorHeader = if (config.diagnosticsLastReportAt > 0L) {
        context.getString(
            R.string.diag_monitor_report,
            agoText(context, config.diagnosticsLastReportAt),
            config.diagnosticsLastResult.ifBlank { "…" },
        )
    } else {
        context.getString(R.string.diag_monitor_never)
    }
    val monitor = monitorHeader + "\n" + context.getString(
        R.string.diag_monitor_counters,
        config.clockSteps24h,
        config.reconnects24h,
        config.stuckStops24h,
        config.crashes24h,
        context.getString(if (config.cleanShutdown) R.string.diag_monitor_clean_yes else R.string.diag_monitor_clean_no),
    )

    val startError = config.lastStartError
    val notVerifiable = context.getString(R.string.diag_not_verifiable)
    // DEVICE: identidad completa del equipo (Build público, sin permisos).
    val deviceText = buildString {
        append("manufacturer: ").append(Build.MANUFACTURER)
        append("\nbrand: ").append(Build.BRAND)
        append("\nmodel: ").append(Build.MODEL)
        append("\nandroid sdk: ").append(Build.VERSION.SDK_INT)
        append("\nandroid version: ").append(Build.VERSION.RELEASE)
        append("\nfingerprint: ").append(Build.FINGERPRINT.take(96))
        append("\napp: ").append(BuildConfig.VERSION_NAME)
        if (startError.isNotBlank()) {
            append("\n⚠ ").append(startError)
        }
    }

    // PERMISSIONS: solo lo que la API realmente permite leer.
    fun granted(permission: String): Boolean = ContextCompat.checkSelfPermission(
        context, permission
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    )
    val fsl = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || granted(
        Manifest.permission.FOREGROUND_SERVICE_LOCATION
    )
    val notificationsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    val pm = runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull()
    val batteryExempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    val permissionsText = buildString {
        append("coarse: ").append(if (coarse) "GRANTED" else "DENIED")
        append("\nfine: ").append(if (fine) "GRANTED" else "DENIED")
        append("\nprecise: ").append(if (fine) "GRANTED" else "DENIED")
        append("\nnotifications: ").append(if (notificationsOk) "GRANTED" else "DENIED")
        append("\nbackground location: ").append(
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) "N/A (pre-10)"
            else if (background) "GRANTED" else "DENIED"
        )
        append("\nforeground service location: ").append(
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) "N/A (pre-14)"
            else if (fsl) "GRANTED" else "DENIED"
        )
        append("\nbattery exempt: ").append(if (batteryExempt) "YES" else "NO")
    }

    // LOCATION: proveedores disponibles y estado del sistema. accuracy y
    // qualityClass NO se persisten por fix en el teléfono → NO VERIFICABLE.
    val lm = runCatching {
        context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
    }.getOrNull()
    val locationText = buildString {
        append("provider gps: ").append(
            if (lm?.getProvider(android.location.LocationManager.GPS_PROVIDER) != null) "AVAILABLE"
            else "NOT_AVAILABLE"
        )
        append("\nprovider network: ").append(
            if (lm?.getProvider(android.location.LocationManager.NETWORK_PROVIDER) != null) "AVAILABLE"
            else "NOT_AVAILABLE"
        )
        append("\nlocation enabled: ").append(
            if (LocationState.isEnabled(context)) "YES" else "NO"
        )
        append("\nlast fix: ").append(
            config.lastFixAt.takeIf { it > 0L }?.let { agoText(context, it) }
                ?: context.getString(R.string.diag_never)
        )
        append("\nfix age: ").append(
            if (config.lastFixAt > 0L) ((nowMs - config.lastFixAt) / 1000).toString() + " s"
            else "NO VERIFICABLE"
        )
        append("\naccuracy: ").append(notVerifiable)
        append("\nqualityClass: ").append(notVerifiable)
    }

    // TRACKING: estado del servicio + evidencia del pipeline del fix.
    val trackingText = buildString {
        append("service state: ").append(stateText)
        append("\nforeground: ").append(if (TrackingService.isRunning) "YES" else "NO")
        append("\nmotionState: ").append(MotionSensor.currentState().name)
        append("\nspeedSource: ").append(notVerifiable)
        append("\nlast accepted fix: ").append(agoOrNever(config.lastAcceptedAt))
    }

    // NETWORK: conectividad + canal MQTT + última sincronización.
    val networkText = buildString {
        append("internet: ").append(if (online) "OK" else "OFF")
        append("\nmqtt: ").append(MqttStatus.status)
        if (!MqttStatus.lastError.isNullOrBlank()) {
            append("\nmqtt error: ").append(MqttStatus.lastError)
        }
        append("\nlast sync (http): ").append(agoOrNever(config.lastHttpAt))
        append("\nlast sync (mqtt): ").append(agoOrNever(config.lastMqttAt))
        if (!config.trackingEnabled) {
            append("\n").append(context.getString(R.string.diag_service_off))
        }
    }

    val outboxText = buildString {
        append(pendingText)
        append("\nquarantine: ").append(config.quarantinedTotal)
        append("\nfailures (reconnects 24h): ").append(runCatching { config.reconnects24h }.getOrDefault(0))
    }

    // OEM: detección + fuente de verificación honesta. Lo que depende de
    // pantallas protegidas por permisos de sistema es CONFIGURED_UNVERIFIABLE.
    val vendorKey = VendorSettings.currentVendor()
    val oemText = if (vendorKey == null) {
        "detected: GENERIC\nbattery state: " + (if (batteryExempt) "EXEMPT" else "OPTIMIZED") +
            "\nauto-start: N/A\nbackground restrictions: NONE (sin gate conocido)"
    } else {
        val guide = VendorSettings.guideFor(vendorKey)
        val guideDone = config.vendorGuideDone
        buildString {
            append("detected: ").append(guide?.vendorName ?: vendorKey)
            append("\nbattery state: ").append(if (batteryExempt) "EXEMPT" else "OPTIMIZED")
            append("\nauto-start state: ").append(notVerifiable)
            append("\nbackground restrictions: ").append(notVerifiable)
            append("\nverification source: ")
            append(
                when {
                    !guideDone -> "REQUIERE ACCIÓN DEL USUARIO (guía del fabricante pendiente)"
                    else -> "CONFIGURADO / NO VERIFICABLE (pantallas protegidas por el OEM)"
                }
            )
        }
    }

    // DeviceReadinessGate: re-evaluación en vivo (lecturas baratas de prefs y
    // permisos, en IO). Honestidad total: NOT_VERIFIABLE se imprime tal cual
    // (nunca se traduce a PASS); note y fuente se muestran cuando aportan.
    val readinessResult = runCatching {
        withContext(Dispatchers.IO) { DeviceReadinessChecker.evaluate(context) }
    }.getOrNull()
    val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    val readinessText = if (readinessResult == null) {
        "verdict: NO VERIFICABLE (la evaluación del gate falló)"
    } else {
        buildString {
            append("verdict: ").append(readinessResult.verdict)
            if (readinessResult.notReadyBecause.isNotEmpty()) {
                append("\nnotReadyBecause: ")
                append(readinessResult.notReadyBecause.joinToString(", "))
            } else {
                append("\nreadyBecause: ")
                append(
                    readinessResult.readyBecause.ifEmpty { listOf("—") }.joinToString(", "),
                )
            }
            readinessResult.checks.values.forEach { check ->
                append("\n")
                append(check.key.name.lowercase())
                append(": ").append(check.state.name)
                append(" (").append(check.value).append(")")
                if (check.note.isNotBlank()) {
                    append(" — ").append(check.note)
                }
                append(" · ").append(check.source)
                append(" @ ").append(clockFmt.format(Date(check.timestampMs)))
            }
        }
    }

    // CONTINUIDAD (AppConfig): estado persistido de la prueba conductual, con
    // la causa traducida a lenguaje legible (solo con causa real, nunca
    // fabricada). El outcome de RECOVERY (state/note/attempts) ya es visible
    // en la fila recovery_test del readiness y en la sección RECOVERY.
    val continuityCauseText = when (config.continuityCause) {
        "OEM_FREEZE" -> "control del fabricante congeló la captura"
        "NO_CALLBACK" -> "sin captura con pantalla apagada"
        "PROCESS_DEAD", "FGS_DEAD" -> "proceso/FGS muerto"
        else -> ""
    }
    val continuityText = buildString {
        if (config.continuityRunning) {
            append("state: TEST_REQUIRED · prueba en curso")
        } else {
            append("state: ").append(config.continuityState.ifBlank { "NOT_RUN" })
            when {
                continuityCauseText.isNotBlank() -> append(" · causa: ").append(continuityCauseText)
                config.continuityCause.isNotBlank() ->
                    append(" · causa: ").append(config.continuityCause)
            }
        }
        if (config.continuityAt > 0L) {
            append(" · ").append(agoText(context, config.continuityAt))
        }
        append("\nfixes durante apagado: ").append(config.continuityFixesDuringOff)
        append("\ncongelado: ").append(config.continuityFrozenSeconds).append(" s")
    }

    return DiagRows(
        device = deviceText,
        permissions = permissionsText,
        gps = gpsText + "\n" + locationText,
        tracking = trackingText,
        network = networkText,
        outbox = outboxText,
        oem = oemText,
        recovery = recoveryText + "\n" + capsText,
        pipeline = pipelineFull,
        update = updateText,
        monitor = monitor,
        readiness = readinessText,
        continuity = continuityText,
        recoverEnabled = recoverEnabled,
    )
}

private fun recoveryStatusLabel(context: Context, status: RecoveryStatus): String = when (status) {
    RecoveryStatus.TRACKING_ACTIVE -> context.getString(R.string.diag_recovery_state_ok)
    RecoveryStatus.SERVICE_MISSING -> context.getString(R.string.diag_recovery_state_missing)
    RecoveryStatus.RECOVERY_PENDING -> context.getString(R.string.diag_recovery_state_pending)
    RecoveryStatus.RECOVERY_BLOCKED_BY_OEM -> context.getString(R.string.diag_recovery_state_blocked)
}

private fun agoText(context: Context, timestamp: Long): String =
    JourneyFormatter.agoText(context, timestamp)

/**
 * Lógica pura del diagnóstico GPS (JVM, sin Android) para `testDebugUnitTest`.
 *
 * - `indoor`: hay datos GNSS con 0 satélites a la vista y sin fix reciente
 *   ([GnssSummary.isSkyBlocked] ya calculado fuera por necesitar el total) →
 *   "Bajo techo o sin vista al cielo — sal al aire libre".
 * - `firstWait`: sin datos GNSS y sin fix reciente → "Esperando primera señal…".
 * - `searching`: jornada activa y > 90 s sin fix (mismo umbral que
 *   `ActivePollPolicy.NO_FIX_POLL_AFTER_NANOS`): el polling one-shot
 *   probablemente está disparando. Es inferencia (sin getter al servicio, que
 *   está prohibido tocar): se calcula aquí con `AppConfig.lastFixAt`.
 */
data class GpsDiagInfo(
    val indoor: Boolean,
    val firstWait: Boolean,
    val searching: Boolean,
)

object GpsDiagPolicy {
    /** Fix "reciente" igual que la UI existente (5 min). */
    const val RECENT_FIX_MS = 5 * 60_000L

    /** Umbral de inferencia de polling: 90 s (Fase A). */
    const val SEARCHING_AFTER_MS = 90_000L

    fun hasRecentFix(lastFixAt: Long, now: Long): Boolean =
        lastFixAt > 0L && now - lastFixAt < RECENT_FIX_MS

    /**
     * Ms sin fix: desde el último fix si lo hubo, si no desde el inicio de la
     * jornada (caso "esperando primera señal" con jornada activa). 0 si no hay
     * referencia (sin jornada y sin fix: no se infiere búsqueda).
     */
    fun withoutFixMs(lastFixAt: Long, journeyStartAt: Long, now: Long): Long = when {
        lastFixAt > 0L -> (now - lastFixAt).coerceAtLeast(0L)
        journeyStartAt > 0L -> (now - journeyStartAt).coerceAtLeast(0L)
        else -> 0L
    }

    fun isSearching(trackingActive: Boolean, withoutFixMs: Long): Boolean =
        trackingActive && withoutFixMs > SEARCHING_AFTER_MS

    fun describe(
        hasGnssData: Boolean,
        skyBlocked: Boolean,
        hasRecentFix: Boolean,
        hasAnyFix: Boolean,
        trackingActive: Boolean,
        withoutFixMs: Long,
    ): GpsDiagInfo {
        val indoor = skyBlocked && !hasRecentFix
        // Sin datos GNSS y sin fix reciente (nunca hubo o ya es viejo):
        // aún no hay primera señal en este arranque.
        val firstWait = !hasGnssData && !hasRecentFix
        val searching = isSearching(trackingActive, withoutFixMs)
        return GpsDiagInfo(indoor = indoor, firstWait = firstWait, searching = searching)
    }
}
