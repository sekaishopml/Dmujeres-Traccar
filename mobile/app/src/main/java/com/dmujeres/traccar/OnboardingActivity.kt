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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.ui.theme.BgDark
import com.dmujeres.traccar.ui.theme.BorderGlass
import com.dmujeres.traccar.ui.theme.DmujeresTheme
import com.dmujeres.traccar.ui.theme.GlassWhite06
import com.dmujeres.traccar.ui.theme.NeonCyan
import com.dmujeres.traccar.ui.theme.NeonPink
import com.dmujeres.traccar.ui.theme.NeonViolet
import com.dmujeres.traccar.ui.theme.Primary
import com.dmujeres.traccar.ui.theme.StatusOk
import com.dmujeres.traccar.ui.theme.SurfaceGlass
import com.dmujeres.traccar.ui.theme.SurfaceGlassLight
import com.dmujeres.traccar.ui.theme.TextPrimary
import com.dmujeres.traccar.ui.theme.TextSecondary
import com.dmujeres.traccar.ui.theme.White
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BgDark)
        ) {
            // top neon glow
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                NeonPink.copy(alpha = 0.12f),
                                NeonViolet.copy(alpha = 0.08f),
                                Color.Transparent
                            ),
                            center = Offset(540f, 100f),
                            radius = 800f
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.verticalGradient(listOf(BgDark, SurfaceGlass))
                        )
                        .border(1.dp, BorderGlass, RoundedCornerShape(20.dp))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.logo_banner),
                        contentDescription = stringResource(R.string.app_name),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .widthIn(max = 260.dp)
                            .fillMaxWidth(),
                    )
                }

                Text(
                    text = stringResource(R.string.welcome_title),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.welcome_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.fillMaxWidth(),
                )

                StepCard(
                    title = stringResource(R.string.step_location),
                    buttonText = stringResource(R.string.grant_location),
                    done = states.locationOk,
                    onClick = onLocation,
                )
                StepCard(
                    title = stringResource(R.string.step_notifications),
                    buttonText = stringResource(R.string.grant_notifications),
                    done = states.notificationsOk,
                    onClick = onNotifications,
                )
                StepCard(
                    title = stringResource(R.string.step_battery),
                    buttonText = stringResource(R.string.grant_battery),
                    done = states.batteryOk,
                    onClick = onBattery,
                )
                StepCard(
                    title = stringResource(R.string.step_gps),
                    buttonText = stringResource(R.string.grant_gps),
                    done = states.gpsOk,
                    onClick = onGps,
                )

                val vendor = VendorSettings.guideFor(VendorSettings.currentVendor())
                if (vendor != null) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderGlass, RoundedCornerShape(20.dp))
                            .background(
                                Brush.linearGradient(listOf(SurfaceGlassLight, SurfaceGlass)),
                                RoundedCornerShape(20.dp)
                            )
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(listOf(NeonPink, NeonViolet))
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = "⚙", fontSize = 16.sp, color = White)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.vendor_step_title, vendor.vendorName)
                                        .let { if (vendorPressed) "✓ $it" else it },
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (vendorPressed) StatusOk else NeonCyan,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                text = vendor.steps.joinToString("\n"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp, bottom = 12.dp),
                            )
                            Button(
                                onClick = onVendorOpen,
                                enabled = !vendorPressed,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonCyan,
                                    contentColor = White,
                                    disabledContainerColor = SurfaceGlassLight,
                                    disabledContentColor = TextSecondary
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.vendor_open_settings, vendor.vendorName))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                val allDone = states.locationOk && states.notificationsOk && states.batteryOk && states.gpsOk
                val finishGradient = if (allDone) {
                    Brush.horizontalGradient(listOf(Primary, NeonViolet))
                } else {
                    Brush.horizontalGradient(listOf(SurfaceGlassLight, SurfaceGlassLight))
                }
                Button(
                    onClick = onFinish,
                    enabled = allDone,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        contentColor = White,
                        disabledContentColor = TextSecondary
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .shadow(
                            if (allDone) 12.dp else 0.dp,
                            RoundedCornerShape(16.dp),
                            ambientColor = Primary.copy(alpha = 0.35f)
                        )
                        .clip(RoundedCornerShape(16.dp))
                        .background(finishGradient, RoundedCornerShape(16.dp))
                        .border(1.dp, if (allDone) GlassWhite06 else BorderGlass, RoundedCornerShape(16.dp)),
                ) {
                    Text(
                        text = stringResource(R.string.finish),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = if (allDone) White else TextSecondary
                    )
                }
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
    private fun StepCard(
        title: String,
        buttonText: String,
        done: Boolean,
        onClick: () -> Unit,
    ) {
        val scale by animateFloatAsState(
            targetValue = if (done) 1f else 0.96f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
            label = "checkScale"
        )
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (done) StatusOk.copy(alpha = 0.35f) else BorderGlass, RoundedCornerShape(20.dp))
                .background(
                    if (done) Brush.linearGradient(listOf(SurfaceGlassLight, SurfaceGlass))
                    else Brush.linearGradient(listOf(SurfaceGlassLight.copy(alpha = 0.9f), SurfaceGlass)),
                    RoundedCornerShape(20.dp)
                )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (done) Brush.linearGradient(listOf(StatusOk, Color(0xFF00C853)))
                                else Brush.linearGradient(listOf(BorderGlass, SurfaceGlassLight))
                            )
                            .border(1.dp, if (done) StatusOk else BorderGlass, CircleShape)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (done) "✓" else "•",
                            color = if (done) White else TextSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (done) StatusOk else TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (done) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(StatusOk.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Listo",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = StatusOk
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onClick,
                    enabled = !done,
                    shape = RoundedCornerShape(12.dp),
                    colors = if (done) {
                        ButtonDefaults.buttonColors(containerColor = StatusOk, contentColor = White)
                    } else {
                        ButtonDefaults.buttonColors(containerColor = Primary, contentColor = White)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(if (done) R.drawable.ic_stat_pin else R.drawable.ic_update),
                        contentDescription = null,
                        tint = if (done) White else White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(buttonText, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    @Composable
    private fun rememberStepStates(
        context: android.content.Context,
        refreshKey: Int,
    ): StepStates {
        return androidx.compose.runtime.remember(refreshKey) {
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
