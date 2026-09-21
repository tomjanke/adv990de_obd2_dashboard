package de.adv990de.obd2.ui.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.convertUnit
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.components.LiveValueText
import de.adv990de.obd2.ui.components.LiveValueTile
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.asLiveValue
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** Oberes Ende der Drehzahl-Skala; entspricht der maxValue-Angabe des RPM-PID. */
private const val RPM_BAR_MAX = 10_000f

/** Eine Stufe der Segmentanzeige. 200 rpm ergeben 50 Segmente ueber die volle Breite. */
private const val RPM_SEGMENT = 200f

@Composable
internal fun RpmBar(
    rpm: Float,
    threshold: Int,
    modifier: Modifier = Modifier,
    /** Ohne Verbindung zeigt der Balken nur die Demo-Bewegung: kein Alarmrot, kein Blinken
     *  und keine Zahl, die eine Drehzahl vortaeuscht, die es nicht gibt. */
    isLive: Boolean = true,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val blinkPeriodMs: Long = if (isLive && threshold > 0 && rpm > threshold) {
        val f = if (threshold >= 10000) 1f
                else ((rpm - threshold) / (10000f - threshold)).coerceIn(0f, 1f)
        (500f - 420f * f).toLong()  // 500ms at threshold → 80ms at max
    } else 0L

    // rememberUpdatedState lets the coroutine read the latest period on each iteration
    // without restarting the effect — crucial during animated RPM sweeps where blinkPeriodMs
    // changes every frame and a key-based restart would prevent the delay from ever firing.
    val blinkPeriodRef = rememberUpdatedState(blinkPeriodMs)
    val visible by produceState(initialValue = true) {
        while (true) {
            val period = blinkPeriodRef.value
            if (period > 0L) {
                delay(period)
                value = !value
            } else {
                value = true
                delay(50) // idle poll — keeps loop alive without busy-spinning
            }
        }
    }

    val isAlert = isLive && threshold > 0 && rpm > threshold
    val litColor = if (isAlert) StatusColors.Critical else MaterialTheme.colorScheme.primary
    // Segmente oberhalb des Schwellwerts sind schon vor dem Erreichen als Grenzbereich
    // erkennbar — wie die Schaltblitz-Skala eines Cockpit-Drehzahlmessers.
    val redlineColor = StatusColors.Critical
    val idleColor = MaterialTheme.colorScheme.surfaceVariant
    val redlineFrom = if (isLive && threshold > 0) threshold.toFloat() else Float.MAX_VALUE

    Row(
        modifier = modifier
            .height(72.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
            )
            // Segmentanzeige statt durchgehendem Balken: 50 Stufen à 200 rpm. Die Skala bleibt
            // auch bei 0 rpm sichtbar, sodass der Fahrer die Position der Drehzahl im Band
            // ablesen kann, ohne die Zahl zu lesen.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val count = (RPM_BAR_MAX / RPM_SEGMENT).toInt()
                val gap = 1.5.dp.toPx()
                val segmentWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
                val lit = if (visible) {
                    ceil(rpm / RPM_SEGMENT).toInt().coerceIn(0, count)
                } else 0
                for (i in 0 until count) {
                    val segmentStartRpm = i * RPM_SEGMENT
                    val color = when {
                        i < lit && segmentStartRpm >= redlineFrom -> redlineColor
                        i < lit                                   -> litColor
                        else                                      -> idleColor
                    }
                    drawRect(
                        color = color,
                        topLeft = Offset(i * (segmentWidth + gap), 0f),
                        size = Size(segmentWidth, size.height),
                    )
                }
            }
        }
        LiveValueText(
            value = if (isLive) "%.0f rpm".format(rpm) else "-- rpm",
            style = MaterialTheme.typography.titleSmall.asLiveValue(),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = 8.dp)
                .width(90.dp)
        )
        trailingContent?.invoke()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SensorCardSlot(
    reading: SensorValue?,
    sparklines: Map<String, List<Float>>,
    useImperial: Boolean,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null
) {
    if (reading != null) {
        SensorCard(reading, sparklines[reading.pid.id] ?: emptyList(), useImperial, onLongPress, modifier.fillMaxSize())
    } else {
        Surface(
            modifier = modifier
                .fillMaxSize()
                .combinedClickable(onClick = {}, onLongClick = { onLongPress?.invoke() }),
            shape = RectangleShape,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            // Ein unbelegter Slot war bisher eine leere Flaeche und sah nach Renderfehler aus.
            // Der Hinweis nennt zugleich die einzige Geste, die ihn belegt.
            Box(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.dashboard_slot_empty),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Adapter from the domain type [SensorValue] to the generic [LiveValueTile]:
 * handles unit conversion (imperial flag), NaN/Inf → "--", voltage gets one
 * decimal place, everything else integer. The accessibility contentDescription
 * stays here because it depends on the PID semantics.
 */
@Composable
internal fun SensorCard(
    value: SensorValue,
    sparkline: List<Float> = emptyList(),
    useImperial: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val (convertedValue, displayUnit) = if (value.value.isNaN() || value.value.isInfinite()) {
        value.value to value.pid.unit
    } else {
        convertUnit(value.value, value.pid.unit, useImperial)
    }
    val displayValue = if (value.value.isNaN() || value.value.isInfinite()) "--"
                       else if (value.pid.unit == "V") "%.1f".format(convertedValue)
                       else "%.0f".format(convertedValue)
    val pidLabel = stringResource(value.pid.labelRes)
    LiveValueTile(
        label = pidLabel,
        value = displayValue,
        unit = displayUnit,
        sparkline = sparkline,
        status = pidTileStatus(value.pid.id, value.value),
        onLongPress = onLongPress,
        modifier = modifier.semantics { contentDescription = "$pidLabel: $displayValue $displayUnit" },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SlotPickerSheet(
    currentPidId: String,
    availablePids: List<PidDefinition>,
    readings: List<SensorValue>,
    useImperial: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        LazyColumn {
            items(availablePids, key = { it.id }) { pid ->
                val reading = readings.firstOrNull { it.pid.id == pid.id }
                val label = if (reading == null || reading.value.isNaN() || reading.value.isInfinite()) {
                    "${stringResource(pid.labelRes)}: -- (${pid.unit})"
                } else {
                    val (convertedValue, displayUnit) = convertUnit(reading.value, pid.unit, useImperial)
                    val formatted = if (pid.unit == "V") "%.1f".format(convertedValue)
                                    else "%.0f".format(convertedValue)
                    "${stringResource(pid.labelRes)}: $formatted ($displayUnit)"
                }
                ListItem(
                    modifier = Modifier.clickable { onSelect(pid.id) },
                    headlineContent = { Text(label) },
                    trailingContent = if (pid.id == currentPidId) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                )
                HorizontalDivider()
            }
        }
    }
}
