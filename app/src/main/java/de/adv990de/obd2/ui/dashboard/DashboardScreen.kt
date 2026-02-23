package de.adv990de.obd2.ui.dashboard

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.settings.DisplaySettingsViewModel

fun convertUnit(value: Float, unit: String, imperial: Boolean): Pair<Float, String> =
    when {
        !imperial      -> value to unit
        unit == "km/h" -> value * 0.621371f to "mph"
        unit == "°C"   -> value * 9f / 5f + 32f to "°F"
        else           -> value to unit
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    navController: NavController,
    vm: DashboardViewModel = hiltViewModel(),
    displayVm: DisplaySettingsViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    val placeholderReadings by vm.placeholderReadings.collectAsState()
    val useImperial by displayVm.useImperial.collectAsState()
    val rpmBlinkThreshold by vm.rpmBlinkThreshold.collectAsState()
    val rpmDynamicThreshold by vm.rpmDynamicThreshold.collectAsState()
    val slotPids by vm.slotPids.collectAsState()
    val availablePids by vm.availablePids.collectAsState()
    var pickerSlot: Int? by remember { mutableStateOf(null) }
    // Show real readings when connected; fall back to placeholder (value=0) tiles when not.
    val displayReadings = if (state.readings.isNotEmpty()) state.readings else placeholderReadings

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        // ── RPM demo animation ────────────────────────────────────────────
        // Runs once per navigation to this screen when rpmValue is 0 (engine off / no data).
        // Sweeps 0 → 10 000 → 0 to demonstrate bar fill and threshold blinking.
        val rpmDemo = remember { Animatable(0f) }
        val rpmValueRef = rememberUpdatedState(state.rpmValue)
        LaunchedEffect(Unit) {
            delay(400) // allow first ECU readings to arrive before deciding to demo
            if (rpmValueRef.value < 1f) {
                // EaseOut: bar rises quickly at first, then slows near max — spends more
                // time above the blink threshold, making the gradual speed-up clearly visible.
                rpmDemo.animateTo(10000f, tween(durationMillis = 4000, easing = EaseOut))
                delay(1500) // hold at max so rapid blinking is clearly visible
                rpmDemo.animateTo(0f, tween(durationMillis = 1200, easing = EaseIn))
            }
        }
        // During demo (real rpm still 0): show animated value. Once engine delivers real
        // RPM, switch immediately to the live value regardless of demo state.
        val displayRpm = if (state.rpmValue < 1f) rpmDemo.value else state.rpmValue
        // Demo fallback: if no threshold is configured (=0), show blinking above 7000 during
        // the demo so the feature is visible even before the user has set a threshold.
        // Dynamic scaling: when enabled, reduce threshold proportionally to engine temperature.
        // Note: not wrapped in remember — rpmDemo.value changes every animation frame and must
        // stay reactive; the scaling arithmetic is cheap.
        val displayThreshold = when {
            rpmBlinkThreshold == 0 -> if (rpmDemo.value > 0f) 7000 else 0
            !rpmDynamicThreshold   -> rpmBlinkThreshold
            else -> {
                val scale = when (val temp = state.engineTempC) {
                    null                              -> 0.50f   // no plausible value → conservative fallback
                    in Float.NEGATIVE_INFINITY..49.9f -> 0.50f
                    in 50f..69.9f                     -> 0.75f
                    in 70f..84.9f                     -> 0.90f
                    else                              -> 1.00f
                }
                (rpmBlinkThreshold * scale).toInt()
            }
        }

        // While connected but waiting for first readings: brief full-screen spinner.
        if (state.isConnected && state.readings.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            // Slot order is driven by slotPids; always exactly 4 entries.
            val slots: List<SensorValue?> = remember(displayReadings, slotPids) {
                List(4) { i ->
                    val pidId = slotPids.getOrElse(i) { "" }
                    displayReadings.firstOrNull { it.pid.id == pidId }
                }
            }

            Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
                // ── RPM bar ───────────────────────────────────────────────
                RpmBar(
                    rpm = displayRpm,
                    threshold = displayThreshold,
                    modifier = Modifier.fillMaxWidth(),
                    trailingContent = {
                        if (state.isConnected) {
                            Icon(
                                painterResource(R.drawable.ic_bluetooth),
                                contentDescription = stringResource(R.string.dashboard_bt_connected_desc),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            IconButton(onClick = {
                                navController.navigate(Screen.Settings.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }) {
                                Icon(
                                    painterResource(R.drawable.ic_bluetooth),
                                    contentDescription = stringResource(R.string.dashboard_bt_disconnected_desc),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )

                // ── Sensor grid ───────────────────────────────────────────
                if (isLandscape) {
                    // Landscape: 3 columns — col 3 has 2 stacked cards
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        SensorCardSlot(slots[0], state.sparklines, useImperial, Modifier.weight(1f),
                            onLongPress = { pickerSlot = 0 })
                        SensorCardSlot(slots[1], state.sparklines, useImperial, Modifier.weight(1f),
                            onLongPress = { pickerSlot = 1 })
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            SensorCardSlot(slots[2], state.sparklines, useImperial, Modifier.weight(1f),
                                onLongPress = { pickerSlot = 2 })
                            SensorCardSlot(slots[3], state.sparklines, useImperial, Modifier.weight(1f),
                                onLongPress = { pickerSlot = 3 })
                        }
                    }
                } else {
                    // Portrait: 3 rows — row 3 has 2 cards side by side
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        SensorCardSlot(slots[0], state.sparklines, useImperial, Modifier.weight(1f),
                            onLongPress = { pickerSlot = 0 })
                        SensorCardSlot(slots[1], state.sparklines, useImperial, Modifier.weight(1f),
                            onLongPress = { pickerSlot = 1 })
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            SensorCardSlot(slots[2], state.sparklines, useImperial, Modifier.weight(1f),
                                onLongPress = { pickerSlot = 2 })
                            SensorCardSlot(slots[3], state.sparklines, useImperial, Modifier.weight(1f),
                                onLongPress = { pickerSlot = 3 })
                        }
                    }
                }
            }

            pickerSlot?.let { slot ->
                SlotPickerSheet(
                    currentPidId = slotPids.getOrElse(slot) { "" },
                    availablePids = availablePids,
                    onSelect = { pidId ->
                        vm.setSlotPid(slot, pidId)
                        pickerSlot = null
                    },
                    onDismiss = { pickerSlot = null }
                )
            }
        }
    }
}

