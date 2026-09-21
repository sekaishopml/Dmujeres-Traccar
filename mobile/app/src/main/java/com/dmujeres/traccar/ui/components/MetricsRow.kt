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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmujeres.traccar.R
import com.dmujeres.traccar.ui.theme.Ink
import com.dmujeres.traccar.ui.theme.JourneyColors
import com.dmujeres.traccar.core.JourneyFormatter
import kotlinx.coroutines.delay

// Suelos y techos de la fuente adaptativa: los valores grandes nunca bajan de 11 sp
// (en 320 dp "12 h 34 min" a 16 sp pide ~88 dp y el hueco es de ~80 dp -> ~14.5 sp) y
// las etiquetas no de 9 sp; en tarjetas anchas se permite crecer hasta 1.15x.
private val MIN_SIZE_VALUE = 11.sp
private val MIN_SIZE_LABEL = 9.sp
private const val MAX_FONT_SCALE = 1.15f

// Margen para que el redondeo del pintado no vuelva a recortar el texto medido.
private val SAFETY_MARGIN_DP = 2.dp

// La fila replica el límite de ancho de la tarjeta principal (480 dp, centrada):
// en tablets/landscape las 3 tarjetas no se estiran a líneas absurdas.
private val MAX_ROW_WIDTH = 480.dp

// Alturas coherentes entre los 3 recuadros (los puntos alinean entre tarjetas).
private val DOT_AREA_HEIGHT = 16.dp
private val CARD_MIN_HEIGHT_COMPACT = 72.dp
private val CARD_MIN_HEIGHT = 88.dp

// Etiquetas que caben en una línea a tamaño base (p.ej. "Batería", "Duración");
// las más largas ("Pendientes por enviar") pueden partir en 2 y elipsar.
private const val LABEL_MAX_LINES_SINGLE = 1
private const val LABEL_MAX_LINES_DOUBLE = 2
private val LABEL_SINGLE_LINE_MAX_CHARS = 12

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
    // Elapsed monotónico persistido por el servicio + su ancla wall: la duración
    // se calcula desde aquí (no desde journeyStartAt) para ser inmune a saltos NTP.
    journeyElapsedMs: Long = 0L,
    journeyElapsedWallMs: Long = 0L,
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

    // Recuadros con peso igual (los 3 ocupan el mismo ancho), altura común (la del
    // más alto) y ancho total acotado como la tarjeta principal, centrado.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = MAX_ROW_WIDTH)
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
            journeyElapsedMs = journeyElapsedMs,
            journeyElapsedWallMs = journeyElapsedWallMs,
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
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = if (isCompactHeight) CARD_MIN_HEIGHT_COMPACT else CARD_MIN_HEIGHT),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isCompactHeight) 8.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StateDot(
                accentColor = accentColor,
                dotDescription = batteryDescription,
            )
            Spacer(modifier = Modifier.height(6.dp))
            // Invertida: ARRIBA el porcentaje grande, ABAJO la etiqueta pequeña.
            FitText(
                text = if (batteryLevel in 0..100) "$batteryLevel%" else "—",
                baseStyle = if (isCompactHeight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                minSize = MIN_SIZE_VALUE,
                fontWeight = FontWeight.Bold,
            )
            FittedLabel(text = stringResource(R.string.battery_label))
        }
    }
}

@Composable
private fun ServerCard(
    connected: Boolean,
    connecting: Boolean,
    journeyStartAt: Long,
    journeyActive: Boolean,
    journeyElapsedMs: Long = 0L,
    journeyElapsedWallMs: Long = 0L,
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
    // La duración no es estado: si nadie recompone la tarjeta, System.currentTimeMillis()
    // se evalúa una sola vez y se queda en "0 h 0 min" toda la jornada. Reloj local que
    // se auto-invalida 1x/segundo solo mientras hay jornada activa.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(hasJourney) {
        // Refresco inmediato al entrar/salir de jornada (arranca o se congela el valor).
        nowMs = System.currentTimeMillis()
        while (hasJourney) {
            delay(1000L)
            nowMs = System.currentTimeMillis()
        }
    }
    val durationText = when {
        connecting -> stringResource(R.string.server_connecting_label)
        hasJourney -> {
            // Elapsed del servicio + gap desde SU ancla: el ticker de 1 s mueve
            // solo el gap; un paso NTP ya no colapsa ni infla las horas.
            val (hours, minutes) = JourneyFormatter.durationParts(
                JourneyFormatter.displayElapsedMs(journeyElapsedMs, journeyElapsedWallMs, journeyStartAt, nowMs),
            )
            // Formato compacto de tarjeta: "2:26" cabe a cualquier ancho/escala
            // de fuente; la versión larga vive en la notificación persistente.
            stringResource(R.string.journey_duration_compact, hours, minutes)
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
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = if (isCompactHeight) CARD_MIN_HEIGHT_COMPACT else CARD_MIN_HEIGHT),
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
                    .height(DOT_AREA_HEIGHT),
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
            FittedLabel(
                text = stringResource(R.string.server_duration_label),
                color = Ink,
            )
            Spacer(modifier = Modifier.height(2.dp))
            // ABAJO: tiempo grande de la jornada.
            FitText(
                text = durationText,
                baseStyle = if (isCompactHeight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = Ink,
                minSize = MIN_SIZE_VALUE,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
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
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = if (isCompactHeight) CARD_MIN_HEIGHT_COMPACT else CARD_MIN_HEIGHT),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isCompactHeight) 8.dp else 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StateDot(
                accentColor = accentColor,
                dotDescription = dotDescription,
            )
            Spacer(modifier = Modifier.height(6.dp))
            FitText(
                text = label,
                baseStyle = if (isCompactHeight) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                minSize = MIN_SIZE_VALUE,
                fontWeight = FontWeight.Bold,
            )
            if (sublabel.isNotEmpty()) {
                FittedLabel(
                    text = sublabel,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StateDot(accentColor: Color, dotDescription: String) {
    // Bloque superior fijo (16dp) para alinear los puntos con las otras tarjetas.
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(DOT_AREA_HEIGHT),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(accentColor, CircleShape)
                .semantics { contentDescription = dotDescription },
        )
    }
}

/**
 * Etiqueta pequeña de las tarjetas: "Pendientes por enviar" no cabe en una línea
 * en 320-360 dp, así que puede partir en 2 líneas y elipsar con elegancia; las
 * cortas ("Batería", "Duración") se quedan en 1. La fuente se encoge con el mismo
 * sistema de FitText antes de partir líneas.
 */
@Composable
private fun FittedLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
) {
    val maxLines = if (text.length <= LABEL_SINGLE_LINE_MAX_CHARS) {
        LABEL_MAX_LINES_SINGLE
    } else {
        LABEL_MAX_LINES_DOUBLE
    }
    FitText(
        text = text,
        baseStyle = MaterialTheme.typography.labelSmall,
        color = color,
        minSize = MIN_SIZE_LABEL,
        modifier = modifier,
        maxLines = maxLines,
    )
}

