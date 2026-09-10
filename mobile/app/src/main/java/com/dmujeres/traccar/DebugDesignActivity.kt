package com.dmujeres.traccar

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dmujeres.traccar.ui.theme.DmujeresTheme
import com.dmujeres.traccar.ui.theme.Ink

/**
 * TEMPORAL — Modo debug de diseño.
 *
 * Menú oculto (QA interno, disponible en todos los builds) para previsualizar como recién instalada:
 * bienvenida, permisos, login y dash principal, sin tocar datos reales
 * (prefs, jornada, MQTT, BD). Todo via Intents con extras EXTRA_DEBUG_*.
 *
 * Para revertir a producción: borrar este fichero + su entrada en el
 * manifest + el gancho de 5 taps en MainActivity + los extras preview.
 */
class DebugDesignActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DmujeresTheme {
                DebugDesignContent(
                    onBack = { finish() },
                    onSplash = { startActivity(Intent(this, SplashActivity::class.java)) },
                    onOnboarding = { step ->
                        startActivity(
                            Intent(this, OnboardingActivity::class.java)
                                .putExtra(OnboardingActivity.EXTRA_DEBUG_STEP, step)
                                .putExtra(OnboardingActivity.EXTRA_DEBUG_PREVIEW, true),
                        )
                    },
                    onLogin = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_DEBUG_LOGGED, false)
                                .putExtra(MainActivity.EXTRA_DEBUG_DASH, ""),
                        )
                    },
                    onDash = { mode ->
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_DEBUG_LOGGED, true)
                                .putExtra(MainActivity.EXTRA_DEBUG_DASH, mode),
                        )
                    },
                    onClear = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_DEBUG_CLEAR, true),
                        )
                        finish()
                    },
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DebugDesignContent(
        onBack: () -> Unit,
        onSplash: () -> Unit,
        onOnboarding: (Int) -> Unit,
        onLogin: () -> Unit,
        onDash: (String) -> Unit,
        onClear: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.debug_design_title),
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
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DebugButton(stringResource(R.string.debug_splash), onSplash)
                DebugButton(stringResource(R.string.debug_onboarding_1)) { onOnboarding(0) }
                DebugButton(stringResource(R.string.debug_onboarding_2)) { onOnboarding(1) }
                DebugButton(stringResource(R.string.debug_onboarding_3)) { onOnboarding(2) }
                DebugButton(stringResource(R.string.debug_onboarding_4)) { onOnboarding(3) }
                DebugButton(stringResource(R.string.debug_login), onLogin)
                DebugButton(stringResource(R.string.debug_dash_idle)) { onDash("idle") }
                DebugButton(stringResource(R.string.debug_dash_active)) { onDash("active") }
                DebugButton(stringResource(R.string.debug_dash_error)) { onDash("error") }
                OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.debug_clear))
                }
            }
        }
    }

    @Composable
    private fun DebugButton(text: String, onClick: () -> Unit) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(text)
        }
    }
}
