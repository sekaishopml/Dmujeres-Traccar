package com.dmujeres.traccar.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dmujeres.traccar.R
import com.dmujeres.traccar.ui.theme.JourneyColors
import com.dmujeres.traccar.ui.theme.journeyBannerColors

/**
 * Banner de estado de la jornada con sistema de 3 colores estricto
 * (ver JourneyColors):
 * - Fuera de jornada: SOLO rojo (fondo rojo + texto blanco), sin excepción.
 * - En jornada con tracking OK: verde fondo + blanco texto.
 * - En jornada degradada: blanco fondo + texto verde (transitorio) o rojo (error).
 */
@Composable
fun StatusBanner(
    isStarted: Boolean,
    trackingOk: Boolean,
    hasError: Boolean,
    stateText: String,
    isCompactHeight: Boolean,
    // Causa de red (Fase 1): mensaje humano ya resuelto por el llamador.
    // Mismo sistema de 3 colores (usa colors.content); sin scroll (maxLines + ellipsis).
    netCauseMessage: String? = null,
    showWifiAction: Boolean = false,
    onOpenWifiSettings: () -> Unit = {},
) {
    val colors = journeyBannerColors(isStarted, trackingOk, hasError)
    val title = if (isStarted) stringResource(R.string.journey_started_title) else stringResource(R.string.journey_finished_title)

    val animatedBackground by animateColorAsState(
        targetValue = colors.background,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "bannerBackground",
    )
    val animatedContent by animateColorAsState(
        targetValue = colors.content,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "bannerContent",
    )
    val animatedBadge by animateColorAsState(
        targetValue = colors.badge,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "bannerBadge",
    )
    val animatedOnBadge by animateColorAsState(
        targetValue = colors.onBadge,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "bannerOnBadge",
    )
    val animatedBorder by animateColorAsState(
        targetValue = if (colors.background == JourneyColors.Blanco) {
            colors.content.copy(alpha = 0.35f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "bannerBorder",
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = animatedBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, animatedBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = if (isCompactHeight) 10.dp else 14.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(if (isCompactHeight) 40.dp else 48.dp)
                    .background(animatedBadge, CircleShape),
            ) {
                Icon(
                    painter = painterResource(if (isStarted) R.drawable.ic_play else R.drawable.ic_stop),
                    contentDescription = title,
                    tint = animatedOnBadge,
                    modifier = Modifier.size(if (isCompactHeight) 20.dp else 24.dp),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = if (isCompactHeight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = animatedContent,
                )
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = animatedContent.copy(alpha = 0.8f),
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (!netCauseMessage.isNullOrBlank()) {
                    Text(
                        text = if (showWifiAction) "$netCauseMessage · Abrir WiFi" else netCauseMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = animatedContent.copy(alpha = 0.9f),
                        textDecoration = if (showWifiAction) TextDecoration.Underline else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (showWifiAction) {
                            Modifier
                                .padding(top = 2.dp)
                                .heightIn(min = 48.dp)
                                .clickable(onClick = onOpenWifiSettings)
                        } else {
                            Modifier.padding(top = 2.dp)
                        },
                    )
                }
            }
        }
    }
}
