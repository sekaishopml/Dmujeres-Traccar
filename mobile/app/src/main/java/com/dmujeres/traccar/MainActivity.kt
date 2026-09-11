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
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.location.GnssState
import com.dmujeres.traccar.location.TrackingState
import com.dmujeres.traccar.location.TrackingService
import com.dmujeres.traccar.mqtt.MqttManager
import com.dmujeres.traccar.mqtt.MqttStatus
import com.dmujeres.traccar.mqtt.PendingAlertPolicy
import com.dmujeres.traccar.mqtt.UpdateManager
import com.dmujeres.traccar.ui.theme.Background
import com.dmujeres.traccar.ui.theme.DmujeresTheme
import com.dmujeres.traccar.ui.theme.Ink
import com.dmujeres.traccar.ui.components.LoginCard
import com.dmujeres.traccar.ui.components.MetricsRow
import com.dmujeres.traccar.ui.components.StatusBanner
import com.dmujeres.traccar.util.JourneyFormatter
import com.dmujeres.traccar.util.DiagnosticsReporter
import com.dmujeres.traccar.util.SentryLog
import com.dmujeres.traccar.ui.theme.JourneyColors
import com.dmujeres.traccar.util.NetCause
import com.dmujeres.traccar.util.PermissionHealth
import com.dmujeres.traccar.util.snapshot
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
        // TEMPORAL debug de diseño (QA interno, disponible en todos los builds):
        // overrides solo en memoria.
        // EXTRA_DEBUG_LOGGED fuerza logged true/false; EXTRA_DEBUG_DASH ("idle",
        // "active", "error", "" para solo-login) inyecta estado falso de jornada;
        // EXTRA_DEBUG_CLEAR limpia los overrides. No tocan prefs, jornada ni red.
        const val EXTRA_DEBUG_LOGGED = "debug_logged"
        const val EXTRA_DEBUG_DASH = "debug_dash"
        const val EXTRA_DEBUG_CLEAR = "debug_clear"
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
    private var trackingOk by mutableStateOf(false)
    private var journeyHasError by mutableStateOf(false)
    private var batteryText by mutableStateOf("")
    private var batteryLevel by mutableIntStateOf(-1)
    private var batteryColor by mutableStateOf(JourneyColors.Verde)
    private var journeyStartAt by mutableStateOf(0L)
    private var journeyActive by mutableStateOf(false)
    // Par (elapsed monotónico persistido, ancla wall) escrito por TrackingService:
    // base de la duración mostrada, inmune a correcciones NTP tras sueño largo.
    private var journeyElapsedMs by mutableStateOf(0L)
    private var journeyElapsedWallMs by mutableStateOf(0L)
    private var logText by mutableStateOf("")
    // Causa de red (Fase 1): mensaje humano + deep-link a WiFi donde aplica.
    private var netCauseMessage by mutableStateOf<String?>(null)
    private var netCauseWifiAction by mutableStateOf(false)
    // true solo si los pendientes son ANORMALES (ver PendingAlertPolicy): con el dispatch
    // secuencial (1 en vuelo, ackTimeout 15 s) casi siempre hay 1-5 sanos y no deben alarmar.
    private var pendingAbnormal by mutableStateOf(false)
    // Fila de diagnóstico en vivo del dash: valores exactos ya calculados en
    // refreshState (mismas fuentes que Diagnóstico: config.lastFixAt/lastAckAt,
    // conteo RealTime de Room y GnssState en memoria). Nunca se duplica lógica.
    private var diagSatsText by mutableStateOf("—")
    private var diagFixAgo by mutableStateOf("")
    private var diagAckAgo by mutableStateOf("")
    private var diagPending by mutableIntStateOf(0)
    private var diagReportAgo by mutableStateOf("")
    private var detailsLoading by mutableStateOf(false)
    private var toggleLabel by mutableIntStateOf(R.string.start)
    private var toggleIcon by mutableIntStateOf(R.drawable.ic_play)
    private var toggleEnabled by mutableStateOf(true)
    private var updateBannerVisible by mutableStateOf(false)

    // TEMPORAL debug de diseño: overrides solo en memoria (null = comportamiento real).
    private var debugLoggedOverride: Boolean? = null
    private var debugDashMode: String? = null
    private var debugTapCount = 0
    private var debugFirstTapAt = 0L

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

        // TEMPORAL debug de diseño: leer overrides antes de la auditoría para
        // que el modo preview (debugDashMode/debugLoggedOverride) la salte.
        readDebugExtras(intent)

        // AUDITORÍA POST-OTA: tras una actualización en la app, OEMs (ZTE/Mifavor,
        // Xiaomi) suelen revocar permisos especiales y `onboardingDone` (one-shot)
        // sigue en true, así que la pérdida sería silenciosa. Si cambió la versión
        // y falta algo crítico, reabrir SOLO los pasos de reparación.
        if (config.onboardingDone && !isDebugPreview()) {
            if (config.appVersionCode != BuildConfig.VERSION_CODE) {
                val missing = PermissionHealth.check(this).missing
                if (missing.isNotEmpty()) {
                    runCatching {
                        SentryLog.breadcrumb("diag", "ota_update", "audit=repair_shown missing=${missing.size}")
                        DiagnosticsReporter.report(this, "ota_update")
                    }
                    startActivity(
                        Intent(this, OnboardingActivity::class.java)
                            .putExtra(OnboardingActivity.EXTRA_REPAIR, true)
                            .putExtra(
                                OnboardingActivity.EXTRA_REPAIR_STEPS,
                                PermissionHealth.repairStepsFor(missing).toIntArray(),
                            ),
                    )
                    // NO marcar appVersionCode aquí: si el usuario no completa,
                    // volvemos a preguntar en el próximo arranque.
                    finish()
                    return
                }
                config.appVersionCode = BuildConfig.VERSION_CODE
                runCatching {
                    SentryLog.breadcrumb("diag", "ota_update", "audit=clean")
                    DiagnosticsReporter.report(this, "ota_update")
                }
            }
        }

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
                    trackingOk = trackingOk,
                    journeyHasError = journeyHasError,
                    batteryText = batteryText,
                    batteryLevel = batteryLevel,
                    batteryColor = batteryColor,
                    journeyStartAt = journeyStartAt,
                    journeyActive = journeyActive,
                    journeyElapsedMs = journeyElapsedMs,
                    journeyElapsedWallMs = journeyElapsedWallMs,
                    detailsLoading = detailsLoading,
                    logText = logText,
                    pendingAbnormal = pendingAbnormal,
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
                    onVersionTap = ::onVersionTapped,
                    netCauseMessage = netCauseMessage,
                    netCauseWifiAction = netCauseWifiAction,
                    onOpenWifiSettings = ::openWifiSettings,
                    diagSatsText = diagSatsText,
                    diagFixAgo = diagFixAgo,
                    diagAckAgo = diagAckAgo,
                    diagPending = diagPending,
                    diagReportAgo = diagReportAgo,
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
        readDebugExtras(intent)
        updateView()
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

    /** Exige la exención de batería: sin ella Android congela el GPS en reposo.
     * Con "No volver a mostrar" persistido en SharedPreferences "dmj_tracking"
     * (clave "battery_dialog_dismissed", directo sin AppConfig por restricción).
     * No se toca AppConfig.kt. */
    private fun ensureBatteryExemption() {
        val dismissPrefs = getSharedPreferences("dmj_tracking", MODE_PRIVATE)
        if (dismissPrefs.getBoolean("battery_dialog_dismissed", false)) return
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
            .setNeutralButton(R.string.battery_dismiss) { _, _ ->
                dismissPrefs.edit().putBoolean("battery_dialog_dismissed", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    // TEMPORAL debug de diseño: lee overrides solo en memoria (null = real).
    // EXTRA_DEBUG_CLEAR limpia ambos; LOGGED fuerza logged; DASH (ifBlank→null)
    // inyecta estado falso de jornada. No toca prefs, jornada ni red.
    private fun readDebugExtras(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_DEBUG_CLEAR, false)) {
            debugLoggedOverride = null
            debugDashMode = null
            return
        }
        if (intent.hasExtra(EXTRA_DEBUG_LOGGED)) {
            debugLoggedOverride = intent.getBooleanExtra(EXTRA_DEBUG_LOGGED, false)
        }
        if (intent.hasExtra(EXTRA_DEBUG_DASH)) {
            val mode = intent.getStringExtra(EXTRA_DEBUG_DASH)
            debugDashMode = if (mode.isNullOrBlank()) null else mode
        }
    }

    private fun isDebugPreview(): Boolean {
        return debugLoggedOverride != null || debugDashMode != null
    }

    private fun debugToast(): Boolean {
        if (!isDebugPreview()) return false
        Toast.makeText(this, R.string.debug_preview_locked, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun onVersionTapped() {
        val now = SystemClock.elapsedRealtime()
        if (now - debugFirstTapAt > 3_000) {
            debugTapCount = 0
            debugFirstTapAt = now
        }
        debugTapCount++
        if (debugTapCount >= 5) {
            debugTapCount = 0
            debugFirstTapAt = 0L
            startActivity(Intent(this, DebugDesignActivity::class.java))
        }
    }

    private fun applyDebugDashState() {
        val mode = debugDashMode
        logged = true
        journeyActive = (mode != "idle")
        journeyStartAt = if (journeyActive) System.currentTimeMillis() - 2 * 60 * 60 * 1000 else 0L
        // Preview sin servicio: elapsed=0/ancla=0 → displayElapsedMs cae al ancla
        // de inicio (legacy) y la tarjeta muestra las 2 h sintéticas.
        journeyElapsedMs = 0L
        journeyElapsedWallMs = 0L
        detailsLoading = false
        pendingAbnormal = false
        updateBannerVisible = false
        netCauseMessage = null
        netCauseWifiAction = false
        when (mode) {
            "active" -> {
                stateText = getString(R.string.state_active)
                trackingOk = true
                journeyHasError = false
                batteryLevel = 85
                batteryText = "85%"
                batteryColor = JourneyColors.Verde
                logText = listOf(
                    getString(R.string.log_journey_on, 2, 14),
                    getString(R.string.log_server_on),
                    getString(R.string.log_battery, 85),
                ).joinToString("\n")
                toggleLabel = R.string.stop
                toggleIcon = R.drawable.ic_stop
            }
            "error" -> {
                stateText = getString(R.string.state_network)
                trackingOk = false
                journeyHasError = true
                batteryLevel = 42
                batteryText = "42%"
                batteryColor = JourneyColors.Verde
                logText = listOf(
                    getString(R.string.log_journey_on, 0, 37),
                    getString(R.string.log_server_off),
                    getString(R.string.log_battery, 42),
                ).joinToString("\n")
                toggleLabel = R.string.stop
                toggleIcon = R.drawable.ic_stop
            }
            else -> {
                stateText = getString(R.string.tracking_off)
                trackingOk = false
                journeyHasError = false
                batteryLevel = 85
                batteryText = "85%"
                batteryColor = JourneyColors.Verde
                logText = listOf(
                    getString(R.string.log_journey_off),
                    getString(R.string.log_server_off),
                    getString(R.string.log_battery, 85),
                ).joinToString("\n")
                toggleLabel = R.string.start
                toggleIcon = R.drawable.ic_play
            }
        }
        toggleEnabled = true
        // Fila de diagnóstico en modo preview: valores fijos coherentes con la
        // tarjeta mostrada (no tocan la ruta real de refreshState).
        when (mode) {
            "active" -> {
                diagSatsText = "9/14"
                diagFixAgo = getString(R.string.ago_now)
                diagAckAgo = getString(R.string.ago_minutes, 1)
                diagReportAgo = getString(R.string.ago_hours, 2)
            }
            "error" -> {
                diagSatsText = "0/0"
                diagFixAgo = getString(R.string.ago_minutes, 12)
                diagAckAgo = getString(R.string.ago_minutes, 8)
                diagReportAgo = getString(R.string.ago_minutes, 5)
            }
            else -> {
                diagSatsText = "—"
                diagFixAgo = ""
                diagAckAgo = ""
                diagReportAgo = ""
            }
        }
        diagPending = if (mode == "error") 3 else 0
    }

    private fun login() {
        if (debugToast()) return
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
        if (debugToast()) return
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION)
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
        debugLoggedOverride?.let { logged = it }
        if (debugDashMode != null) logged = true
        refreshState()
    }

    /** Registro de estado en lenguaje simple para el colaborador. */
    private fun refreshState() {
        if (debugDashMode != null) {
            applyDebugDashState()
            return
        }
        val enabled = config.trackingEnabled
        val storedState = TrackingState.fromName(config.trackingState)
        val state = if (enabled && !TrackingService.isRunning) {
            TrackingState.SERVICE_RECOVERY
        } else {
            storedState
        }
        stateText = stateText(state)
        trackingOk = state == TrackingState.TRACKING_ACTIVE
        journeyHasError = journeyHasError(state)
        toggleLabel = if (enabled) R.string.stop else R.string.start
        toggleIcon = if (enabled) R.drawable.ic_stop else R.drawable.ic_play
        toggleEnabled = !detailsTransition

        val lines = mutableListOf<String>()
        val now = System.currentTimeMillis()
        lines += getString(R.string.log_state, stateText(state))

        val journey = config.journeyStartAt
        journeyStartAt = journey
        journeyElapsedMs = config.journeyElapsedMs
        journeyElapsedWallMs = config.journeyElapsedWallMs
        journeyActive = enabled && journey > 0
        if (enabled && journey > 0) {
            // Duración vista = elapsed monotónico persistido por el servicio + gap
            // desde su ancla (nunca now - inicio): inmune a correcciones NTP.
            val displayMs = JourneyFormatter.displayElapsedMs(journeyElapsedMs, journeyElapsedWallMs, journey, now)
            val (hours, minutes) = JourneyFormatter.durationParts(displayMs)
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
            // Sistema de 3 colores estricto: sin ámbar; batería baja = rojo, resto = verde.
            batteryColor = if (battery in 1..20) JourneyColors.Rojo else JourneyColors.Verde
            // Solo el porcentaje para la tarjeta (MetricsRow lo muestra como "85%").
            // No se añade línea de batería a `lines`: la tarjeta ya la muestra y
            // LogPanel la filtraría para no duplicar el %.
            batteryText = "$battery%"
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

        // Fila de diagnóstico en vivo: reutiliza exactamente los valores de
        // arriba (mismo tick de 3 s) + GnssState en memoria (la misma fuente
        // que lee Diagnóstico) + config.diagnosticsLastReportAt del monitor.
        diagFixAgo = if (lastFix > 0) agoText(lastFix) else ""
        diagAckAgo = if (lastAck > 0) agoText(lastAck) else ""
        val satsUsed = GnssState.satsUsed
        val satsTotal = GnssState.satsTotal
        diagSatsText = if (GnssState.hasData() && satsUsed != null && satsTotal != null) {
            "$satsUsed/$satsTotal"
        } else {
            "—"
        }
        val lastReport = config.diagnosticsLastReportAt
        diagReportAgo = if (lastReport > 0) agoText(lastReport) else ""

        // Causa de red (Fase 1): el servicio es autoritativo cuando corre (usa
        // previousLabel correcto); si está detenido se calcula en vivo para la UI.
        val storedCause = NetCause.fromValue(config.netCause)
        val liveCause = runCatching { NetCause.detect(snapshot(this, config.netLabel)) }.getOrNull()
        val effectiveCause = if (TrackingService.isRunning) {
            storedCause ?: liveCause ?: NetCause.OK
        } else {
            liveCause ?: storedCause ?: NetCause.OK
        }
        val showNetCause = journeyActive && effectiveCause != NetCause.OK
        netCauseMessage = if (showNetCause) effectiveCause.userMessage() else null
        netCauseWifiAction = if (showNetCause) effectiveCause.opensWifiSettings() else false
        if (showNetCause) {
            netCauseMessage?.let { lines += it }
        }

        lifecycleScope.launch {
            val pendingInfo = withContext(Dispatchers.IO) {
                runCatching {
                    val dao = (application as DmujeresApp).database.positionDao()
                    dao.count() to dao.oldestEnqueuedAt()
                }.getOrDefault(0 to null)
            }
            val pending = pendingInfo.first
            val oldest = pendingInfo.second
            // Solo es anormal lo que un pipeline sano no produce (ver PendingAlertPolicy):
            // 1-5 pendientes recientes con ACK al día muestran "Sincronizando N…" en verde.
            val abnormal = PendingAlertPolicy.isAbnormal(
                pendingCount = pending,
                oldestPendingAt = oldest,
                lastAckAt = lastAck,
                now = now,
            )
            pendingAbnormal = abnormal
            // Mismo conteo RealTime de Room: alimenta el chip "Pendientes".
            diagPending = pending
            lines += when {
                pending <= 0 -> getString(R.string.log_pending_none)
                abnormal -> getString(R.string.log_pending, pending)
                else -> getString(R.string.log_pending_sync, pending)
            }
            if (pending > 0 && oldest != null && oldest > 0L) {
                lines += getString(R.string.log_oldest_pending, agoText(oldest))
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

    /**
     * En jornada, SERVICE_RECOVERY es transitorio (banner blanco con texto verde);
     * cualquier otro estado no-OK es error (banner blanco con texto rojo).
     * Fuera de jornada el banner es siempre rojo (ver journeyBannerColors).
     */
    private fun journeyHasError(state: TrackingState): Boolean = when (state) {
        TrackingState.TRACKING_ACTIVE,
        TrackingState.SERVICE_RECOVERY,
        TrackingState.TRACKING_DISABLED_BY_USER -> false
        else -> true
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

    private fun agoText(timestamp: Long): String =
        JourneyFormatter.agoText(this, timestamp)

    /** Resumen de la jornada recién finalizada. */
    private fun showJourneySummary() {
        val startedAt = config.journeyStartAt
        if (startedAt <= 0) return
        // Mismo cálculo que la tarjeta: elapsed monotónico persistido + gap desde
        // su ancla (capturado antes de que el servicio limpie las métricas).
        val durationMs = JourneyFormatter.displayElapsedMs(
            config.journeyElapsedMs, config.journeyElapsedWallMs, startedAt, System.currentTimeMillis(),
        )
        val duration = JourneyFormatter.journeyDuration(this, durationMs)
        val km = JourneyFormatter.formatKm(config.journeyDistanceM)
        val points = config.journeyPoints
        val confirmedPoints = config.journeyConfirmedPoints
        val summary = JourneyFormatter.buildSummary(duration, km, points, confirmedPoints)
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
        if (debugDashMode != null) return
        if (updateCheckInFlight) return
        updateCheckInFlight = true
        lifecycleScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { UpdateManager.check(config.serverUrl) }
                config.lastUpdateCheckAt = System.currentTimeMillis()
                if (latest == null) {
                    config.lastUpdateLatest = ""
                    config.lastUpdateError = UpdateManager.lastError ?: "sin respuesta"
                    hideUpdateBanner()
                    Notifications.clearUpdateAvailable(this@MainActivity)
                    if (!auto) {
                        val reason = config.lastUpdateError
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.update_unreachable) + if (reason.isBlank()) "" else "\n$reason",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@launch
                }
                config.lastUpdateLatest = latest.version
                config.lastUpdateError = ""
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
                val reason = UpdateManager.lastError
                config.lastUpdateCheckAt = System.currentTimeMillis()
                config.lastUpdateLatest = latest.version
                config.lastUpdateError = reason ?: "sin respuesta"
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.update_error) + if (reason.isNullOrBlank()) "" else "\n$reason",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun openDiagnostics() {
        startActivity(Intent(this, DiagnosticsActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
    }

    private fun openWifiSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
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
        trackingOk: Boolean,
        journeyHasError: Boolean,
        batteryText: String,
        batteryLevel: Int,
        batteryColor: Color,
        journeyStartAt: Long,
        journeyActive: Boolean,
        journeyElapsedMs: Long = 0L,
        journeyElapsedWallMs: Long = 0L,
        detailsLoading: Boolean,
        logText: String,
        pendingAbnormal: Boolean,
        toggleLabel: Int,
        toggleIcon: Int,
        toggleEnabled: Boolean,
        onToggle: () -> Unit,
        onDiag: () -> Unit,
        updateBannerVisible: Boolean,
        onUpdateBanner: () -> Unit,
        onUpdateIcon: () -> Unit,
        versionText: String,
        onVersionTap: () -> Unit = {},
        netCauseMessage: String? = null,
        netCauseWifiAction: Boolean = false,
        onOpenWifiSettings: () -> Unit = {},
        diagSatsText: String = "—",
        diagFixAgo: String = "",
        diagAckAgo: String = "",
        diagPending: Int = 0,
        diagReportAgo: String = "",
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
            // Compacto: menos padding vertical para que todo quepa sin scroll.
            val verticalPad = if (isCompactHeight) 8.dp else 12.dp
            // Orden vertical: logo (TopBanner, con espacio superior mayor) ->
            // jornada activa (StatusBanner inmediatamente debajo del logo, con
            // 10-12 dp de gap) -> métricas -> botón. Sin scroll nuevo. El grupo
            // logo+banner queda alto (centre-alto). UpdateBanner es overlay
            // TopCenter: cuando es visible se reserva su altura (~48.dp) arriba
            // del contenido para no tapar el logo.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPad, vertical = verticalPad)
                    .padding(top = if (updateBannerVisible) 48.dp else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TopBanner(
                    onUpdateIcon = onUpdateIcon,
                    isCompactHeight = isCompactHeight,
                    updateBannerVisible = updateBannerVisible,
                    slimTop = !logged,
                )

                // TopCenter: el contenido logueado (MainCard) arranca justo
                // debajo del logo, de modo que StatusBanner quede pegado a él;
                // el login se centra en su propio Box de tamaño completo.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Crossfade(
                        targetState = logged,
                        modifier = Modifier
                            .widthIn(max = 480.dp)
                            .fillMaxSize(),
                    ) { isLogged ->
                        if (!isLogged) {
                            // Bienvenido debajo del logo + tarjeta solo con campos.
                            // Pegado arriba (no centrado): en pantallas altas el
                            // bloque quedaba muy abajo.
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Top,
                            ) {
                                Text(
                                    text = stringResource(R.string.login_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 2.dp),
                                )
                                Text(
                                    text = stringResource(R.string.login_subtitle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp),
                                )
                                LoginCard(
                                    username = username,
                                    onUsernameChange = onUsernameChange,
                                    password = password,
                                    onPasswordChange = onPasswordChange,
                                    passwordVisible = passwordVisible,
                                    onTogglePasswordVisible = onTogglePasswordVisible,
                                    loginTesting = loginTesting,
                                    onLogin = onLogin,
                                    showHeader = false,
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                MainCard(
                                    stateText = stateText,
                                    trackingOk = trackingOk,
                                    journeyHasError = journeyHasError,
                                    batteryText = batteryText,
                                    batteryLevel = batteryLevel,
                                    batteryColor = batteryColor,
                                    journeyStartAt = journeyStartAt,
                                    journeyActive = journeyActive,
                                    journeyElapsedMs = journeyElapsedMs,
                                    journeyElapsedWallMs = journeyElapsedWallMs,
                                    detailsLoading = detailsLoading,
                                    logText = logText,
                                    pendingAbnormal = pendingAbnormal,
                                    toggleLabel = toggleLabel,
                                    toggleIcon = toggleIcon,
                                    toggleEnabled = toggleEnabled,
                                    onToggle = onToggle,
                                    onDiag = onDiag,
                                    isCompactHeight = isCompactHeight,
                                    netCauseMessage = netCauseMessage,
                                    netCauseWifiAction = netCauseWifiAction,
                                    onOpenWifiSettings = onOpenWifiSettings,
                                    diagSatsText = diagSatsText,
                                    diagFixAgo = diagFixAgo,
                                    diagAckAgo = diagAckAgo,
                                    diagPending = diagPending,
                                    diagReportAgo = diagReportAgo,
                                )
                            }
                        }
                    }
                }

                // Versión integrada: una sola línea con ellipsis para no empujar.
                Text(
                    text = versionText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = if (isCompactHeight) 4.dp else 8.dp)
                        .clickable(onClick = onVersionTap),
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
    private fun TopBanner(
        onUpdateIcon: () -> Unit,
        isCompactHeight: Boolean,
        updateBannerVisible: Boolean,
        slimTop: Boolean = false,
    ) {
        // Espacio superior mayor (statusBarsPadding + 28-40 dp): el logo baja y
        // queda JUSTO ENCIMA del banner de jornada activa (StatusBanner, en
        // MainCard alineado arriba en MainScreen) con 10-12 dp de gap.
        // En login (slimTop) el espacio es mínimo para que el conjunto quede
        // centrado en pantalla. El botón de actualizar va en overlay pegado
        // ARRIBA (a nivel de la barra de estado), fuera del área del logo.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = when {
                            slimTop -> if (isCompactHeight) 8.dp else 12.dp
                            isCompactHeight -> 28.dp
                            else -> 40.dp
                        },
                        bottom = if (isCompactHeight) 10.dp else 12.dp,
                    )
                    .padding(horizontal = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Logo ópticamente centrado: reserva simétrica de 48.dp (ancho del
                // IconButton) a cada lado para no desplazarse a la izquierda.
                Image(
                    painter = painterResource(R.drawable.logo_banner),
                    contentDescription = stringResource(R.string.app_name),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = if (isCompactHeight) 240.dp else 320.dp),
                )
            }
            // Mientras el banner rojo de actualización es visible
            // (updateBannerVisible) el botón no se compone: la propia acción ya
            // está en pantalla. El logo no se mueve (overlay simétrico).
            if (!updateBannerVisible) {
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
    }

    @Composable
    private fun MainCard(
        stateText: String,
        trackingOk: Boolean,
        journeyHasError: Boolean,
        batteryText: String,
        batteryLevel: Int,
        batteryColor: Color,
        journeyStartAt: Long,
        journeyActive: Boolean,
        journeyElapsedMs: Long = 0L,
        journeyElapsedWallMs: Long = 0L,
        detailsLoading: Boolean,
        logText: String,
        pendingAbnormal: Boolean,
        toggleLabel: Int,
        toggleIcon: Int,
        toggleEnabled: Boolean,
        onToggle: () -> Unit,
        onDiag: () -> Unit,
        isCompactHeight: Boolean,
        netCauseMessage: String? = null,
        netCauseWifiAction: Boolean = false,
        onOpenWifiSettings: () -> Unit = {},
        diagSatsText: String = "—",
        diagFixAgo: String = "",
        diagAckAgo: String = "",
        diagPending: Int = 0,
        diagReportAgo: String = "",
    ) {
        val isStarted = toggleLabel == R.string.stop

        Column(
            modifier = Modifier.fillMaxWidth(),
            // Compacto: menos espacio para que todo quepa sin scroll en ~640dp.
            verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 8.dp else 12.dp),
        ) {
            // Estado — banner con sistema de 3 colores estricto (ver JourneyColors).
            StatusBanner(
                isStarted = isStarted,
                trackingOk = trackingOk,
                hasError = journeyHasError,
                stateText = stateText,
                isCompactHeight = isCompactHeight,
                netCauseMessage = netCauseMessage,
                showWifiAction = netCauseWifiAction,
                onOpenWifiSettings = onOpenWifiSettings,
            )

            // Métricas — grid de 3 items
            if (!detailsLoading) {
                MetricsRow(
                    batteryText = batteryText,
                    batteryLevel = batteryLevel,
                    batteryColor = batteryColor,
                    logText = logText,
                    isCompactHeight = isCompactHeight,
                    journeyStartAt = journeyStartAt,
                    journeyActive = journeyActive,
                    journeyElapsedMs = journeyElapsedMs,
                    journeyElapsedWallMs = journeyElapsedWallMs,
                    // Solo rojo si PendingAlertPolicy dice anormal; 1-5 sanos quedan en verde.
                    pendingAlert = pendingAbnormal,
                )
            } else {
                DetailsLoadingPanel(isCompactHeight = isCompactHeight)
            }

            // Fila compacta de diagnóstico EN VIVO (always-on): GPS (sats usados/
            // totales), Fix (último fix), Ack (última confirmación), Pendientes y
            // Reporte (último reporte del monitor). Mismas fuentes que Diagnóstico;
            // el estado del servidor sigue en el dot de MetricsRow.
            DiagLiveRow(
                gps = diagSatsText,
                fix = diagFixAgo.ifBlank { stringResource(R.string.chip_never) },
                ack = diagAckAgo.ifBlank { stringResource(R.string.chip_never) },
                pending = diagPending.toString(),
                report = diagReportAgo.ifBlank { stringResource(R.string.chip_never) },
                isCompactHeight = isCompactHeight,
            )

            // Botón principal — grande y prominente, con transición suave de
            // color: verde al activar (Iniciar) y rojo al finalizar (regla 3
            // colores). La animación va en ambos sentidos.
            val toggleTargetColor =
                if (isStarted) MaterialTheme.colorScheme.primary else JourneyColors.Verde
            val toggleColor by animateColorAsState(
                targetValue = toggleTargetColor,
                animationSpec = tween(durationMillis = 600),
                label = "toggleColor",
            )
            Button(
                onClick = onToggle,
                enabled = toggleEnabled,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = toggleColor,
                    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isCompactHeight) 56.dp else 64.dp),
            ) {
                Icon(
                    painter = painterResource(toggleIcon),
                    contentDescription = stringResource(toggleLabel),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(if (isCompactHeight) 22.dp else 26.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(toggleLabel),
                    fontSize = if (isCompactHeight) 16.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Diagnóstico — sutil (48dp mínimo táctil, sin rediseño).
            TextButton(
                onClick = onDiag,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.diag_button),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    /**
     * Fila de 5 chips de diagnóstico en vivo (GPS/Fix/Ack/Pendientes/Reporte).
     * labelSmall + bodySmall como las tarjetas existentes; una línea por texto
     * con encogido tipo FitText para que nada se lea recortado en 360 dp.
     */
    @Composable
    private fun DiagLiveRow(
        gps: String,
        fix: String,
        ack: String,
        pending: String,
        report: String,
        isCompactHeight: Boolean,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(if (isCompactHeight) 4.dp else 6.dp),
        ) {
            DiagChip(stringResource(R.string.chip_gps), gps, Modifier.weight(1f))
            DiagChip(stringResource(R.string.chip_fix), fix, Modifier.weight(1f))
            DiagChip(stringResource(R.string.chip_ack), ack, Modifier.weight(1f))
            DiagChip(stringResource(R.string.chip_pending), pending, Modifier.weight(1f))
            DiagChip(stringResource(R.string.chip_report), report, Modifier.weight(1f))
        }
    }

    @Composable
    private fun DiagChip(label: String, value: String, modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(JourneyColors.Blanco)
                .padding(horizontal = 3.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            DiagFitText(
                text = label,
                baseStyle = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                minSize = 7.sp,
            )
            DiagFitText(
                text = value,
                baseStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                minSize = 8.sp,
            )
        }
    }

    /**
     * Variante privada del encogido de MetricsRow (mismo patrón onSizeChanged +
     * TextMeasurer, suelo duro en [minSize], sin crecer sobre la base): los chips
     * caben en 5 columnas de ~60 dp en 360 dp sin elipsis ni recortes.
     */
    @Composable
    private fun DiagFitText(
        text: String,
        baseStyle: TextStyle,
        color: Color,
        minSize: TextUnit,
    ) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        var availablePx by remember { mutableStateOf(0f) }
        val fittedSp = remember(text, availablePx, baseStyle, minSize, density) {
            val basePx = with(density) { baseStyle.fontSize.toPx() }
            val minPx = with(density) { minSize.toPx() }
            val scale = if (availablePx > 0f && basePx > 0f) {
                val widthAtBasePx = measurer.measure(
                    text = text,
                    style = baseStyle,
                    softWrap = false,
                    maxLines = 1,
                ).size.width.toFloat()
                val usablePx = availablePx - with(density) { 2.dp.toPx() }
                if (widthAtBasePx > 0f && usablePx > 0f) {
                    (usablePx / widthAtBasePx).coerceIn(minPx / basePx, 1f)
                } else {
                    1f
                }
            } else {
                1f
            }
            with(density) { (basePx * scale).toSp() }
        }
        Text(
            text = text,
            style = baseStyle.copy(fontSize = fittedSp),
            color = color,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { availablePx = it.width.toFloat() },
        )
    }

    @Composable
    private fun DetailsLoadingPanel(isCompactHeight: Boolean) {
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
        // Compacto: menos padding y barras más bajas para no empujar sin scroll.
        val panelPad = if (isCompactHeight) 10.dp else 14.dp
        val barHeight = if (isCompactHeight) 12.dp else 16.dp
        val gapLarge = if (isCompactHeight) 8.dp else 12.dp
        val gapSmall = if (isCompactHeight) 6.dp else 8.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(JourneyColors.Blanco, RoundedCornerShape(8.dp))
                .padding(panelPad),
        ) {
            SkeletonBar(widthFraction = 0.8f, alpha = alpha, barHeight = barHeight)
            Spacer(modifier = Modifier.height(gapLarge))
            SkeletonBar(widthFraction = 0.95f, alpha = alpha, barHeight = barHeight)
            Spacer(modifier = Modifier.height(gapLarge))
            SkeletonBar(widthFraction = 0.65f, alpha = alpha, barHeight = barHeight)
            Spacer(modifier = Modifier.height(gapSmall))
        }
    }

    @Composable
    private fun SkeletonBar(widthFraction: Float, alpha: Float, barHeight: androidx.compose.ui.unit.Dp = 16.dp) {
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .height(barHeight)
                .background(JourneyColors.Rojo.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                .graphicsLayer { this.alpha = alpha },
        )
    }

    @Composable
    private fun LogPanel(logText: String) {
        // La tarjeta de batería ya muestra el nivel: no repetir su línea
        // ("Batería · NN%" de log_battery) en el registro para no duplicar el %.
        // Se conserva el aviso de batería baja ("Aviso · …"), que empieza por "Aviso".
        // Sin scroll: altura acotada con maxLines + ellipsis en vez de crecer.
        val filtered = logText.lines()
            .filterNot { it.trim().startsWith("Batería ·") && "%" in it }
            .joinToString("\n")
            .trim()
        Text(
            text = filtered,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(JourneyColors.Blanco, RoundedCornerShape(8.dp))
                .padding(10.dp),
        )
    }

    @Composable
    private fun UpdateBanner(onClick: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_update),
                contentDescription = stringResource(R.string.check_updates),
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
