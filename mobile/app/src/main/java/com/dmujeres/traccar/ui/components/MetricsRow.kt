package com.dmujeres.traccar.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dmujeres.traccar.R
import com.dmujeres.traccar.ui.theme.Ink
import com.dmujeres.traccar.ui.theme.JourneyColors
import com.dmujeres.traccar.util.JourneyFormatter

/**
 * Fila de 3 métricas (batería / pendientes / servidor) extraída de MainActivity.
 *
 * Nota: deriva pendientes y estado del servidor desde [logText] igual que antes
 * para no cambiar comportamiento. Si se quiere desacoplar, pasar valores directos.
 *
 * - Batería: porcentaje grande ARRIBA + etiqueta pequeña ABAJO (invertida).
 *   Se ignora [batteryText] heredado (contenía log_battery duplicado) y se usa
 *   [batteryLevel]. Fuera de línea (ni conectado ni conectando) NO se muestra.
 * - Servidor: etiqueta "Duración" arriba (Ink), tiempo grande abajo
 *   ("2 h 15 min" / "Sin jornada" / "Conectando…"); punto pulsante verde/rojo
 *   como único indicador de conexión (contentDescription accesible).
 */
@Composable
fun MetricsRow(
    batteryText: String,
    batteryLevel: Int,
    batteryColor: Color,
    logText: String,
    isCompactHeight: Boolean,
    journeyStartAt: Long = 0L,
    journeyActive: Boolean = false,
    // true solo si los pendientes son ANORMALES (ver PendingAlertPolicy). Con el dispatch
    // secuencial (1 en vuelo, ackTimeout 15 s) casi siempre hay 1-5 sanos: esos quedan en
    // verde y solo el backlog anormal pinta la tarjeta de rojo.
    pendingAlert: Boolean = false,
) {
    // Extraer pending del logText (vale el texto grave "%d ubicaciones pendientes" y el
    // leve "Sincronizando %d…" de log_pending_sync).
    val pendingMatch = Regex("""(?:(\d+) ubicaciones pendientes|Sincronizando (\d+))""").find(logText)
    val pending = pendingMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 0

    // Extraer estado del servidor del logText (las líneas log_server_* siguen en el log).
    val serverConnected = "Conectado al servidor" in logText
    val serverConnecting = !serverConnected && "Conectando al servidor" in logText
    // Fuera de línea: ni conectado ni conectando -> no se muestra la batería.
    val online = serverConnected || serverConnecting

    // Recuadros alineados y del mismo tamaño: la fila mide lo del más alto
    // y cada tarjeta rellena esa altura con el contenido centrado.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(if (isCompactHeight) 6.dp else 8.dp),
    ) {
        // Batería: ARRIBA porcentaje grande, ABAJO etiqueta pequeña (invertida).
        // Solo visible en línea; fuera de línea se oculta (el resto ocupa el ancho).
        if (online) {
            BatteryCard(
                batteryLevel = batteryLevel,
                accentColor = batteryColor,
                modifier = Modifier.weight(1f),
                isCompactHeight = isCompactHeight,
            )
        }
        // Pendientes
        MetricCard(
            label = if (pending > 0) "$pending" else "0",
            sublabel = stringResource(R.string.pending),
            // 3 colores estricto: solo el backlog anormal es rojo; 1-5 sanos = verde (sin ámbar).
            accentColor = if (pending > 0 && pendingAlert) JourneyColors.Rojo else JourneyColors.Verde,
            modifier = Modifier.weight(1f),
            isCompactHeight = isCompactHeight,
        )
        // Servidor: punto pulsante + duración de jornada, texto compacto.
        ServerCard(
            connected = serverConnected,
            connecting = serverConnecting,
            journeyStartAt = journeyStartAt,
            journeyActive = journeyActive,
            modifier = Modifier.weight(1f),
            isCompactHeight = isCompactHeight,
        )
    }
}

@Composable
private fun BatteryCard(
    batteryLevel: Int,
    accentColor: Color,
    modifier: Modifier = Modifier,
    isCompactHeight: Boolean,
) {
    val batteryDescription = if (batteryLevel in 0..100) {
        "$batteryLevel% " + stringResource(R.string.battery_label)
    } else {
        stringResource(R.string.battery_label)
    }
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier.fillMaxHeight(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isCompactHeight) 8.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Bloque superior fijo (16dp) para alinear los puntos con las
            // otras dos tarjetas.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(accentColor, CircleShape)
                        .semantics { contentDescription = batteryDescription },
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            // Invertida: ARRIBA el porcentaje grande, ABAJO la etiqueta pequeña.
            Text(
                text = if (batteryLevel in 0..100) "$batteryLevel%" else "—",
                style = if (isCompactHeight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.battery_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ServerCard(
    connected: Boolean,
    connecting: Boolean,
    journeyStartAt: Long,
    journeyActive: Boolean,
    modifier: Modifier = Modifier,
    isCompactHeight: Boolean,
) {
    // 3 colores estricto: servidor OK = verde, si no = rojo DMujeres (sin ámbar,
    // ni siquiera para "conectando").
    val accentColor = if (connected) JourneyColors.Verde else JourneyColors.Rojo
    // Texto grande inferior: "Conectando…" mientras conecta; con jornada activa
    // la duración ("2 h 15 min", vía JourneyFormatter.durationParts); si no,
    // "Sin jornada".
    val hasJourney = journeyActive && journeyStartAt > 0L
    val durationText = when {
        connecting -> stringResource(R.string.server_connecting_label)
        hasJourney -> {
            val (hours, minutes) = JourneyFormatter.durationParts(System.currentTimeMillis() - journeyStartAt)
            stringResource(R.string.journey_duration, hours, minutes)
        }
        else -> stringResource(R.string.server_no_journey)
    }
    val connectionDescription = when {
        connected -> stringResource(R.string.server_connected)
        connecting -> stringResource(R.string.server_connecting)
        else -> stringResource(R.string.server_disconnected)
    }
    val pulse = rememberInfiniteTransition(label = "serverPulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "serverPulseAlpha",
    )
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier.fillMaxHeight(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isCompactHeight) 8.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // ARRIBA: punto; DEBAJO: etiqueta "Duración" en negro (Ink);
            // ABAJO: tiempo grande. Igual que las otras dos tarjetas.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp),
            ) {
                // El punto pulsante verde/rojo se conserva como único indicador de
                // conexión (accesible por contentDescription).
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer { this.alpha = alpha }
                        .background(accentColor, CircleShape)
                        .semantics { contentDescription = connectionDescription },
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.server_duration_label),
                style = MaterialTheme.typography.labelSmall,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            // ABAJO: tiempo grande de la jornada.
            Text(
                text = durationText,
                style = if (isCompactHeight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Ink,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    sublabel: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    isCompactHeight: Boolean,
) {
    val dotDescription = "$label $sublabel".trim()
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier.fillMaxHeight(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isCompactHeight) 8.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Bloque superior fijo (16dp) para alinear los puntos con las
            // otras dos tarjetas.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(accentColor, CircleShape)
                        .semantics { contentDescription = dotDescription },
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = if (isCompactHeight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (sublabel.isNotEmpty()) {
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
