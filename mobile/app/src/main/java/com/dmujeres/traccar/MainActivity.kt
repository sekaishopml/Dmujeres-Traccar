package com.dmujeres.traccar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    ) { grant ->
        if (grant.values.all { it }) {
            Toast.makeText(this, R.string.permission_title, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = AppConfig(this)
        Notifications.ensureChannel(this)

        binding.serverInput.setText(config.serverUrl)
        binding.deviceInput.setText(config.deviceId)
        binding.saveButton.setOnClickListener {
            config.serverUrl = binding.serverInput.text.toString()
            config.deviceId = binding.deviceInput.text.toString()
            Toast.makeText(this, R.string.save, Toast.LENGTH_SHORT).show()
        }
        binding.toggleButton.setOnClickListener {
            if (config.trackingEnabled) {
                config.trackingEnabled = false
                TrackingService.stop(this)
                updateUi()
            } else {
                if (requestPermissions()) {
                    config.trackingEnabled = true
                    TrackingService.start(this)
                    updateUi()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun updateUi() {
        binding.serverInput.setText(config.serverUrl)
        binding.deviceInput.setText(config.deviceId)
        val enabled = config.trackingEnabled
        binding.stateText.text = if (enabled) getString(R.string.tracking_on) else getString(R.string.tracking_off)
        binding.toggleButton.setText(if (enabled) R.string.stop else R.string.start)
    }

    private fun requestPermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
            return false
        }
        return true
    }
}
