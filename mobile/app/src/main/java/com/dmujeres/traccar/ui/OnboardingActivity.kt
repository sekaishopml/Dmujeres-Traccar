package com.dmujeres.traccar.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.dmujeres.traccar.BuildConfig
import com.dmujeres.traccar.R
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.readiness.CheckKey
import com.dmujeres.traccar.readiness.CheckState
import com.dmujeres.traccar.readiness.ContinuityTracker
import com.dmujeres.traccar.readiness.DeviceReadinessChecker
import com.dmujeres.traccar.readiness.DeviceReadinessPolicy
import com.dmujeres.traccar.ui.theme.DmujeresTheme
import com.dmujeres.traccar.ui.theme.Primary
import com.dmujeres.traccar.ui.theme.StatusOk
import com.dmujeres.traccar.platform.LocationState
import com.dmujeres.traccar.oem.VendorSettings

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
        val debugStep = intent.getIntExtra(EXTRA_DEBUG_STEP, 0).coerceIn(0, 4)
        // Reparación post-OTA: la auditoría de MainActivity manda los indices de
        // los pasos cuyos permisos fueron revocados. El preview debug gana.
        val repairSteps = if (
            intent.getBooleanExtra(EXTRA_REPAIR, false) && !debugPreview
        ) {
            // El paso vendor (4) pasa cuando el llamador lo pide EXPLÍCITO
            // (gate anti-OEM: forzar re-abrir la guía aunque ya esté marcada)
            // o cuando aún no fue confirmado (persistido). Los índices fuera
            // de 0..4 se descartan (defensa: el intent viene de dentro).
            val requested = intent.getIntArrayExtra(EXTRA_REPAIR_STEPS)
                ?.toList()?.filter { it in 0..4 }?.distinct() ?: emptyList()
            val vendorPending = VendorSettings.currentVendor() != null &&
                !AppConfig(this).vendorGuideDone
            val steps = if (vendorPending) requested + listOf(4) else requested
            // La prueba de CONTINUIDAD ya NO forma parte del asistente: vive en
            // Diagnóstico (§21). La reparación solo repara permisos/ajustes.
            steps.distinct().sorted().ifEmpty { null }
        } else {
            null
        }
        // Los pasos dinámicos se calculan dentro de OnboardingContent en cada
        // revalidación (onResume); el modo reparación manda sobre la lista.
        setContent {
            DmujeresTheme {
                OnboardingContent(
                    refreshKey = refreshKey,
                    // Preview de diseño: lista fija de un solo paso (el pedido).
                    // Reparación: lista fija desde el auditor. Onboarding normal:
                    // la lista se recalcula en cada revalidación (onResume).
                    repairSteps = when {
                        debugPreview -> listOf(debugStep)
                        else -> repairSteps
                    },
                    onLocation = { requestLocationPermissions() },
                    onNotifications = { requestNotifications() },
                    onBattery = { requestIgnoreBatteryOptimizations() },
                    onGps = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    onVendorOpen = { openVendorSettings() },
                    onVendorSecondary = { openVendorSecondary() },
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
        // Persistido: la guía del fabricante se marca hecha al abrirla (el
        // usuario vuelve del vendor manualmente). Si no la hizo, la puede
        // relanzar desde "Permisos y batería".
        AppConfig(this).vendorGuideDone = true
        val genericSettings = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        // Samsung: el deeplink "Never sleeping apps" solo se intenta si Device
        // Care (com.samsung.android.lool) está instalado; si no, directo al
        // fallback genérico. Cualquier fallo de lanzamiento cae al fallback.
        val primary = if (vendor == "samsung" && !VendorSettings.samsungDeviceCareInstalled(packageManager)) {
            null
        } else {
            guide.settingsIntent
        }
        val intent = primary ?: genericSettings
        // 1.1.8: cadena OEM con fallback. Primero se prueban TODOS los specs del
        // fabricante (componentes de ROM, pueden faltar o estar bloqueados) y,
        // si ninguno lanza, se cae a Ajustes de la app. Nunca se declara éxito
        // de un ajuste: solo se abre la pantalla.
        val launched = runCatching { startActivity(intent); true }.getOrDefault(false)
        if (!launched) {
            val chain = VendorSettings.intentChainFor(vendor)
            var opened = false
            for (spec in chain) {
                opened = runCatching {
                    startActivity(VendorSettings.intentFor(spec, packageName))
                    true
                }.getOrDefault(false)
                if (opened) break
            }
            if (!opened) {
                runCatching { startActivity(genericSettings) }
            }
        }
        refreshKey++
    }

    private fun openVendorSecondary() {
        val vendor = VendorSettings.currentVendor() ?: return
        val guide = VendorSettings.guideFor(vendor) ?: return
        val intent = guide.secondaryIntent ?: return
        vendorPressed = true
        AppConfig(this).vendorGuideDone = true
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
        repairSteps: List<Int>? = null,
        initialIndex: Int = 0,
        onLocation: () -> Unit,
        onNotifications: () -> Unit,
        onBattery: () -> Unit,
        onGps: () -> Unit,
        onVendorOpen: () -> Unit,
        onVendorSecondary: () -> Unit,
        onFinish: () -> Unit,
    ) {
        val context = LocalContext.current
        // Revalidación local del gate: startTest muta AppConfig sin pasar por
        // onResume; este contador fuerza recalcular evaluación y pasos.
        var gateRefresh by remember { mutableIntStateOf(0) }
        val evalKey = refreshKey + gateRefresh
        val states = rememberStepStates(context, evalKey)
        val vendor = VendorSettings.guideFor(VendorSettings.currentVendor())
        val vendorDone = remember(evalKey) {
            AppConfig(context).vendorGuideDone
        }
        // Evaluación del DeviceReadinessGate (lectura barata): de aquí EMERGEN
        // los pasos pendientes y el veredicto; nunca listas fijas por OEM.
        val readiness = remember(evalKey) { DeviceReadinessChecker.evaluate(context) }
        // OEM pendiente de mitigar: guía presente sin confirmar. El check
        // OEM_CONFIG queda CONFIRMED (≠ PASS) con la guía ya hecha; re-agregar
        // el paso 4 con "!= PASS" sería un bucle infinito.
        val oemGatePending = readiness.checks[CheckKey.OEM_CONFIG]?.state == CheckState.NOT_VERIFIABLE
        // La batería cubre bucket RESTRICTED (FAILED) y restricción de fondo
        // (WARNING/FAILED): mismo paso existente, sin índice nuevo por OEM.
        val batteryGatePending =
            readiness.checks[CheckKey.STANDBY_BUCKET]?.state == CheckState.FAILED ||
                readiness.checks[CheckKey.BACKGROUND_RESTRICTED]?.state == CheckState.WARNING ||
                readiness.checks[CheckKey.BACKGROUND_RESTRICTED]?.state == CheckState.FAILED
        // Pasos dinámicos: se recalculan en cada revalidación (onResume, vuelta
        // de Ajustes, permisos concedidos). Solo aparecen los que faltan; los
        // ya completos desaparecen y N se encoge. Reparación: lista fija.
        val derived = remember(evalKey, repairSteps) {
            if (repairSteps != null) {
                repairSteps
            } else {
                OnboardingPolicy.gateSteps(
                    base = OnboardingPolicy.dynamicSteps(
                        locationEnabled = states.gpsOk,
                        locationOk = states.locationOk,
                        notificationsOk = states.notificationsOk,
                        batteryOk = states.batteryOk,
                        vendorPending = vendor != null && !vendorDone,
                    ),
                    batteryGatePending = batteryGatePending,
                    oemGatePending = oemGatePending,
                )
            }
        }
        // Honestidad del gate: "Todo está listo" SOLO con veredicto READY del
        // DeviceReadinessGate (checks públicos + OEM confirmado).
        val steps = derived
        // Todo completo → pantalla final "Todo está listo" (sin pasos que
        // mostrar). En reparación, lista vacía = nada por reparar: cerrar.
        if (steps.isEmpty()) {
            if (repairSteps != null) {
                LaunchedEffect(Unit) { onFinish() }
                return
            }
            ReadyScreen(onFinish = onFinish)
            return
        }
        // Índice seguro: nunca apuntar a un paso que desapareció; si el actual
        // quedó completo, saltar al primero pendiente de la lista revalidada.
        var index by remember { mutableIntStateOf(initialIndex.coerceIn(0, maxOf(0, steps.lastIndex))) }
        LaunchedEffect(steps, evalKey) {
            if (index > steps.lastIndex) {
                index = steps.lastIndex
            }
            // Si el paso actual ya está completo, saltar al primero pendiente.
            val current = steps.getOrNull(index)
            if (current != null && OnboardingPolicy.isStepComplete(
                    step = current,
                    locationOk = states.locationOk,
                    notificationsOk = states.notificationsOk,
                    batteryOk = states.batteryOk,
                    vendorOk = vendorDone,
                )
            ) {
                val firstPending = steps.indexOfFirst {
                    !OnboardingPolicy.isStepComplete(
                        step = it,
                        locationOk = states.locationOk,
                        notificationsOk = states.notificationsOk,
                        batteryOk = states.batteryOk,
                        vendorOk = vendorDone,
                    )
                }
                index = if (firstPending >= 0) firstPending else steps.lastIndex
            }
        }
        // Lectura segura: el índice puede salir de rango justo en la
        // recomposición donde la lista se encogió (antes del efecto).
        val idx = index.coerceIn(0, maxOf(0, steps.lastIndex))
        val step = steps[idx]
        val allOk = states.locationOk && states.notificationsOk && states.batteryOk && states.gpsOk
        // Secuencia de activación: Siguiente NO alumbra hasta completar el paso
        // actual (ubicación → avisos → batería). Así se detecta qué falta.
        val stepOk = OnboardingPolicy.isStepComplete(
            step = step,
            locationOk = states.locationOk,
            notificationsOk = states.notificationsOk,
            batteryOk = states.batteryOk,
            vendorOk = vendorDone,
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
                text = stringResource(R.string.step_counter, idx + 1, steps.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // Progreso con puntos: ● completados, ○ pendientes (leyenda visual
            // del "1 de N" dinámico; con muchos pasos no crece en altura).
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                steps.forEachIndexed { stepIndex, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (stepIndex == idx) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    stepIndex < idx -> StatusOk
                                    stepIndex == idx -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                }
                            ),
                    )
                }
            }
            // Barra de progreso animada: avanza suave entre pasos.
            val animatedProgress by animateFloatAsState(
                targetValue = (idx + 1) / steps.size.toFloat(),
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
                        if (repairSteps != null) {
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
                    4 -> if (vendor != null) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.vendor_step_title, vendor.vendorName)
                                    .let { if (vendorDone) "✓ $it" else it },
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
                                enabled = !vendorDone || repairSteps != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(R.string.vendor_open_settings, vendor.vendorName),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (vendor.secondaryIntent != null) {
                                Button(
                                    onClick = onVendorSecondary,
                                    enabled = !vendorDone || repairSteps != null,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        stringResource(R.string.vendor_open_app_page),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
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
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (idx > 0) {
                    TextButton(
                        onClick = { index = idx - 1 },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Atrás")
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (idx < steps.lastIndex) {
                    // Siguiente sencillo: sin elevación, altura fija, sin rebotes.
                    // Apagado (sin alumbrar) hasta completar el paso actual.
                    Button(
                        onClick = { index = idx + 1 },
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
                        // (el paso GPS/vendor no forma parte de la lista). En el
                        // flujo normal, además de allOk, el gate exige continuidad
                        // PASS cuando hay guía OEM (nunca READY sin ella).
                        enabled = if (repairSteps != null) {
                            stepOk
                        } else {
                            allOk
                        },
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

    /** Estado persistido de la prueba de continuidad (AppConfig) para la UI. */
    private data class ContinuityUi(
        val state: String,
        val cause: String,
        val running: Boolean,
        val screenOffAt: Long,
    )

    /**
     * Pantalla final "Todo está listo": checklist en lenguaje del colaborador
     * (sin datos técnicos) + CONTINUAR hacia la pantalla principal.
     */
    @Composable
    private fun ReadyScreen(onFinish: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(modifier = Modifier.weight(0.6f))
            Image(
                painter = painterResource(R.drawable.logo_banner),
                contentDescription = stringResource(R.string.app_name),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .widthIn(max = 200.dp)
                    .fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.ready_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.ready_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 8.dp)
                    .widthIn(max = 280.dp),
            ) {
                ReadyCheck(stringResource(R.string.ready_item_location))
                ReadyCheck(stringResource(R.string.ready_item_permissions))
                ReadyCheck(stringResource(R.string.ready_item_tracking))
                ReadyCheck(stringResource(R.string.ready_item_connectivity))
                ReadyCheck(stringResource(R.string.ready_item_device))
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    stringResource(R.string.ready_continue),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.padding(bottom = 12.dp))
        }
    }

    @Composable
    private fun ReadyCheck(label: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleMedium,
                color = StatusOk,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }

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
     * botón final con allOk. Paso 4: guía OEM confirmada; paso 5: continuidad
     * PASS (DeviceReadinessGate). Puro para tests.
     */
    fun isStepComplete(
        step: Int,
        locationOk: Boolean,
        notificationsOk: Boolean,
        batteryOk: Boolean,
        vendorOk: Boolean = true,
    ): Boolean = when (step) {
        0 -> locationOk
        1 -> notificationsOk
        2 -> batteryOk
        4 -> vendorOk
        else -> true
    }

    /**
     * Amplía la lista dinámica con lo que EMERGE de la evaluación del
     * DeviceReadinessGate (pura, testeable): bucket/restricción de fondo →
     * paso 2 (la batería lo cubre) y OEM pendiente de mitigar → paso 4. Nunca
     * agrega índices fijos por OEM: los pendientes son los que manda el gate.
     * La prueba de continuidad vive en Diagnóstico, no en el asistente.
     */
    fun gateSteps(
        base: List<Int>,
        batteryGatePending: Boolean,
        oemGatePending: Boolean,
    ): List<Int> {
        var steps = base
        if (batteryGatePending && 2 !in steps) steps = steps + 2
        if (oemGatePending && 4 !in steps) steps = steps + 4
        // La prueba de CONTINUIDAD ya no forma parte del asistente: vive en
        // Diagnóstico (la decisión del producto la sacó del flujo).
        return steps
    }

    /**
     * Selección DINÁMICA de pasos (pura, JVM): SOLO los pasos que realmente
     * faltan, en el orden de prioridad del tracking (1. ubicación, 2. avisos,
     * 3. batería, 4. OEM, GPS primero si el interruptor del sistema está
     * apagado porque sin GPS nada de lo demás tiene efecto). Lista vacía =
     * dispositivo preparado (no se muestra onboarding). Nunca crea "1 de 6"
     * si solo hay 2 pendientes. Sobre esta base, [gateSteps] agrega lo que
     * emerge del DeviceReadinessGate (OEM sin mitigar).
     */
    fun dynamicSteps(
        locationEnabled: Boolean,
        locationOk: Boolean,
        notificationsOk: Boolean,
        batteryOk: Boolean,
        vendorPending: Boolean,
    ): List<Int> = listOf(
        3 to locationEnabled,
        0 to locationOk,
        1 to notificationsOk,
        2 to batteryOk,
        4 to !vendorPending,
    ).filter { !it.second }.map { it.first }
}
