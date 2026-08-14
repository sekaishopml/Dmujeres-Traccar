package com.dmujeres.traccar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.databinding.ActivityMainBinding
import com.dmujeres.traccar.location.TrackingService
import com.dmujeres.traccar.mqtt.MqttManager
import com.dmujeres.traccar.util.Notifications
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

        binding.saveButton.setOnClickListener {
            saveAndTest()
        }

        binding.permissionsButton.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        binding.toggleButton.setOnClickListener {
            if (config.trackingEnabled) {
                config.trackingEnabled = false
                TrackingService.stop(this)
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

    private fun updateUi() {
        binding.versionText.text = getString(R.string.app_version, BuildConfig.VERSION_NAME)
        binding.usernameInput.setText(config.username)
        binding.passwordInput.setText(config.password)
        binding.serverInput.setText(config.serverUrl)
        binding.intervalInput.setText(config.intervalSeconds.toString())
        binding.bufferInput.setText(config.bufferMax.toString())
        val enabled = config.trackingEnabled
        binding.stateText.text = if (enabled) getString(R.string.tracking_on) else getString(R.string.tracking_off)
        binding.toggleButton.setText(if (enabled) R.string.stop else R.string.start)
    }
}
