package de.adv990de.obd2.ui.dashboard

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.ui.components.StatusDot
import de.adv990de.obd2.ui.components.StatusDotState
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.settings.DisplaySettingsViewModel
import kotlinx.coroutines.delay

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

    val isConnected = state.isConnected

    // Keep screen on only while OBD is connected (Dashboard screen is active).
    val context = LocalContext.current
    DisposableEffect(isConnected) {
        val window = (context as? android.app.Activity)?.window
        if (isConnected) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                StatusDot(state = StatusDotState.Ok)
                                Icon(
                                    painterResource(R.drawable.ic_bluetooth),
                                    contentDescription = stringResource(R.string.dashboard_bt_connected_desc),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
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
                    readings = buildList {
                        addAll(state.readings)
                        if (state.rpmValue > 0f) add(SensorValue(PidRegistry.RPM, state.rpmValue))
                    },
                    useImperial = useImperial,
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
