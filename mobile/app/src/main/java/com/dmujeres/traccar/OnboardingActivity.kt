package com.dmujeres.traccar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.ui.theme.DmujeresTheme
import com.dmujeres.traccar.ui.theme.Primary
import com.dmujeres.traccar.ui.theme.StatusOk
import com.dmujeres.traccar.util.VendorSettings

/**
 * Asistente de primeros pasos que guía al colaborador por los permisos de
 * ubicación, notificaciones, batería y GPS.
 */
class OnboardingActivity : ComponentActivity() {

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshKey++ }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshKey++ }

    private val batteryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshKey++ }

    private var refreshKey by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DmujeresTheme {
                OnboardingContent(
                    refreshKey = refreshKey,
                    onLocation = { requestLocationPermissions() },
                    onNotifications = { requestNotifications() },
                    onBattery = { requestIgnoreBatteryOptimizations() },
                    onGps = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    onVendorOpen = { openVendorSettings() },
                    onFinish = { finishOnboarding() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKey++
    }

    private var vendorPressed = false

    private fun openVendorSettings() {
        val vendor = VendorSettings.currentVendor() ?: return
        val guide = VendorSettings.guideFor(vendor) ?: return
        vendorPressed = true
        val intent = guide.settingsIntent
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                }
            }
        refreshKey++
    }

    private fun requestLocationPermissions() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted && coarseGranted) {
            requestBackgroundLocation()
        } else {
            locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            locationLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            refreshKey++
        }
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            refreshKey++
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            refreshKey++
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
        }.onFailure { refreshKey++ }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        runCatching { batteryLauncher.launch(intent) }
            .onFailure { refreshKey++ }
    }

    private fun finishOnboarding() {
        AppConfig(this).onboardingDone = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    @Composable
    private fun OnboardingContent(
        refreshKey: Int,
        onLocation: () -> Unit,
        onNotifications: () -> Unit,
        onBattery: () -> Unit,
        onGps: () -> Unit,
        onVendorOpen: () -> Unit,
        onFinish: () -> Unit,
    ) {
        val context = LocalContext.current
        val states = rememberStepStates(context, refreshKey)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.logo_banner),
                contentDescription = stringResource(R.string.app_name),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(R.string.welcome_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
            )

            StepButton(
                title = stringResource(R.string.step_location),
                buttonText = stringResource(R.string.grant_location),
                done = states.locationOk,
                onClick = onLocation,
            )
            StepButton(
                title = stringResource(R.string.step_notifications),
                buttonText = stringResource(R.string.grant_notifications),
                done = states.notificationsOk,
                onClick = onNotifications,
            )
            StepButton(
                title = stringResource(R.string.step_battery),
                buttonText = stringResource(R.string.grant_battery),
                done = states.batteryOk,
                onClick = onBattery,
            )
            StepButton(
                title = stringResource(R.string.step_gps),
                buttonText = stringResource(R.string.grant_gps),
                done = states.gpsOk,
                onClick = onGps,
            )

            val vendor = VendorSettings.guideFor(VendorSettings.currentVendor())
            if (vendor != null) {
                Text(
                    text = stringResource(R.string.vendor_step_title, vendor.vendorName)
                        .let { if (vendorPressed) "✓ $it" else it },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = vendor.steps.joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )
                Button(
                    onClick = onVendorOpen,
                    enabled = !vendorPressed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                ) {
                    Text(stringResource(R.string.vendor_open_settings, vendor.vendorName))
                }
            }

            Button(
                onClick = onFinish,
                enabled = states.locationOk && states.notificationsOk && states.batteryOk && states.gpsOk,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.finish))
            }
        }
    }

    private data class StepStates(
        val locationOk: Boolean,
        val notificationsOk: Boolean,
        val batteryOk: Boolean,
        val gpsOk: Boolean,
    )

    @Composable
    private fun StepButton(
        title: String,
        buttonText: String,
        done: Boolean,
        onClick: () -> Unit,
    ) {
        Text(
            text = if (done) "✓ $title" else title,
            style = MaterialTheme.typography.titleMedium,
            color = if (done) StatusOk else Primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onClick,
            enabled = !done,
            colors = if (done) {
                ButtonDefaults.buttonColors(containerColor = StatusOk)
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) {
            Text(buttonText)
        }
    }

    @Composable
    private fun rememberStepStates(
        context: android.content.Context,
        refreshKey: Int,
    ): StepStates {
        return remember(refreshKey) {
            val fineGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val backgroundGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            val locationOk = fineGranted && backgroundGranted
            val notificationsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            val pm = context.getSystemService(PowerManager::class.java)
            val batteryOk = pm.isIgnoringBatteryOptimizations(context.packageName)
            val mode = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            )
            val gpsOk = mode != Settings.Secure.LOCATION_MODE_OFF

            StepStates(locationOk, notificationsOk, batteryOk, gpsOk)
        }
    }
}
