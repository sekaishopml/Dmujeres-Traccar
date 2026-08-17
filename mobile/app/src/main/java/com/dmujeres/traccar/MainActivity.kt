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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.databinding.ActivityMainBinding
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

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiTicker = object : Runnable {
        override fun run() {
            refreshState()
            uiHandler.postDelayed(this, 3_000)
        }
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
        binding.diagButton.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        binding.toggleButton.setOnClickListener {
            if (config.trackingEnabled) {
                config.trackingEnabled = false
                TrackingService.stop(this)
                showJourneySummary()
                refreshState()
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
            if (!TrackingService.isRunning) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.killed_title)
                    .setMessage(R.string.killed_body)
                    .setPositiveButton(R.string.killed_reactivate) { _, _ ->
                        config.trackingEnabled = true
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
        config.trackingEnabled = true
        TrackingService.start(this)
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

    /** Muestra login o pantalla principal según haya credenciales guardadas. */
    private fun updateView() {
        val logged = config.username.isNotBlank() && config.password.isNotBlank()
        binding.loginSection.visibility = if (logged) android.view.View.GONE else android.view.View.VISIBLE
        binding.mainSection.visibility = if (logged) android.view.View.VISIBLE else android.view.View.GONE
        refreshState()
    }

    /** Registro de estado en lenguaje simple para el colaborador. */
    private fun refreshState() {
        binding.stateText.text = getString(
            if (config.trackingEnabled) R.string.tracking_on else R.string.tracking_off
        )

        val lines = mutableListOf<String>()
        val now = System.currentTimeMillis()

        val journey = config.journeyStartAt
        if (journey > 0) {
            val elapsed = (now - journey).coerceAtLeast(0)
            val hours = elapsed / 3_600_000
            val minutes = (elapsed % 3_600_000) / 60_000
            lines += getString(R.string.log_journey_on, hours, minutes)
        } else {
            lines += getString(R.string.log_journey_off)
        }

        lines += if (MqttStatus.status == MqttStatus.CONNECTED) {
            getString(R.string.log_server_on)
        } else {
            getString(R.string.log_server_off)
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

        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                runCatching { (application as DmujeresApp).database.positionDao().count() }.getOrDefault(0)
            }
            lines += if (pending > 0) getString(R.string.log_pending, pending)
            else getString(R.string.log_pending_none)
            if (battery in 1..20) {
                lines += getString(R.string.log_warn_battery)
            }
            val lastFix = config.lastFixAt
            if (lastFix > 0 && now - lastFix > 5 * 60_000) {
                lines += getString(R.string.log_warn_gps)
            }
            binding.logText.text = lines.joinToString("\n")
        }
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
        val summary = "$duration|$km|$points"
        config.lastJourneySummary = summary
        config.lastSummaryNotified = ""
        AlertDialog.Builder(this)
            .setTitle(R.string.journey_summary_title)
            .setMessage(getString(R.string.journey_summary_body, duration, km, points))
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    /** Comprueba si hay actualización en el servidor (auto al abrir, o al pulsar el icono). */
    private fun checkForUpdate(auto: Boolean) {
        lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) { UpdateManager.check(config.serverUrl) }
            if (latest == null || !UpdateManager.isNewer(BuildConfig.VERSION_NAME, latest.version)) {
                if (!auto) {
                    Toast.makeText(this@MainActivity, R.string.update_no_new, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.update_found, latest.version))
                .setMessage(getString(R.string.update_notes, latest.notes ?: ""))
                .setPositiveButton(R.string.update_now) { _, _ ->
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
                .setNegativeButton(R.string.update_later, null)
                .show()
        }
    }
}