package com.dmujeres.traccar

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.location.GnssState
import com.dmujeres.traccar.location.GnssSummary
import com.dmujeres.traccar.location.TrackingState
import com.dmujeres.traccar.location.TrackingService
import com.dmujeres.traccar.mqtt.MqttManager
import com.dmujeres.traccar.mqtt.MqttStatus
import com.dmujeres.traccar.mqtt.UpdateManager
import com.dmujeres.traccar.util.LocationState
import com.dmujeres.traccar.ui.theme.DmujeresTheme
import com.dmujeres.traccar.ui.theme.Ink
import com.dmujeres.traccar.util.JourneyFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiagnosticsActivity : ComponentActivity() {

    private lateinit var config: AppConfig
    private var refreshKey by mutableIntStateOf(0)

    /**
     * Permiso opcional READ_PHONE_STATE, solo bajo demanda desde este botón.
     * Nunca se pide en onboarding ni bloquea Iniciar jornada: si lo deniegan
     * se sigue con la heurística sin insistir.
     */
    private val phoneStateLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Toast.makeText(
            this,
            if (granted) R.string.diag_phone_granted else R.string.diag_phone_denied,
            Toast.LENGTH_LONG,
        ).show()
        refreshKey++
    }

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
                    onImprove = { requestPhoneState() },
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

    /** Explicación previa + solicitud del permiso opcional de diagnóstico. */
    private fun requestPhoneState() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.diag_phone_already, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.diag_improve)
            .setMessage(R.string.diag_phone_rationale)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()
                phoneStateLauncher.launch(Manifest.permission.READ_PHONE_STATE)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DiagnosticsContent(
        refreshKey: Int,
        onBack: () -> Unit,
        onTestConnection: () -> Unit,
        onPermissions: () -> Unit,
        onRecoverService: () -> Unit,
        onImprove: () -> Unit,
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

            // Sin scroll: grid compacto 2 columnas + filas acotadas para que
            // quepa sin scroll en ~640dp de alto (bodySmall, 6.dp, maxLines).
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DiagCell(text = rows.state, modifier = Modifier.weight(1f))
                    DiagCell(text = rows.gps, modifier = Modifier.weight(1f))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DiagCell(text = rows.network, modifier = Modifier.weight(1f))
                    DiagCell(text = rows.server, modifier = Modifier.weight(1f))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DiagCell(text = rows.pending, modifier = Modifier.weight(1f))
                    DiagCell(text = rows.battery, modifier = Modifier.weight(1f))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    DiagCell(text = rows.lastFix, modifier = Modifier.weight(1f))
                    DiagCell(text = rows.lastAck, modifier = Modifier.weight(1f))
                }
                DiagCell(
                    text = rows.update,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                )

                Text(
                    text = rows.device,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF666666),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )

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
                    onClick = onImprove,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                ) {
                    Text(
                        stringResource(R.string.diag_improve),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
    val state: String = "",
    val gps: String = "",
    val network: String = "",
    val server: String = "",
    val pending: String = "",
    val battery: String = "",
    val lastFix: String = "",
    val lastAck: String = "",
    val update: String = "",
    val device: String = "",
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
    val networkText = context.getString(
        if (online) R.string.diag_network_ok else R.string.diag_network_off
    )

    var serverText = MqttStatus.status +
        if (MqttStatus.lastError.isNullOrBlank()) "" else "\n${MqttStatus.lastError}"

    if (!config.trackingEnabled) {
        serverText += " · " + context.getString(R.string.diag_service_off)
    }

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

    val battery = (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val batteryText = context.getString(R.string.diag_battery, battery)

    val lastFixText = context.getString(
        R.string.diag_last_fix,
        config.lastFixAt.takeIf { it > 0L }?.let { agoText(context, it) }
            ?: context.getString(R.string.diag_never),
    )
    val lastAckText = context.getString(
        R.string.diag_last_ack,
        config.lastAckAt.takeIf { it > 0L }?.let { agoText(context, it) }
            ?: context.getString(R.string.diag_never),
    )

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

    val startError = config.lastStartError
    val deviceText = Build.MANUFACTURER + " " + Build.MODEL +
        " · Android " + Build.VERSION.RELEASE +
        " · " + context.getString(R.string.app_version, BuildConfig.VERSION_NAME) +
        if (startError.isBlank()) "" else "\n⚠ " + startError

    return DiagRows(
        state = stateText,
        gps = gpsText,
        network = networkText,
        server = serverText,
        pending = pendingText,
        battery = batteryText,
        lastFix = lastFixText,
        lastAck = lastAckText,
        update = updateText,
        device = deviceText,
        recoverEnabled = recoverEnabled,
    )
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
