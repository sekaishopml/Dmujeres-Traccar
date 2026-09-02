package com.dmujeres.traccar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.location.TrackingState
import com.dmujeres.traccar.location.TrackingService
import com.dmujeres.traccar.mqtt.MqttManager
import com.dmujeres.traccar.mqtt.MqttStatus
import com.dmujeres.traccar.mqtt.UpdateManager
import com.dmujeres.traccar.ui.theme.Background
import com.dmujeres.traccar.ui.theme.DmujeresTheme
import com.dmujeres.traccar.ui.theme.Ink
import com.dmujeres.traccar.ui.theme.StatusError
import com.dmujeres.traccar.ui.theme.StatusIdle
import com.dmujeres.traccar.ui.theme.StatusOffline
import com.dmujeres.traccar.ui.theme.StatusOk
import com.dmujeres.traccar.ui.theme.StatusWarn
import com.dmujeres.traccar.util.Notifications
import com.dmujeres.traccar.util.RemoteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla principal: login con usuario y contraseña, botón de iniciar/finalizar
 * jornada y un registro de estado, con acceso al diagnóstico.
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_UPDATE = "open_update"
    }

    private lateinit var config: AppConfig
    private var testing = false
    private var latestUpdate: UpdateManager.Latest? = null
    private var updateCheckInFlight = false
    private var detailsTransition = false
    private var recoveryDialogShown = false

    // Estado de la UI (Compose) actualizado desde los métodos imperativos.
    private var usernameInput by mutableStateOf("")
    private var passwordInput by mutableStateOf("")
    private var passwordVisible by mutableStateOf(false)
    private var loginTesting by mutableStateOf(false)
    private var logged by mutableStateOf(false)

    private var stateText by mutableStateOf("")
    private var statusColor by mutableStateOf(StatusIdle)
    private var batteryText by mutableStateOf("")
    private var batteryLevel by mutableIntStateOf(-1)
    private var batteryColor by mutableStateOf(StatusOk)
    private var logText by mutableStateOf("")
    private var detailsLoading by mutableStateOf(false)
    private var toggleLabel by mutableIntStateOf(R.string.start)
    private var toggleIcon by mutableIntStateOf(R.drawable.ic_play)
    private var toggleEnabled by mutableStateOf(true)
    private var updateBannerVisible by mutableStateOf(false)

    private val uiHandler = Handler(Looper.getMainLooper())
    private var tickCount = 0
    private val uiTicker = object : Runnable {
        override fun run() {
            refreshState()
            tickCount++
            // Mientras la app está abierta, revisa versiones cada 2 minutos (40 ticks × 3 s).
            if (tickCount % 40 == 0) checkForUpdate(auto = true)
            uiHandler.postDelayed(this, 3_000)
        }
    }

    private val detailsTransitionTimeout = Runnable {
        endDetailsLoading()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startIfReady()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig(this)
        Notifications.ensureChannel(this)

        usernameInput = config.username
        passwordInput = config.password

        setContent {
            DmujeresTheme {
                MainScreen(
                    username = usernameInput,
                    onUsernameChange = { usernameInput = it },
                    password = passwordInput,
                    onPasswordChange = { passwordInput = it },
                    passwordVisible = passwordVisible,
                    onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                    loginTesting = loginTesting,
                    onLogin = ::login,
                    logged = logged,
                    stateText = stateText,
                    statusColor = statusColor,
                    batteryText = batteryText,
                    batteryLevel = batteryLevel,
                    batteryColor = batteryColor,
                    detailsLoading = detailsLoading,
                    logText = logText,
                    toggleLabel = toggleLabel,
                    toggleIcon = toggleIcon,
                    toggleEnabled = toggleEnabled,
                    onToggle = ::onTogglePressed,
                    onDiag = ::openDiagnostics,
                    updateBannerVisible = updateBannerVisible,
                    onUpdateBanner = {
                        latestUpdate?.let { showUpdateDialog(it) }
                    },
                    onUpdateIcon = { checkForUpdate(auto = false) },
                    versionText = getString(R.string.app_version, BuildConfig.VERSION_NAME),
                )
            }
        }

        ensureBatteryExemption()
        updateView()
        if (intent?.getBooleanExtra(EXTRA_OPEN_UPDATE, false) == true) {
            checkForUpdate(auto = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_UPDATE, false)) {
            checkForUpdate(auto = false)
        }
    }

    override fun onResume() {
        super.onResume()
        updateView()
        checkForUpdate(auto = true)
        uiHandler.post(uiTicker)
        if (config.trackingEnabled) {
            ensureBatteryExemption()
            if (TrackingService.isRunning) recoveryDialogShown = false
            if (!TrackingService.isRunning && config.lastStartError.isNotBlank() && !recoveryDialogShown) {
                recoveryDialogShown = true
                AlertDialog.Builder(this)
                    .setTitle(R.string.killed_title)
                    .setMessage(config.lastStartError + "\n\n" + getString(R.string.killed_body))
                    .setPositiveButton(R.string.killed_reactivate) { _, _ ->
                        beginDetailsLoading()
                        config.trackingEnabled = true
                        config.trackingState = TrackingState.SERVICE_RECOVERY.name
                        TrackingService.start(this)
                    }
                    .setNegativeButton(R.string.dialog_close, null)
                    .show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiTicker)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(detailsTransitionTimeout)
        super.onDestroy()
    }

    /** Exige la exención de batería: sin ella Android congela el GPS en reposo. */
    private fun ensureBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_body)
            .setPositiveButton(R.string.battery_grant) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                runCatching { startActivity(intent) }
            }
            .setNegativeButton(R.string.dialog_close, null)
            .setCancelable(false)
            .show()
    }

    private fun login() {
        val username = usernameInput.trim()
        val password = passwordInput
        if (username.isBlank() || password.isBlank()) {
            showDialog(getString(R.string.credentials_required), false)
            return
        }
        if (testing) return
        testing = true
        loginTesting = true

        MqttManager.testConnection(config.serverUrl, username, password) { success, message ->
            runOnUiThread {
                testing = false
                loginTesting = false
                if (success) {
                    config.username = username
                    config.password = password
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { RemoteConfig.fetch(this@MainActivity) }
                        updateView()
                        showDialog(getString(R.string.login_welcome), true)
                    }
                } else {
                    showDialog(message, false)
                }
            }
        }
    }

    private fun showDialog(message: String, success: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(getString(if (success) R.string.dialog_ok_title else R.string.dialog_error_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.dialog_close), null)
            .show()
    }

    private fun onTogglePressed() {
        if (config.trackingEnabled) {
            confirmFinishJourney()
        } else {
            if (config.username.isBlank() || config.password.isBlank()) {
                Toast.makeText(this, R.string.credentials_required, Toast.LENGTH_LONG).show()
            } else {
                MqttManager.testConnection(config.serverUrl, config.username, config.password) { success, message ->
                    runOnUiThread {
                        if (success) startIfReady() else showDialog(message, false)
                    }
                }
            }
        }
    }

    private fun startIfReady() {
        if (!allPermissionsGranted()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            return
        }
        beginDetailsLoading()
        config.trackingEnabled = true
        config.trackingState = TrackingState.SERVICE_RECOVERY.name
        val requested = TrackingService.start(this)
        if (!requested) {
            config.trackingEnabled = false
            config.lastStartError = getString(R.string.start_error)
            config.trackingState = TrackingState.TRACKING_DISABLED_BY_USER.name
            endDetailsLoading()
            showDialog(config.lastStartError, false)
        }
        refreshState()
    }

    private fun allPermissionsGranted(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return true
    }

    /** Muestra login o pantalla principal según haya credenciales guardadas (con fundido). */
    private fun updateView() {
        logged = config.username.isNotBlank() && config.password.isNotBlank()
        refreshState()
    }

    /** Registro de estado en lenguaje simple para el colaborador. */
    private fun refreshState() {
        val enabled = config.trackingEnabled
        val storedState = TrackingState.fromName(config.trackingState)
        val state = if (enabled && !TrackingService.isRunning) {
            TrackingState.SERVICE_RECOVERY
        } else {
            storedState
        }
        stateText = stateText(state)
        statusColor = statusColor(state)
        toggleLabel = if (enabled) R.string.stop else R.string.start
        toggleIcon = if (enabled) R.drawable.ic_stop else R.drawable.ic_play
        toggleEnabled = !detailsTransition

        val lines = mutableListOf<String>()
        val now = System.currentTimeMillis()
        lines += getString(R.string.log_state, stateText(state))

        val journey = config.journeyStartAt
        if (enabled && journey > 0) {
            val elapsed = (now - journey).coerceAtLeast(0)
            val hours = elapsed / 3_600_000
            val minutes = (elapsed % 3_600_000) / 60_000
            lines += getString(R.string.log_journey_on, hours, minutes)
        } else {
            lines += getString(R.string.log_journey_off)
        }

        lines += when (MqttStatus.status) {
            MqttStatus.CONNECTED -> getString(R.string.log_server_on)
            MqttStatus.CONNECTING -> getString(R.string.log_server_connecting)
            else -> getString(R.string.log_server_off)
        }

        val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (battery in 0..100) {
            batteryLevel = battery
            batteryColor = when {
                battery in 1..20 -> StatusError
                battery <= 50 -> StatusWarn
                else -> StatusOk
            }
            batteryText = getString(R.string.log_battery, battery)
            lines += getString(R.string.log_battery, battery)
        } else {
            batteryLevel = -1
            batteryText = ""
        }

        val lastAck = config.lastAckAt
        if (lastAck > 0) {
            lines += getString(R.string.log_last_ack, agoText(lastAck))
        }
        val lastFix = config.lastFixAt
        if (lastFix > 0) {
            lines += getString(R.string.log_last_fix, agoText(lastFix))
        }

        lifecycleScope.launch {
            val pendingInfo = withContext(Dispatchers.IO) {
                runCatching {
                    val dao = (application as DmujeresApp).database.positionDao()
                    dao.count() to dao.oldestEnqueuedAt()
                }.getOrDefault(0 to null)
            }
            val pending = pendingInfo.first
            lines += if (pending > 0) getString(R.string.log_pending, pending)
            else getString(R.string.log_pending_none)
            if (pending > 0 && pendingInfo.second != null) {
                lines += getString(R.string.log_oldest_pending, agoText(pendingInfo.second!!))
            }
            if (battery in 1..20) {
                lines += getString(R.string.log_warn_battery)
            }
            if (lastFix > 0 && now - lastFix > 5 * 60_000) {
                lines += getString(R.string.log_warn_gps)
            }
            logText = lines.joinToString("\n")
            if (!detailsTransition
                || (enabled && TrackingService.isRunning && state != TrackingState.SERVICE_RECOVERY)
                || (!enabled && !TrackingService.isRunning)
            ) {
                endDetailsLoading()
            }
        }
    }

    private fun stateText(state: TrackingState): String = when (state) {
        TrackingState.TRACKING_ACTIVE -> getString(R.string.state_active)
        TrackingState.GPS_DISABLED -> getString(R.string.state_gps)
        TrackingState.NETWORK_OFFLINE -> getString(R.string.state_network)
        TrackingState.MQTT_DISCONNECTED -> getString(R.string.state_server)
        TrackingState.SERVER_UNAVAILABLE -> getString(R.string.state_server)
        TrackingState.PENDING_ACK_TIMEOUT -> getString(R.string.state_pending)
        TrackingState.BATTERY_LOW -> getString(R.string.state_battery)
        TrackingState.BUFFER_FULL -> getString(R.string.state_buffer)
        TrackingState.PERMISSION_MISSING -> getString(R.string.state_permission)
        TrackingState.SERVICE_RECOVERY -> getString(R.string.state_recovery)
        TrackingState.TRACKING_DISABLED_BY_USER -> getString(R.string.tracking_off)
    }

    private fun statusColor(state: TrackingState): Color = when (state) {
        TrackingState.TRACKING_ACTIVE -> StatusOk
        TrackingState.SERVICE_RECOVERY,
        TrackingState.TRACKING_DISABLED_BY_USER -> StatusIdle
        TrackingState.PENDING_ACK_TIMEOUT,
        TrackingState.BATTERY_LOW,
        TrackingState.BUFFER_FULL -> StatusWarn
        TrackingState.GPS_DISABLED -> StatusOffline
        TrackingState.NETWORK_OFFLINE,
        TrackingState.MQTT_DISCONNECTED,
        TrackingState.SERVER_UNAVAILABLE,
        TrackingState.PERMISSION_MISSING -> StatusError
    }

    private fun beginDetailsLoading() {
        detailsTransition = true
        detailsLoading = true
        uiHandler.removeCallbacks(detailsTransitionTimeout)
        uiHandler.postDelayed(detailsTransitionTimeout, 15_000)
    }

    private fun endDetailsLoading() {
        detailsTransition = false
        uiHandler.removeCallbacks(detailsTransitionTimeout)
        detailsLoading = false
        toggleEnabled = true
    }

    private fun confirmFinishJourney() {
        AlertDialog.Builder(this)
            .setTitle(R.string.finish_confirm_title)
            .setMessage(R.string.finish_confirm_body)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.finish_confirm_button) { _, _ -> finishJourney() }
            .show()
    }

    private fun finishJourney() {
        // Captura el resumen antes de que el servicio resetee las métricas al cerrar.
        beginDetailsLoading()
        showJourneySummary()
        config.journeyStopRequested = true
        config.trackingEnabled = false
        config.trackingState = TrackingState.TRACKING_DISABLED_BY_USER.name
        TrackingService.stop(this)
        refreshState()
    }

    private fun agoText(timestamp: Long): String {
        val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0)) / 60_000
        return when {
            minutes < 1 -> getString(R.string.ago_now)
            minutes < 60 -> getString(R.string.ago_minutes, minutes)
            else -> getString(R.string.ago_hours, minutes / 60)
        }
    }

    /** Resumen de la jornada recién finalizada. */
    private fun showJourneySummary() {
        val startedAt = config.journeyStartAt
        if (startedAt <= 0) return
        val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
        val hours = durationMs / 3_600_000
        val minutes = (durationMs % 3_600_000) / 60_000
        val duration = getString(R.string.journey_duration, hours, minutes)
        val km = String.format(java.util.Locale.US, "%.1f", config.journeyDistanceM / 1000.0)
        val points = config.journeyPoints
        val confirmedPoints = config.journeyConfirmedPoints
        val summary = "$duration|$km|$points|$confirmedPoints"
        config.lastJourneySummary = summary
        config.lastSummaryNotified = ""
        AlertDialog.Builder(this)
            .setTitle(R.string.journey_summary_title)
            .setMessage(getString(R.string.journey_summary_body, duration, km, points, confirmedPoints))
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    /** Comprueba si hay actualización en el servidor (auto al abrir o al pulsar el icono). */
    private fun checkForUpdate(auto: Boolean) {
        if (updateCheckInFlight) return
        updateCheckInFlight = true
        lifecycleScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { UpdateManager.check(config.serverUrl) }
                if (latest == null) {
                    hideUpdateBanner()
                    Notifications.clearUpdateAvailable(this@MainActivity)
                    if (!auto) {
                        Toast.makeText(this@MainActivity, R.string.update_unreachable, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                if (!UpdateManager.isNewer(BuildConfig.VERSION_NAME, latest.version)) {
                    hideUpdateBanner()
                    Notifications.clearUpdateAvailable(this@MainActivity)
                    if (!auto) {
                        Toast.makeText(this@MainActivity, R.string.update_no_new, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                latestUpdate = latest
                Notifications.updateAvailable(this@MainActivity, latest.version)
                if (auto) {
                    showUpdateBanner()
                } else {
                    showUpdateDialog(latest)
                }
            } finally {
                updateCheckInFlight = false
            }
        }
    }

    /** Banner rojo superior (navbar) avisando de la nueva versión. */
    private fun showUpdateBanner() {
        if (updateBannerVisible) return
        updateBannerVisible = true
    }

    private fun hideUpdateBanner() {
        if (!updateBannerVisible) return
        updateBannerVisible = false
    }

    private fun showUpdateDialog(latest: UpdateManager.Latest) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_found, latest.version))
            .setMessage(getString(R.string.update_notes, latest.notes ?: ""))
            .setPositiveButton(R.string.update_now) { _, _ ->
                downloadAndInstall(latest)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun downloadAndInstall(latest: UpdateManager.Latest) {
        lifecycleScope.launch {
            val notification = Notifications.foregroundNotification(
                this@MainActivity,
                getString(R.string.app_name),
                getString(R.string.update_downloading),
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(9, notification)
            val file = withContext(Dispatchers.IO) { UpdateManager.download(this@MainActivity, latest.url) }
            manager.cancel(9)
            if (file != null) {
                UpdateManager.install(this@MainActivity, file)
            } else {
                Toast.makeText(this@MainActivity, R.string.update_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openDiagnostics() {
        startActivity(Intent(this, DiagnosticsActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
    }

    @Composable
    private fun MainScreen(
        username: String,
        onUsernameChange: (String) -> Unit,
        password: String,
        onPasswordChange: (String) -> Unit,
        passwordVisible: Boolean,
        onTogglePasswordVisible: () -> Unit,
        loginTesting: Boolean,
        onLogin: () -> Unit,
        logged: Boolean,
        stateText: String,
        statusColor: Color,
        batteryText: String,
        batteryLevel: Int,
        batteryColor: Color,
        detailsLoading: Boolean,
        logText: String,
        toggleLabel: Int,
        toggleIcon: Int,
        toggleEnabled: Boolean,
        onToggle: () -> Unit,
        onDiag: () -> Unit,
        updateBannerVisible: Boolean,
        onUpdateBanner: () -> Unit,
        onUpdateIcon: () -> Unit,
        versionText: String,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            val isCompactHeight = maxHeight < 640.dp
            val horizontalPad = if (maxWidth > 600.dp) 32.dp else 20.dp
            val verticalPad = if (isCompactHeight) 12.dp else 16.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPad, vertical = verticalPad),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TopBanner(onUpdateIcon = onUpdateIcon)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(
                        targetState = logged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 480.dp),
                    ) { isLogged ->
                        if (!isLogged) {
                            LoginCard(
                                username = username,
                                onUsernameChange = onUsernameChange,
                                password = password,
                                onPasswordChange = onPasswordChange,
                                passwordVisible = passwordVisible,
                                onTogglePasswordVisible = onTogglePasswordVisible,
                                loginTesting = loginTesting,
                                onLogin = onLogin,
                            )
                        } else {
                            MainCard(
                                stateText = stateText,
                                statusColor = statusColor,
                                batteryText = batteryText,
                                batteryLevel = batteryLevel,
                                batteryColor = batteryColor,
                                detailsLoading = detailsLoading,
                                logText = logText,
                                toggleLabel = toggleLabel,
                                toggleIcon = toggleIcon,
                                toggleEnabled = toggleEnabled,
                                onToggle = onToggle,
                                onDiag = onDiag,
                                isCompactHeight = isCompactHeight,
                            )
                        }
                    }
                }

                Text(
                    text = versionText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A8A8A),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            AnimatedVisibility(
                visible = updateBannerVisible,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                UpdateBanner(onClick = onUpdateBanner)
            }
        }
    }

    @Composable
    private fun TopBanner(onUpdateIcon: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.logo_banner),
                contentDescription = stringResource(R.string.app_name),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 320.dp)
                    .align(Alignment.Center),
            )
            IconButton(
                onClick = onUpdateIcon,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_update),
                    contentDescription = stringResource(R.string.check_updates),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    @Composable
    private fun LoginCard(
        username: String,
        onUsernameChange: (String) -> Unit,
        password: String,
        onPasswordChange: (String) -> Unit,
        passwordVisible: Boolean,
        onTogglePasswordVisible: () -> Unit,
        loginTesting: Boolean,
        onLogin: () -> Unit,
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.login_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    text = stringResource(R.string.login_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = { Text(stringResource(R.string.username_hint)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_person),
                            contentDescription = null,
                            tint = Ink,
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.password_hint)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_lock),
                            contentDescription = null,
                            tint = Ink,
                        )
                    },
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = onTogglePasswordVisible) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = null,
                                tint = Ink,
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                )
                Button(
                    onClick = onLogin,
                    enabled = !loginTesting,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text(
                        text = stringResource(if (loginTesting) R.string.checking else R.string.login_button),
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }

    @Composable
    private fun MainCard(
        stateText: String,
        statusColor: Color,
        batteryText: String,
        batteryLevel: Int,
        batteryColor: Color,
        detailsLoading: Boolean,
        logText: String,
        toggleLabel: Int,
        toggleIcon: Int,
        toggleEnabled: Boolean,
        onToggle: () -> Unit,
        onDiag: () -> Unit,
        isCompactHeight: Boolean,
    ) {
        // Botón con pausa al terminar de cargar: pulso suave con pausa larga
        val isStarted = toggleLabel == R.string.stop
        val btnPulse = rememberInfiniteTransition(label = "btnPulse")
        val btnScale by btnPulse.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 2600
                    1f at 0
                    1f at 800 // pausa inicial
                    1.03f at 1050
                    1f at 1300
                    1f at 2600 // pausa larga
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "btnScale",
        )
        val effectiveScale = if (!detailsLoading && toggleEnabled) btnScale else 1f

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(if (isCompactHeight) 18.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 10.dp else 12.dp),
            ) {
                // Estado grande centrado — jornada iniciada / finalizada
                JourneyHero(
                    isStarted = isStarted,
                    stateText = stateText,
                    statusColor = statusColor,
                    isCompactHeight = isCompactHeight,
                )

                StateBannerPanel(stateText = stateText, statusColor = statusColor)

                if (batteryLevel in 0..100) {
                    BatteryPanel(
                        batteryText = batteryText,
                        batteryLevel = batteryLevel,
                        batteryColor = batteryColor,
                    )
                }

                if (detailsLoading) {
                    DetailsLoadingPanel()
                } else {
                    LogPanel(logText = logText)
                }

                Button(
                    onClick = onToggle,
                    enabled = toggleEnabled,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isCompactHeight) 56.dp else 64.dp)
                        .padding(top = if (isCompactHeight) 4.dp else 8.dp)
                        .graphicsLayer {
                            scaleX = effectiveScale
                            scaleY = effectiveScale
                        },
                ) {
                    Icon(
                        painter = painterResource(toggleIcon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Text(
                        text = stringResource(toggleLabel),
                        fontSize = if (isCompactHeight) 16.sp else 18.sp,
                    )
                }

                TextButton(
                    onClick = onDiag,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.diag_button))
                }
            }
        }
    }

    @Composable
    private fun JourneyHero(
        isStarted: Boolean,
        stateText: String,
        statusColor: Color,
        isCompactHeight: Boolean,
    ) {
        val pulse = rememberInfiniteTransition(label = "heroPulse")
        val alpha by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.52f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "heroAlpha",
        )
        val title = if (isStarted) stringResource(R.string.journey_started_title) else stringResource(R.string.journey_finished_title)
        val subtitle = stateText
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .background(statusColor.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                .padding(vertical = if (isCompactHeight) 14.dp else 18.dp, horizontal = 16.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(if (isCompactHeight) 56.dp else 68.dp)
                    .background(statusColor, CircleShape),
            ) {
                // pulso exterior
                Box(
                    modifier = Modifier
                        .size(if (isCompactHeight) 56.dp else 68.dp)
                        .background(statusColor.copy(alpha = 0.18f * alpha), CircleShape),
                )
                Icon(
                    painter = painterResource(if (isStarted) R.drawable.ic_play else R.drawable.ic_stop),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(if (isCompactHeight) 28.dp else 32.dp),
                )
            }
            Spacer(modifier = Modifier.height(if (isCompactHeight) 8.dp else 10.dp))
            Text(
                text = title,
                style = if (isCompactHeight) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = statusColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }

    @Composable
    private fun StateBannerPanel(stateText: String, statusColor: Color) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFE9F0), RoundedCornerShape(8.dp))
                .padding(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(statusColor, CircleShape),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stateText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    @Composable
    private fun BatteryPanel(
        batteryText: String,
        batteryLevel: Int,
        batteryColor: Color,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFE9F0), RoundedCornerShape(8.dp))
                .padding(14.dp),
        ) {
            Text(
                text = batteryText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            LinearProgressIndicator(
                progress = { batteryLevel / 100f },
                color = batteryColor,
                trackColor = Color(0xFFE0E0E0),
                strokeCap = StrokeCap.Round,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )
        }
    }

    @Composable
    private fun DetailsLoadingPanel() {
        val transition = rememberInfiniteTransition(label = "skeleton")
        val alpha by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "skeletonAlpha",
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFE9F0), RoundedCornerShape(8.dp))
                .padding(14.dp),
        ) {
            SkeletonBar(widthFraction = 0.8f, alpha = alpha)
            Spacer(modifier = Modifier.height(12.dp))
            SkeletonBar(widthFraction = 0.95f, alpha = alpha)
            Spacer(modifier = Modifier.height(12.dp))
            SkeletonBar(widthFraction = 0.65f, alpha = alpha)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    @Composable
    private fun SkeletonBar(widthFraction: Float, alpha: Float) {
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .height(16.dp)
                .background(Color(0xFFE4E4E4), RoundedCornerShape(6.dp))
                .graphicsLayer { this.alpha = alpha },
        )
    }

    @Composable
    private fun LogPanel(logText: String) {
        Text(
            text = logText,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFE9F0), RoundedCornerShape(8.dp))
                .padding(14.dp),
        )
    }

    @Composable
    private fun UpdateBanner(onClick: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_update),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.update_banner),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            )
            Image(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = 180f },
            )
        }
    }
}
