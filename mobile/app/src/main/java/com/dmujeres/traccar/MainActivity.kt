package com.dmujeres.traccar

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.databinding.ActivityMainBinding
import com.dmujeres.traccar.location.TrackingService
import com.dmujeres.traccar.util.Notifications

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var config: AppConfig

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

        binding.deviceInput.setText(config.deviceId)
        binding.serverInput.setText(config.serverUrl)
        binding.intervalInput.setText(config.intervalSeconds.toString())
        binding.bufferInput.setText(config.bufferMax.toString())

        binding.copyIdButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("deviceId", config.deviceId))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }

        binding.saveButton.setOnClickListener {
            config.deviceId = binding.deviceInput.text.toString()
            config.serverUrl = binding.serverInput.text.toString()
            config.intervalSeconds = binding.intervalInput.text.toString().toLongOrNull() ?: 10L
            config.bufferMax = binding.bufferInput.text.toString().toIntOrNull() ?: 500
            Toast.makeText(this, R.string.save, Toast.LENGTH_SHORT).show()
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
                if (allPermissionsGranted()) {
                    config.trackingEnabled = true
                    TrackingService.start(this)
                    updateUi()
                } else {
                    startIfReady()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun startIfReady() {
        if (allPermissionsGranted()) {
            config.trackingEnabled = true
            TrackingService.start(this)
        }
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
        binding.deviceInput.setText(config.deviceId)
        binding.serverInput.setText(config.serverUrl)
        binding.intervalInput.setText(config.intervalSeconds.toString())
        binding.bufferInput.setText(config.bufferMax.toString())
        val enabled = config.trackingEnabled
        binding.stateText.text = if (enabled) getString(R.string.tracking_on) else getString(R.string.tracking_off)
        binding.toggleButton.setText(if (enabled) R.string.stop else R.string.start)
    }
}
