package com.dmujeres.traccar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.databinding.ActivityMainBinding
import com.dmujeres.traccar.location.TrackingService
import com.dmujeres.traccar.mqtt.MqttManager
import com.dmujeres.traccar.mqtt.UpdateManager
import com.dmujeres.traccar.util.Notifications
import com.dmujeres.traccar.util.VendorSettings
import com.dmujeres.traccar.BuildConfig

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AppConfig
    private var testing = false

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

        binding.versionText.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)
        binding.usernameInput.setText(config.username)
        binding.passwordInput.setText(config.password)
        binding.serverInput.setText(config.serverUrl)
        binding.intervalInput.setText(config.intervalSeconds.toString())
        binding.bufferInput.setText(config.bufferMax.toString())
        binding.ackTimeoutInput.setText(config.ackTimeoutSeconds.toString())
        binding.maxRetriesInput.setText(config.maxRetries.toString())

        val policies = listOf(
            AppConfig.POLICY_DROP_OLDEST to getString(R.string.policy_drop_oldest),
            AppConfig.POLICY_STOP_CAPTURE to getString(R.string.policy_stop_capture),
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, policies.map { it.second })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.policySpinner.adapter = adapter
        binding.policySpinner.setSelection(
            policies.indexOfFirst { it.first == config.bufferPolicy }.coerceAtLeast(0)
        )

        binding.saveButton.setOnClickListener {
            saveAndTest()
        }

        binding.permissionsButton.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        binding.diagButton.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        binding.updateButton.setOnClickListener {
            checkForUpdate(auto = false)
        }

        ensureBatteryExemption()

        binding.toggleButton.setOnClickListener {
            if (config.trackingEnabled) {
                config.trackingEnabled = false
                TrackingService.stop(this)
                showJourneySummary()
                updateUi()
            } else {
                if (config.username.isBlank() || config.password.isBlank()) {
                    Toast.makeText(this, R.string.credentials_required, Toast.LENGTH_LONG).show()
                } else {
                    saveAndTest { success ->
                        if (success) startIfReady()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        checkForUpdate(auto = true)
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

    private fun saveAndTest(onDone: ((Boolean) -> Unit)? = null) {
        val username = binding.usernameInput.text.toString().trim()
        val password = binding.passwordInput.text.toString()
        val server = binding.serverInput.text.toString().trim()
        if (username.isBlank() || password.isBlank()) {
            showDialog(getString(R.string.credentials_required), false)
            return
        }
        if (testing) return
        testing = true
        binding.saveButton.isEnabled = false
        binding.saveButton.text = getString(R.string.checking)
        binding.stateText.text = getString(R.string.checking_connection)

        MqttManager.testConnection(server, username, password) { success, message ->
            runOnUiThread {
                testing = false
                binding.saveButton.isEnabled = true
                binding.saveButton.text = getString(R.string.save)
                if (success) {
                    config.username = username
                    config.password = password
                    config.serverUrl = server
                    config.intervalSeconds = binding.intervalInput.text.toString().toLongOrNull() ?: 10L
                    config.bufferMax = binding.bufferInput.text.toString().toIntOrNull() ?: 500
                    binding.stateText.text = getString(R.string.connect_success)
                    showDialog(getString(R.string.connect_success), true)
                } else {
                    binding.stateText.text = message
                    showDialog(message, false)
                }
                onDone?.invoke(success)
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
        updateUi()
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

    /** Resumen de la jornada recién finalizada. */
    private fun showJourneySummary() {
        val startedAt = config.journeyStartAt
        if (startedAt <= 0) return
        val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
        val hours = durationMs / 3_600_000
        val minutes = (durationMs % 3_600_000) / 60_000
        val duration = getString(R.string.journey_duration, hours, minutes)
        val km = String.format(Locale.US, "%.1f", config.journeyDistanceM / 1000.0)
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

    /** Comprueba si hay actualización en el servidor (auto al abrir, o al pulsar el botón). */
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

    private fun updateUi() {
        binding.versionText.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)
        binding.usernameInput.setText(config.username)
        binding.passwordInput.setText(config.password)
        binding.serverInput.setText(config.serverUrl)
        binding.intervalInput.setText(config.intervalSeconds.toString())
        binding.bufferInput.setText(config.bufferMax.toString())
        binding.ackTimeoutInput.setText(config.ackTimeoutSeconds.toString())
        binding.maxRetriesInput.setText(config.maxRetries.toString())
        val enabled = config.trackingEnabled
        binding.stateText.text = if (enabled) getString(R.string.tracking_on) else getString(R.string.tracking_off)
        binding.toggleButton.setText(if (enabled) R.string.stop else R.string.start)
    }
}
