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
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.databinding.ActivityOnboardingBinding
import com.dmujeres.traccar.util.VendorSettings

/**
 * Asistente de primeros pasos que guía al colaborador por los permisos de
 * ubicación, notificaciones, batería y GPS.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
            || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            requestBackgroundLocation()
        }
        refresh()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    private val batteryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupVendorStep()

        binding.stepLocationButton.setOnClickListener {
            val fineGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarseGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (fineGranted && coarseGranted) {
                requestBackgroundLocation()
            } else {
                // Android 10+ NO permite pedir el permiso de fondo junto con los de
                // primer plano en la misma llamada: el sistema lo descarta y el diálogo
                // no aparece. Se piden primero los de primer plano y después, por
                // separado, el de "Permitir todo el tiempo".
                locationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
            }
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
            if (binding.finishButton.isEnabled) {
                AppConfig(this).onboardingDone = true
            }
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private var vendorPressed = false

    private fun setupVendorStep() {
        val vendor = VendorSettings.currentVendor() ?: return
        val guide = VendorSettings.guideFor(vendor) ?: return
        binding.vendorTitle.visibility = android.view.View.VISIBLE
        binding.vendorBody.visibility = android.view.View.VISIBLE
        binding.vendorButton.visibility = android.view.View.VISIBLE
        binding.vendorTitle.text = getString(R.string.vendor_step_title, guide.vendorName)
        binding.vendorBody.text = guide.steps.joinToString("\n")
        binding.vendorButton.text = getString(R.string.vendor_open_settings, guide.vendorName)
        binding.vendorButton.setOnClickListener {
            vendorPressed = true
            val intent = guide.settingsIntent
            runCatching { startActivity(intent) }
                .onFailure { runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) } }
            refresh()
        }
    }

    private fun refresh() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val backgroundGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        val locationOk = fineGranted && backgroundGranted
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
        if (binding.vendorButton.visibility == android.view.View.VISIBLE && vendorPressed) {
            binding.vendorTitle.text = "✓ " + binding.vendorTitle.text.toString().removePrefix("✓ ")
            binding.vendorButton.isEnabled = false
        }
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

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            refresh()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            refresh()
            return
        }
        val previouslyRequested = AppConfig(this).backgroundLocationAsked
        if (previouslyRequested) {
            openAppSettings()
        } else {
            AppConfig(this).backgroundLocationAsked = true
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure { refresh() }
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
