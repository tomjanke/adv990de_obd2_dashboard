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
import androidx.compose.foundation.layout.height
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
    val useGpsSpeed by displayVm.useGpsSpeed.collectAsState()
    val rpmBlinkThreshold by vm.rpmBlinkThreshold.collectAsState()
    val rpmDynamicThreshold by vm.rpmDynamicThreshold.collectAsState()
    val slotPids by vm.slotPids.collectAsState()
    val availablePids by vm.availablePids.collectAsState()
    var pickerSlot: Int? by remember { mutableStateOf(null) }
    // Show real readings when connected; fall back to placeholder (value=0) tiles when not.
    val displayReadings = if (state.readings.isNotEmpty()) state.readings else placeholderReadings

    // Keep screen on for as long as the Dashboard screen is visible, connected or not (User-Wunsch
    // 2026-09-29 — vorher nur bei state.isConnected, s. MEMORY.md). DisposableEffect(Unit) statt
    // eines State-Keys: das Flag soll konstant gesetzt bleiben, waehrend dieser Screen komponiert
    // ist, und nur beim Verlassen (onDispose) wieder geloescht werden — nicht mehr reaktiv auf
    // einen sich aendernden Verbindungsstatus.
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            if (shouldStartRpmDemoSweep(rpmValueRef.value)) {
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
        // Dynamic scaling: when enabled, reduce threshold proportionally to engine temperature —
        // the temp-band → factor mapping is rpmThresholdTempScale() (DashboardComponents.kt),
        // extracted as a pure function for JVM unit-test coverage (DashboardComponentsTest.kt).
        // Note: not wrapped in remember — rpmDemo.value changes every animation frame and must
        // stay reactive; the scaling arithmetic is cheap.
        val displayThreshold = when {
            rpmBlinkThreshold == 0 -> if (rpmDemo.value > 0f) 7000 else 0
            !rpmDynamicThreshold   -> rpmBlinkThreshold
            else -> (rpmBlinkThreshold * rpmThresholdTempScale(state.engineTempC)).toInt()
        }

        // While connected but waiting for first readings: brief full-screen spinner.
        if (state.isConnected && state.readings.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val supportedPidIds by vm.supportedPidIds.collectAsState()
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
                    gear = state.gear,
                    isNeutral = state.isNeutral,
                    oilBarColor = state.oilBarColor,
                    modifier = Modifier.fillMaxWidth(),
                    isLive = state.isConnected,
                    trailingContent = {
                        val dotState = dashboardStatusDotState(
                            isConnected = state.isConnected,
                            isReconnecting = state.isReconnecting,
                            hasError = state.error != null,
                        )
                        if (state.isConnected) {
                            Row(
                                modifier = Modifier.padding(start = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                // 0dp: Dot soll so nah wie moeglich am Bluetooth-Icon sitzen.
                                horizontalArrangement = Arrangement.spacedBy(0.dp),
                            ) {
                                if (dotState != null) {
                                    StatusDot(state = dotState, color = connectionDotColor(dotState))
                                }
                                Icon(
                                    painterResource(R.drawable.ic_bluetooth),
                                    contentDescription = stringResource(R.string.dashboard_bt_connected_desc),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.padding(start = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                // Negativer Abstand gleicht IconButtons eigenes ~12dp-Innenpolster
                                // um sein 24dp-Icon aus (Default-Touch-Ziel 48dp) — sonst saehe der
                                // Dot hier deutlich weiter vom Icon-Glyphen entfernt aus als bei der
                                // verbundenen Variante oben, die ein reines Icon ohne Polster nutzt.
                                horizontalArrangement = Arrangement.spacedBy((-12).dp),
                            ) {
                                if (dotState != null) {
                                    StatusDot(state = dotState, color = connectionDotColor(dotState))
                                }
                                IconButton(onClick = {
                                    navController.navigate(Screen.Settings.route) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }) {
                                    Icon(
                                        painterResource(R.drawable.ic_bluetooth),
                                        contentDescription = stringResource(
                                            if (state.isReconnecting) R.string.dashboard_bt_connecting_desc
                                            else R.string.dashboard_bt_disconnected_desc
                                        ),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                )

                // ── Vorschau-Band ─────────────────────────────────────────
                // Ohne Verbindung zeigt das Dashboard die Slot-Belegung mit "--" statt Zahlen.
                // Das Band sagt, warum: Ein Raster aus Kacheln ohne Werte laesst sonst offen, ob
                // die App nichts misst oder nichts messen kann.
                if (!state.isConnected) {
                    PreviewBanner(modifier = Modifier.fillMaxWidth())
                }

                // ── Sensor grid ───────────────────────────────────────────
                // Feste Kachelhoehen statt weight(1f): zuvor dehnten sich die Kacheln ueber die
                // gesamte Resthoehe, wodurch Label (oben links) und Wert (unten rechts) mehrere
                // hundert dp auseinanderlagen — bei 0,5 s Blickzeit nicht mehr zuzuordnen.
                // Portrait (170dp) entspricht der Anatomie in CLAUDE.md. Querformat ist bewusst
                // hoeher (200dp, dokumentierte Ausnahme in CLAUDE.md): dort bleibt sonst ein
                // grosser Teil der Bildschirmhoehe unter der Kachel-Reihe leer. Das Ausblenden
                // des Hauptmenues waehrend der Fahrt gibt seit 2026-09-27 keinen Platz mehr frei
                // (die AmbientStatusRow nimmt exakt dessen Hoehe ein) — die Begruendung beruht
                // allein auf der Querformat-Geometrie. Der zusaetzliche Raum ist bewusster
                // Atemraum zwischen Label und Sparkline/Wert, nicht zusaetzlicher Inhalt — eine
                // zweite Kachel-Reihe waere der groessere Eingriff gewesen.
                val tileHeight = if (isLandscape) 200.dp else 170.dp
                Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (isLandscape) {
                        // Landscape: vier gleich breite Spalten nebeneinander — nutzt die Breite,
                        // statt eine Spalte zu stapeln und eine halbe Kachel leer zu lassen.
                        Row(
                            modifier = Modifier.fillMaxWidth().height(tileHeight)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            repeat(4) { i ->
                                SensorCardSlot(
                                    slots[i], state.sparklines, useImperial,
                                    Modifier.weight(1f).fillMaxHeight(),
                                    placeholderRes = slotPlaceholderRes(
                                        slotPids.getOrNull(i), supportedPidIds, state.isConnected, useGpsSpeed
                                    ),
                                    isLive = state.isConnected,
                                    onLongPress = { pickerSlot = i }
                                )
                            }
                        }
                    } else {
                        // Portrait: zwei Spalten, zwei Reihen (CLAUDE.md: "2 Spalten Standard")
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.outlineVariant),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            repeat(2) { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(tileHeight),
                                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    repeat(2) { col ->
                                        val i = row * 2 + col
                                        SensorCardSlot(
                                            slots[i], state.sparklines, useImperial,
                                            Modifier.weight(1f).fillMaxHeight(),
                                            placeholderRes = slotPlaceholderRes(
                                                slotPids.getOrNull(i), supportedPidIds, state.isConnected, useGpsSpeed
                                            ),
                                            isLive = state.isConnected,
                                            onLongPress = { pickerSlot = i }
                                        )
                                    }
                                }
                            }
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