@Composable
private fun RpmBar(
    rpm: Float,
    threshold: Int,
    modifier: Modifier = Modifier,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val blinkPeriodMs: Long = if (threshold > 0 && rpm > threshold) {
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

    val isAlert = threshold > 0 && rpm > threshold
    val fillColor = if (isAlert) Color(0xFFD32F2F)
                    else MaterialTheme.colorScheme.primary
    val fraction = (rpm / 10000f).coerceIn(0f, 1f)

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
            if (visible) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(fillColor)
                )
            }
        }
        Text(
            text = "%.0f rpm".format(rpm),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            modifier = Modifier
                .padding(start = 8.dp)
                .width(90.dp)
        )
        trailingContent?.invoke()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SensorCardSlot(
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
        ) {}
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SensorCard(
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
    Surface(
        modifier = modifier
            .semantics { contentDescription = "${value.pid.name}: $displayValue $displayUnit" }
            .combinedClickable(onClick = {}, onLongClick = { onLongPress?.invoke() }),
        shape = RectangleShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(12.dp)
            ) {
                // Sparkline as subtle background line at the bottom
                Sparkline(sparkline, Modifier.fillMaxWidth().height(24.dp).align(Alignment.BottomStart))
                // Label top-left
                Text(
                    value.pid.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopStart)
                )
                // Value + unit bottom-right
                Row(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        displayValue,
                        style = MaterialTheme.typography.displayMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                    Text(
                        " $displayUnit",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Sparkline(points: List<Float>, modifier: Modifier = Modifier) {
    if (points.size < 2) return
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val min = points.min()
        val max = points.max()
        val range = (max - min).coerceAtLeast(0.001f)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = i / (points.size - 1f) * size.width
            val y = size.height - (v - min) / range * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotPickerSheet(
    currentPidId: String,
    availablePids: List<PidDefinition>,
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
                ListItem(
                    modifier = Modifier.clickable { onSelect(pid.id) },
                    headlineContent = { Text("${pid.name} (${pid.unit})") },
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
