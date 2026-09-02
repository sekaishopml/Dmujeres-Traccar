package com.dmujeres.traccar

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
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
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.ui.theme.Accent
import com.dmujeres.traccar.ui.theme.DmujeresTheme
import com.dmujeres.traccar.ui.theme.Ink

/**
 * Pantalla de carga con el logo de DMujeres Tracking. Al terminar entra al
 * onboarding o directo a la pantalla principal según si ya se configuró.
 */
class SplashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DmujeresTheme {
                SplashContent()
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            val onboardingDone = AppConfig(this).onboardingDone
            val target = if (onboardingDone) MainActivity::class.java
            else OnboardingActivity::class.java
            startActivity(Intent(this, target))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            finish()
        }, 1700)
    }
}

@Composable
private fun SplashContent() {
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
                Image(
                    painter = painterResource(R.drawable.logo_banner),
                    contentDescription = stringResource(R.string.app_name),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .fillMaxWidth(),
                )
            }
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 28.dp)
                    .size(48.dp),
                color = Accent,
                strokeWidth = 5.dp,
            )
            Text(
                text = stringResource(R.string.splash_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
