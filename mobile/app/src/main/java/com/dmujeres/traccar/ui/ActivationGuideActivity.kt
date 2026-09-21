package com.dmujeres.traccar.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dmujeres.traccar.BuildConfig
import com.dmujeres.traccar.R
import com.dmujeres.traccar.oem.SetupChecklistPolicy
import com.dmujeres.traccar.oem.VendorSettings
import com.dmujeres.traccar.readiness.DeviceFactsCollector
import com.dmujeres.traccar.ui.theme.DmujeresTheme
import com.dmujeres.traccar.ui.theme.Ink
import com.dmujeres.traccar.ui.theme.StatusOk

/**
 * R7: pantalla de guía de activación en segundo plano y batería.
 *
 * Muestra el estado REAL verificable (API) del equipo y los pasos del
 * fabricante (no verificables: solo se abren las pantallas). Nunca declara
 * un ajuste OEM como aplicado.
 */
class ActivationGuideActivity : ComponentActivity() {

    private var refreshKey by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DmujeresTheme {
                ActivationGuideContent(
                    refreshKey = refreshKey,
                    onBack = {
                        finish()
                        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right)
                    },
                    onOpenVendor = { openVendorChain() },
                    onBattery = { openBatteryExemption() },
                    onBackgroundLocation = { openAppDetails() },
                    onInstallSources = { openInstallSources() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKey++
    }

    private fun openVendorChain() {
        val vendor = VendorSettings.currentVendor()
        val chain = VendorSettings.intentChainFor(vendor)
        var opened = false
        for (spec in chain) {
            opened = runCatching {
                startActivity(VendorSettings.intentFor(spec, packageName))
                true
            }.getOrDefault(false)
            if (opened) break
        }
        if (!opened) openAppDetails()
    }

    private fun openBatteryExemption() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        }
    }

    private fun openAppDetails() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                )
            )
        }
    }

    private fun openInstallSources() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                )
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ActivationGuideContent(
        refreshKey: Int,
        onBack: () -> Unit,
        onOpenVendor: () -> Unit,
        onBattery: () -> Unit,
        onBackgroundLocation: () -> Unit,
        onInstallSources: () -> Unit,
    ) {
        val facts = androidx.compose.runtime.remember(refreshKey) {
            DeviceFactsCollector.collect(this@ActivationGuideActivity)
        }
        val steps = androidx.compose.runtime.remember(facts) {
            SetupChecklistPolicy.stepsFor(facts.vendor, facts.sdkInt)
        }
        val guide = androidx.compose.runtime.remember(facts) {
            VendorSettings.guideFor(facts.vendor)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.activation_guide_title),
                        color = Ink,
                        fontWeight = FontWeight.Medium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = Ink,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionTitle(stringResource(R.string.activation_guide_device))
                Text(
                    text = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · app ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                SectionTitle(stringResource(R.string.activation_guide_status_title))
                for (step in steps) {
                    StatusRow(
                        label = stepLabel(step),
                        state = SetupChecklistPolicy.stateOf(step, facts),
                    )
                }

                SectionTitle(stringResource(R.string.activation_guide_steps_title))
                if (guide != null) {
                    Text(
                        text = guide.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    for (line in guide.steps) {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.activation_guide_background),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }

                Button(
                    onClick = onOpenVendor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Text(stringResource(R.string.activation_guide_open_vendor))
                }
                OutlinedButton(
                    onClick = onBattery,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Text(stringResource(R.string.activation_guide_battery))
                }
                OutlinedButton(
                    onClick = onBackgroundLocation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Text(stringResource(R.string.activation_guide_background))
                }
                if (!facts.canInstallUpdates) {
                    OutlinedButton(
                        onClick = onInstallSources,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                    ) {
                        Text(stringResource(R.string.activation_guide_install))
                    }
                }
                OutlinedButton(
                    onClick = onBackgroundLocation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                ) {
                    Text(stringResource(R.string.activation_guide_appinfo))
                }
            }
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    @Composable
    private fun StatusRow(label: String, state: SetupChecklistPolicy.StepState) {
        val (symbol, color) = when (state) {
            SetupChecklistPolicy.StepState.VERIFIED -> "✓" to StatusOk
            SetupChecklistPolicy.StepState.ACTION_REQUIRED -> "⚠" to MaterialTheme.colorScheme.primary
            SetupChecklistPolicy.StepState.GUIDED -> "•" to MaterialTheme.colorScheme.onBackground
            SetupChecklistPolicy.StepState.NOT_APPLICABLE -> "–" to MaterialTheme.colorScheme.onBackground
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.titleMedium,
                color = color,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }

    private fun stepLabel(step: SetupChecklistPolicy.StepId): String = when (step) {
        SetupChecklistPolicy.StepId.LOCATION -> getString(R.string.activation_step_location)
        SetupChecklistPolicy.StepId.BACKGROUND_LOCATION -> getString(R.string.activation_step_background)
        SetupChecklistPolicy.StepId.NOTIFICATIONS -> getString(R.string.activation_step_notifications)
        SetupChecklistPolicy.StepId.BATTERY_EXEMPTION -> getString(R.string.activation_step_battery)
        SetupChecklistPolicy.StepId.EXACT_ALARMS -> getString(R.string.activation_step_exact_alarms)
        SetupChecklistPolicy.StepId.OEM_SCREENS -> getString(R.string.activation_step_oem_screens)
        SetupChecklistPolicy.StepId.INSTALL_UPDATES -> getString(R.string.activation_step_install)
        SetupChecklistPolicy.StepId.FREEZE_WATCH -> getString(R.string.activation_step_freeze)
    }
}