/**
 * Texto con fuente adaptativa: mide [text] con [baseStyle] y escala el tamaño de
 * fuente para que quepa en el ancho real de la tarjeta. Con [maxLines] > 1 el texto
 * puede partir en varias líneas y elipsar en lugar de recortarse: se mide con
 * [Constraints] reales y se elige la fuente más grande que quepa.
 *
 * No usa BoxWithConstraints: la fila mide alturas intrínsecas (IntrinsicSize.Max) y una
 * subcomposición ahí reportaría altura 0; onSizeChanged da el mismo ancho sin ese riesgo.
 *
 * @param baseStyle tipografía base (titleMedium/titleSmall/labelSmall): de ella se toma
 *   el tamaño de partida, que en compacta ya es más pequeño (comportamiento de siempre).
 * @param minSize suelo duro en sp (11 sp los valores grandes, 9 sp las etiquetas).
 * @param maxScale techo de crecimiento para tarjetas anchas (16 sp -> ~18 sp).
 */
@Composable
private fun FitText(
    text: String,
    baseStyle: TextStyle,
    color: Color,
    minSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign = TextAlign.Center,
    maxScale: Float = MAX_FONT_SCALE,
    maxLines: Int = 1,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Ancho real de la tarjeta: llega tras el primer layout (sin medir = ancho ilimitado).
    var availableWidthPx by remember { mutableStateOf(-1) }
    val style = baseStyle.let { if (fontWeight == null) it else it.copy(fontWeight = fontWeight) }
    val fittedSp = remember(text, availableWidthPx, style, minSize, maxScale, density, maxLines) {
        if (availableWidthPx <= 0) {
            style.fontSize
        } else {
            val basePx = with(density) { style.fontSize.toPx() }
            val minPx = with(density) { minSize.toPx() }
            // 2 dp de margen de seguridad: el redondeo del pintado no vuelva a recortar.
            val usablePx = availableWidthPx - with(density) { SAFETY_MARGIN_DP.toPx() }.toInt()
            val scale = fitFontScaleMultiLine(
                basePx = basePx,
                minPx = minPx,
                maxScale = maxScale,
                fits = { candidatePx ->
                    measurer.measure(
                        text = text,
                        style = style.copy(fontSize = with(density) { candidatePx.toSp() }),
                        constraints = Constraints(maxWidth = usablePx.coerceAtLeast(0)),
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis,
                    ).didOverflowWidth.not()
                },
            )
            with(density) { (basePx * scale).toSp() }
        }
    }
    Text(
        text = text,
        style = style.copy(fontSize = fittedSp),
        color = color,
        textAlign = textAlign,
        softWrap = true,
        maxLines = maxLines,
        // Con el suelo de tamaño y las 2 líneas de las etiquetas largas ya casi
        // siempre cabe; si aun así no entrara se elipsa con elegancia.
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { availableWidthPx = it.width },
    )
}

/**
 * Lógica pura del escalado (testeable sin Android): crece hasta [maxScale] si sobra
 * espacio y, si no cabe, baja en pasos discretos (1.0 -> 0.9 -> 0.8 -> …) hasta que
 * [fits] se cumple o se llega al suelo [minPx]. Sin datos de medición se queda en 1.
 */
internal fun fitFontScaleMultiLine(
    basePx: Float,
    minPx: Float,
    maxScale: Float,
    fits: (Float) -> Boolean,
): Float {
    if (basePx <= 0f || minPx <= 0f) return 1f
    val maxScaleSafe = maxScale.coerceAtLeast(1f)
    if (fits(maxScaleSafe * basePx)) return maxScaleSafe
    var scale = 1.0f
    while (scale * basePx > minPx) {
        if (fits(scale * basePx)) return scale
        scale -= 0.1f
    }
    return (minPx / basePx).coerceAtLeast(0.1f)
}

/**
 * Lógica pura del escalado a 1 línea (mantenida por sus tests): escala (1f = tamaño
 * base) para que un texto que al tamaño base ocupa [textWidthAtBasePx] quepa en
 * [availableWidthPx], recortada entre [minScale] y [maxScale]. Si faltan datos
 * (anchos <= 0) no toca nada.
 */
internal fun fitFontScale(
    textWidthAtBasePx: Float,
    availableWidthPx: Float,
    minScale: Float,
    maxScale: Float,
): Float {
    if (textWidthAtBasePx <= 0f || availableWidthPx <= 0f) return 1f
    return (availableWidthPx / textWidthAtBasePx).coerceIn(minScale, maxScale)
}
