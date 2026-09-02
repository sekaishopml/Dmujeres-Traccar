package com.dmujeres.traccar

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.ui.theme.BgDark
import com.dmujeres.traccar.ui.theme.DmujeresTheme
import com.dmujeres.traccar.ui.theme.NeonPink
import com.dmujeres.traccar.ui.theme.NeonViolet
import com.dmujeres.traccar.ui.theme.TextPrimary
import com.dmujeres.traccar.ui.theme.TextSecondary

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

    val infiniteTransition = rememberInfiniteTransition(label = "splashGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "glow"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark),
        contentAlignment = Alignment.Center,
    ) {
        // Radial gradient neon 15% top
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(520.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            NeonPink.copy(alpha = 0.15f * glowAlpha),
                            NeonViolet.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = Offset(540f, 240f),
                        radius = 700f
                    )
                )
        )
        // Bottom subtle fade
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, BgDark.copy(alpha = 0.5f)),
                        startY = 600f
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(700)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // neon shadow behind logo
                    Box(
                        modifier = Modifier
                            .size(320.dp, 110.dp)
                            .blur(28.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(NeonPink.copy(alpha = 0.22f), NeonViolet.copy(alpha = 0.18f))
                                ),
                                RoundedCornerShape(24.dp)
                            )
                    )
                    Image(
                        painter = painterResource(R.drawable.logo_banner),
                        contentDescription = stringResource(R.string.app_name),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .fillMaxWidth()
                            .shadow(
                                elevation = 24.dp,
                                shape = RoundedCornerShape(20.dp),
                                ambientColor = NeonPink.copy(alpha = 0.4f),
                                spotColor = NeonViolet.copy(alpha = 0.35f)
                            )
                            .clip(RoundedCornerShape(16.dp)),
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Brand text
            Text(
                text = "DMUJERES.EC",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = TextPrimary
                ),
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Belleza Profesional",
                style = MaterialTheme.typography.bodySmall.copy(
                    letterSpacing = 1.2.sp,
                    color = TextSecondary
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )

            // Gradient sweep progress
            Box(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .size(52.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(52.dp),
                    color = NeonPink,
                    strokeWidth = 4.dp,
                    trackColor = Color(0xFF1E2030),
                )
                // inner gradient ring glow
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            Brush.sweepGradient(
                                colors = listOf(NeonPink, NeonViolet, NeonPink)
                            ),
                            shape = androidx.compose.foundation.shape.CircleShape
                        )
                        .padding(2.dp)
                )
            }
            Text(
                text = stringResource(R.string.splash_loading),
                style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
