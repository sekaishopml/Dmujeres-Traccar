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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.ui.theme.DmujeresTheme
import com.dmujeres.traccar.ui.theme.Primary
import com.dmujeres.traccar.ui.theme.StatusOk
import com.dmujeres.traccar.util.LocationState
import com.dmujeres.traccar.util.VendorSettings

/**
 * Asistente de primeros pasos que guía al colaborador por los permisos de
 * ubicación, notificaciones, batería y GPS.
 */
class OnboardingActivity : ComponentActivity() {

    companion object {
        // TEMPORAL debug de diseño: paso inicial + modo preview (no persiste nada).
        const val EXTRA_DEBUG_STEP = "debug_step"
        const val EXTRA_DEBUG_PREVIEW = "debug_preview"
        // Reparación post-OTA: solo se muestran los pasos indicados por indices.
        const val EXTRA_REPAIR = "debug_repair"
        const val EXTRA_REPAIR_STEPS = "repair_steps"
    }

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
        // TEMPORAL debug de diseño (QA interno, disponible en todos los builds).
        val debugPreview = intent.getBooleanExtra(EXTRA_DEBUG_PREVIEW, false)
        val debugStep = intent.getIntExtra(EXTRA_DEBUG_STEP, 0).coerceIn(0, 3)
        // Reparación post-OTA: la auditoría de MainActivity manda los indices de
        // los pasos cuyos permisos fueron revocados. El preview debug gana.
        val repairSteps = if (
            intent.getBooleanExtra(EXTRA_REPAIR, false) && !debugPreview
        ) {
            intent.getIntArrayExtra(EXTRA_REPAIR_STEPS)
                ?.toList()?.filter { it in 0..2 }?.distinct()?.sorted()?.ifEmpty { null }
        } else {
            null
        }
        val allSteps = (0..3).toList()
        setContent {
            DmujeresTheme {
                OnboardingContent(
                    refreshKey = refreshKey,
                    steps = repairSteps ?: allSteps,
                    initialIndex = if (debugPreview) allSteps.indexOf(debugStep).coerceAtLeast(0) else 0,
                    repair = repairSteps != null,
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
        val needsFsl = OnboardingPolicy.needsForegroundLocationPermission(Build.VERSION.SDK_INT)
        val fslGranted = !needsFsl || ContextCompat.checkSelfPermission(
            this, Manifest.permission.FOREGROUND_SERVICE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted && coarseGranted && fslGranted) {
            requestBackgroundLocation()
        } else {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            // Android 14+: el foreground con tipo location exige
            // FOREGROUND_SERVICE_LOCATION en runtime. Se pide junto a FINE
            // (el BACKGROUND sigue yendo aparte por exigirlo el sistema).
            if (needsFsl && !fslGranted) {
                perms += Manifest.permission.FOREGROUND_SERVICE_LOCATION
            }
            locationLauncher.launch(perms.toTypedArray())
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
        // Xiaomi/Redmi e Infinix/Tecno: el diálogo del sistema para fondo suele
        // fallar en silencio; ir directo a Ajustes donde "Permitir siempre" sí sale.
        if (VendorSettings.requiresSettingsForBackground()) {
            AppConfig(this).backgroundLocationAsked = true
            openAppSettings()
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
        // TEMPORAL debug de diseño: en preview no se persiste nada ni se avanza.
        if (intent.getBooleanExtra(EXTRA_DEBUG_PREVIEW, false)) {
            finish()
            return
        }
        AppConfig(this).onboardingDone = true
        // Marca la versión con la que el onboarding quedó revalidado: tras una
        // OTA que revoque permisos, MainActivity compara este valueCode y ofrece
        // reparar (onboardingDone ya era true y no volvería a abrirse solo).
        AppConfig(this).appVersionCode = BuildConfig.VERSION_CODE
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    @Composable
    private fun OnboardingContent(
        refreshKey: Int,
        steps: List<Int> = (0..3).toList(),
        initialIndex: Int = 0,
        repair: Boolean = false,
        onLocation: () -> Unit,
        onNotifications: () -> Unit,
        onBattery: () -> Unit,
        onGps: () -> Unit,
        onVendorOpen: () -> Unit,
        onFinish: () -> Unit,
    ) {
        val context = LocalContext.current
        val states = rememberStepStates(context, refreshKey)
        var index by remember { mutableIntStateOf(initialIndex.coerceIn(0, steps.lastIndex)) }
        val step = steps[index]
        val vendor = VendorSettings.guideFor(VendorSettings.currentVendor())
        val allOk = states.locationOk && states.notificationsOk && states.batteryOk && states.gpsOk
        // Secuencia de activación: Siguiente NO alumbra hasta completar el paso
        // actual (ubicación → avisos → batería). Así se detecta qué falta.
        val stepOk = OnboardingPolicy.isStepComplete(
            step = step,
            locationOk = states.locationOk,
            notificationsOk = states.notificationsOk,
            batteryOk = states.batteryOk,
        )

        // Sin scroll: asistente de 4 pasos, cada paso cabe en pantalla.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.logo_banner),
                contentDescription = stringResource(R.string.app_name),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .widthIn(max = 200.dp)
                    .fillMaxWidth(),
            )

            Text(
                text = "Paso ${index + 1} de ${steps.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // Barra de progreso animada: avanza suave entre pasos.
            val animatedProgress by animateFloatAsState(
                targetValue = (index + 1) / steps.size.toFloat(),
                animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
                label = "onboardingProgress",
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when (step) {
                    0 -> Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (repair) {
                            // Reparación post-OTA: el intro normal se reemplaza por
                            // el aviso de permisos restablecidos.
                            Text(
                                text = stringResource(R.string.repair_title),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.welcome_title),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = stringResource(R.string.welcome_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        StepButton(
                            title = stringResource(R.string.step_location),
                            buttonText = stringResource(R.string.grant_location),
                            detail = stringResource(R.string.step_location_detail),
                            icon = Icons.Filled.LocationOn,
                            done = states.locationOk,
                            onClick = onLocation,
                        )
                    }
                    1 -> StepButton(
                        title = stringResource(R.string.step_notifications),
                        buttonText = stringResource(R.string.grant_notifications),
                        detail = stringResource(R.string.step_notifications_detail),
                        icon = Icons.Filled.Notifications,
                        done = states.notificationsOk,
                        onClick = onNotifications,
                    )
                    2 -> StepButton(
                        title = stringResource(R.string.step_battery),
                        buttonText = stringResource(R.string.grant_battery),
                        detail = stringResource(R.string.step_battery_detail),
                        icon = Icons.Filled.BatteryChargingFull,
                        done = states.batteryOk,
                        onClick = onBattery,
                    )
                    else -> Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        StepButton(
                            title = stringResource(R.string.step_gps),
                            buttonText = stringResource(R.string.grant_gps),
                            detail = stringResource(R.string.step_gps_detail),
                            icon = Icons.Filled.MyLocation,
                            done = states.gpsOk,
                            onClick = onGps,
                        )
                        if (vendor != null) {
                            Text(
                                text = stringResource(R.string.vendor_step_title, vendor.vendorName)
                                    .let { if (vendorPressed) "✓ $it" else it },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = vendor.steps.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 10,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = onVendorOpen,
                                enabled = !vendorPressed,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(R.string.vendor_open_settings, vendor.vendorName),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (index > 0) {
                    TextButton(
                        onClick = { index-- },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Atrás")
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (index < steps.lastIndex) {
                    // Siguiente sencillo: sin elevación, altura fija, sin rebotes.
                    // Apagado (sin alumbrar) hasta completar el paso actual.
                    Button(
                        onClick = { index++ },
                        enabled = stepOk,
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        Text("Siguiente")
                    }
                } else {
                    Button(
                        onClick = onFinish,
                        // En reparación solo se exige completar el paso pendiente
                        // (el paso GPS/vendor no forma parte de la lista).
                        enabled = if (repair) stepOk else allOk,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(R.string.finish),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
    private fun StepButton(
        title: String,
        buttonText: String,
        done: Boolean,
        onClick: () -> Unit,
        detail: String = "",
        icon: ImageVector? = null,
    ) {
        // Sin scroll: columna compacta centrada para el celular.
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (icon != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            if (done) StatusOk.copy(alpha = 0.12f)
                            else Primary.copy(alpha = 0.12f),
                        ),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (done) StatusOk else Primary,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Text(
                text = if (done) "✓ $title" else title,
                style = MaterialTheme.typography.titleSmall,
                color = if (done) StatusOk else Primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = onClick,
                enabled = !done,
                colors = if (done) {
                    ButtonDefaults.buttonColors(containerColor = StatusOk)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    buttonText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            // Android 14+: sin FOREGROUND_SERVICE_LOCATION el servicio con
            // foregroundServiceType location lanza SecurityException al arrancar.
            val fslGranted = !OnboardingPolicy.needsForegroundLocationPermission(Build.VERSION.SDK_INT) ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.FOREGROUND_SERVICE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            val locationOk = OnboardingPolicy.isLocationComplete(
                fineGranted = fineGranted,
                backgroundGranted = backgroundGranted,
                fslGranted = fslGranted,
                sdkInt = Build.VERSION.SDK_INT,
            )
            val notificationsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            val pm = context.getSystemService(PowerManager::class.java)
            val batteryOk = pm.isIgnoringBatteryOptimizations(context.packageName)
            val gpsOk = LocationState.isEnabled(context)

            StepStates(locationOk, notificationsOk, batteryOk, gpsOk)
        }
    }
}

/**
 * Requisitos puros del onboarding para GPS robusto (JVM, sin Android) para
 * `testDebugUnitTest`. El flujo sigue en 4 pasos: el paso 1 (ubicación) cubre
 * FINE + BACKGROUND + FOREGROUND_SERVICE_LOCATION (Android 14+); los otros
 * pasos (notificaciones, batería, GPS) no cambian.
 */
object OnboardingPolicy {
    /** Android 14 (UPSIDE_DOWN_CAKE, API 34) exige FSL en runtime. */
    const val FSL_SDK = 34

    fun needsForegroundLocationPermission(sdkInt: Int): Boolean = sdkInt >= FSL_SDK

    /**
     * Paso 1 completo: FINE + BACKGROUND siempre, más FSL solo en API 34+.
     * En API < 34 `fslGranted` se ignora (puede venir false sin bloquear).
     */
    fun isLocationComplete(
        fineGranted: Boolean,
        backgroundGranted: Boolean,
        fslGranted: Boolean,
        sdkInt: Int,
    ): Boolean {
        if (!fineGranted || !backgroundGranted) return false
        if (needsForegroundLocationPermission(sdkInt) && !fslGranted) return false
        return true
    }

    /**
     * Secuencia de activación GPS: cada paso exige lo suyo antes de alumbrar
     * "Siguiente" (0=ubicación, 1=avisos, 2=batería). El paso 3 (GPS) usa el
     * botón final con allOk. Puro para tests.
     */
    fun isStepComplete(
        step: Int,
        locationOk: Boolean,
        notificationsOk: Boolean,
        batteryOk: Boolean,
    ): Boolean = when (step) {
        0 -> locationOk
        1 -> notificationsOk
        2 -> batteryOk
        else -> true
    }
}
