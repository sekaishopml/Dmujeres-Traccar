package com.dmujeres.traccar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.databinding.ActivityOnboardingBinding

/**
 * Asistente de primeros pasos: guía al colaborador por TODOS los permisos necesarios
 * (ubicación, notificaciones, batería y GPS) para que la app quede ultra activa.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    private val batteryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.stepLocationButton.setOnClickListener {
            locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
        binding.stepNotificationsButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                locationLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                refresh()
            }
        }
        binding.stepBatteryButton.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }
        binding.stepGpsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        binding.finishButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val locationOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val notificationsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        val batteryOk = isIgnoringBatteryOptimizations()
        val gpsOk = isLocationEnabled()

        setDone(binding.stepLocationTitle, binding.stepLocationButton, locationOk)
        setDone(binding.stepNotificationsTitle, binding.stepNotificationsButton, notificationsOk)
        setDone(binding.stepBatteryTitle, binding.stepBatteryButton, batteryOk)
        setDone(binding.stepGpsTitle, binding.stepGpsButton, gpsOk)

        binding.finishButton.isEnabled = locationOk && notificationsOk && batteryOk && gpsOk
    }

    private fun setDone(title: android.widget.TextView, button: android.widget.Button, done: Boolean) {
        if (done) {
            title.text = "✓ " + title.text.toString().removePrefix("✓ ")
            button.isEnabled = false
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        runCatching { batteryLauncher.launch(intent) }
            .onFailure { refresh() }
    }

    private fun isLocationEnabled(): Boolean {
        val mode = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.LOCATION_MODE,
            Settings.Secure.LOCATION_MODE_OFF
        )
        return mode != Settings.Secure.LOCATION_MODE_OFF
    }
}
