package com.dmujeres.traccar

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.location.TrackingState
import com.dmujeres.traccar.location.TrackingService
import com.dmujeres.traccar.mqtt.MqttManager
import com.dmujeres.traccar.mqtt.MqttStatus
import com.dmujeres.traccar.ui.theme.DmujeresTheme
import com.dmujeres.traccar.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DiagnosticsContent(
        refreshKey: Int,
        onBack: () -> Unit,
        onTestConnection: () -> Unit,
        onPermissions: () -> Unit,
        onRecoverService: () -> Unit,
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

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DiagRow(text = rows.state)
                DiagRow(text = rows.gps)
                DiagRow(text = rows.network)
                DiagRow(text = rows.server)
                DiagRow(text = rows.pending)
                DiagRow(text = rows.battery)
                DiagRow(text = rows.lastFix)
                DiagRow(text = rows.lastAck)

                Text(
                    text = rows.device,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF666666),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                )

                Button(
                    onClick = onTestConnection,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                ) {
                    Text(stringResource(R.string.diag_test))
                }

                Button(
                    onClick = onRecoverService,
                    enabled = rows.recoverEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                ) {
                    Text(stringResource(R.string.diag_retry_service))
                }

                Button(
                    onClick = onPermissions,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.permissions_button))
                }
            }
        }
    }

    @Composable
    private fun DiagRow(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(PanelColor)
                .padding(12.dp),
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

    val gpsMode = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0)
    val lastFix = config.lastFixAt
    val gpsText = when {
        gpsMode == 0 -> context.getString(R.string.diag_gps_off)
        lastFix > 0 && System.currentTimeMillis() - lastFix < 5 * 60_000 ->
            context.getString(R.string.diag_gps_ok)
        else -> context.getString(R.string.diag_gps_waiting)
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
        device = deviceText,
        recoverEnabled = recoverEnabled,
    )
}

private fun agoText(context: Context, timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0)) / 60_000
    return when {
        minutes < 1 -> context.getString(R.string.ago_now)
        minutes < 60 -> context.getString(R.string.ago_minutes, minutes)
        else -> context.getString(R.string.ago_hours, minutes / 60)
    }
}
