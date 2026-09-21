package com.dmujeres.traccar.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dmujeres.traccar.R
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.ui.theme.Accent
import com.dmujeres.traccar.ui.theme.DmujeresTheme
import com.dmujeres.traccar.ui.theme.Ink

/**
 * Pantalla de inicio corporativa: logo + CTA real. Con el teléfono ya
 * preparado muestra "Todo está listo" + CONTINUAR (a la pantalla principal);
 * si falta configuración, "COMENZAR" abre el asistente con solo los pasos
 * pendientes. Sin espera artificial: el arranque es inmediato.
 */
class SplashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val onboardingDone = AppConfig(this).onboardingDone
        setContent {
            DmujeresTheme {
                SplashContent(
                    ready = onboardingDone,
                    onContinue = {
                        val target = if (onboardingDone) MainActivity::class.java
                        else OnboardingActivity::class.java
                        startActivity(Intent(this, target))
                        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun SplashContent(
    ready: Boolean,
    onContinue: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(R.drawable.logo_banner),
                        contentDescription = stringResource(R.string.app_name),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = stringResource(
                            if (ready) R.string.splash_subtitle_ready
                            else R.string.splash_subtitle_setup
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onContinue,
                colors = if (ready) {
                    ButtonDefaults.buttonColors()
                } else {
                    ButtonDefaults.buttonColors(containerColor = Accent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 320.dp)
                    .height(52.dp),
            ) {
                Text(
                    text = stringResource(
                        if (ready) R.string.splash_cta_ready else R.string.splash_cta_setup
                    ),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
