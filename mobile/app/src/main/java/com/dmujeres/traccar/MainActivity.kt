package com.dmujeres.traccar

import android.animation.ObjectAnimator
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.databinding.ActivityMainBinding
import com.dmujeres.traccar.location.TrackingState
import com.dmujeres.traccar.location.TrackingService
import com.dmujeres.traccar.mqtt.MqttManager
import com.dmujeres.traccar.mqtt.MqttStatus
import com.dmujeres.traccar.mqtt.UpdateManager
import com.dmujeres.traccar.util.Notifications
import com.dmujeres.traccar.util.RemoteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla principal, limpia para el colaborador:
 * - Antes de iniciar sesión: formulario de usuario y contraseña (más icono de
 *   actualizaciones arriba a la derecha y versión abajo a la derecha).
 * - Después de iniciar sesión: UN solo botón (iniciar/finalizar jornada) y un
 *   registro de estado fácil de entender, con acceso al diagnóstico.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AppConfig
    private var testing = false
    private var latestUpdate: UpdateManager.Latest? = null
    private var detailsTransition = false
    private var skeletonAnimator: ObjectAnimator? = null
    private var recoveryDialogShown = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiTicker = object : Runnable {
        override fun run() {
            refreshState()
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = AppConfig(this)
        Notifications.ensureChannel(this)

        binding.bannerLogo.setImageResource(R.drawable.logo_banner)
        binding.versionText.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)
        binding.usernameInput.setText(config.username)
        binding.passwordInput.setText(config.password)

        binding.loginButton.setOnClickListener { login() }
        binding.updateButton.setOnClickListener { checkForUpdate(auto = false) }
        binding.updateBanner.setOnClickListener {
            latestUpdate?.let { showUpdateDialog(it) }
        }
        binding.diagButton.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
        }

        binding.toggleButton.setOnClickListener {
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

        ensureBatteryExemption()
        updateView()
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
        skeletonAnimator?.cancel()
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
        val username = binding.usernameInput.text.toString().trim()
        val password = binding.passwordInput.text.toString()
        if (username.isBlank() || password.isBlank()) {
            showDialog(getString(R.string.credentials_required), false)
            return
        }
        if (testing) return
        testing = true
        binding.loginButton.isEnabled = false
        binding.loginButton.text = getString(R.string.checking)

        MqttManager.testConnection(config.serverUrl, username, password) { success, message ->
            runOnUiThread {
                testing = false
                binding.loginButton.isEnabled = true
                binding.loginButton.text = getString(R.string.login_button)
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
        val logged = config.username.isNotBlank() && config.password.isNotBlank()
        crossfade(binding.loginSection, !logged)
        crossfade(binding.mainSection, logged)
        refreshState()
    }

    private fun crossfade(view: android.view.View, visible: Boolean) {
        view.animate().cancel()
        if (visible) {
            view.alpha = 0f
            view.visibility = android.view.View.VISIBLE
            view.animate().alpha(1f).setDuration(300).start()
        } else {
            view.animate().alpha(0f).setDuration(200).withEndAction {
                view.visibility = android.view.View.GONE
            }.start()
        }
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
        binding.stateText.text = stateText(state)
        val toggle = binding.toggleButton
        toggle.setText(if (enabled) R.string.stop else R.string.start)
        toggle.icon = ContextCompat.getDrawable(
            this, if (enabled) R.drawable.ic_stop else R.drawable.ic_play
        )
        toggle.isEnabled = !detailsTransition

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
            lines += getString(R.string.log_battery, battery)
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
            binding.logText.text = lines.joinToString("\n")
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

    private fun beginDetailsLoading() {
        detailsTransition = true
        binding.detailsLoading.visibility = android.view.View.VISIBLE
        binding.logText.visibility = android.view.View.GONE
        skeletonAnimator?.cancel()
        skeletonAnimator = ObjectAnimator.ofFloat(binding.detailsLoading, "alpha", 0.45f, 1f).apply {
            duration = 650
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
        uiHandler.removeCallbacks(detailsTransitionTimeout)
        uiHandler.postDelayed(detailsTransitionTimeout, 15_000)
    }

    private fun endDetailsLoading() {
        detailsTransition = false
        uiHandler.removeCallbacks(detailsTransitionTimeout)
        skeletonAnimator?.cancel()
        skeletonAnimator = null
        binding.detailsLoading.visibility = android.view.View.GONE
        binding.detailsLoading.alpha = 1f
        binding.logText.visibility = android.view.View.VISIBLE
        binding.toggleButton.isEnabled = true
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

    /** Comprueba si hay actualización en el servidor (auto al abrir, o al pulsar el icono). */
    private fun checkForUpdate(auto: Boolean) {
        lifecycleScope.launch {
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
        }
    }

    /** Banner rojo superior (navbar) avisando de la nueva versión. */
    private fun showUpdateBanner() {
        if (binding.updateBanner.visibility == android.view.View.VISIBLE) return
        binding.updateBanner.visibility = android.view.View.VISIBLE
        binding.updateBanner.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(300)
            .start()
    }

    private fun hideUpdateBanner() {
        if (binding.updateBanner.visibility != android.view.View.VISIBLE) return
        binding.updateBanner.animate()
            .translationY(-120f)
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                binding.updateBanner.visibility = android.view.View.GONE
            }
            .start()
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
}
