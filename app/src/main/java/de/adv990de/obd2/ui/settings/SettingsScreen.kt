package de.adv990de.obd2.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.location.LocationPermissions
import de.adv990de.obd2.ui.DriveStateViewModel
import de.adv990de.obd2.ui.navigation.Screen

/** Test-only selector for the GPS-speed [Switch] — its [ListItem] siblings have no other stable
 *  anchor to distinguish it from the other switches on this screen. Purely a semantics marker,
 *  no effect on production behavior. */
internal const val GPS_SPEED_SWITCH_TEST_TAG = "settings_gps_speed_switch"

/** Test-only selector for the hideNavOnDrive [Switch], same reasoning as [GPS_SPEED_SWITCH_TEST_TAG]. */
internal const val HIDE_NAV_ON_DRIVE_SWITCH_TEST_TAG = "settings_hide_nav_on_drive_switch"

/** Test-only selector for this screen's [LazyColumn], so instrumentation tests can
 *  `performScrollToNode` to items below the initially-composed viewport instead of relying on
 *  them already being laid out — needed once the list grew past what a single screen shows. */
internal const val SETTINGS_LIST_TEST_TAG = "settings_list"

/** The GPS-speed switch's three-way outcome once the user toggles it, decided purely from the
 *  requested state and the current fine-location grant — extracted so the branch is directly
 *  unit-testable without Compose/instrumentation (same convention as the internal decision
 *  functions in `DashboardComponents.kt`, e.g. `rpmThresholdTempScale`). */
internal enum class GpsSpeedSwitchAction { SET_OFF, SET_ON, REQUEST_PERMISSION }

internal fun determineGpsSpeedSwitchAction(wanted: Boolean, granted: Boolean): GpsSpeedSwitchAction =
    when {
        !wanted -> GpsSpeedSwitchAction.SET_OFF
        granted -> GpsSpeedSwitchAction.SET_ON
        else -> GpsSpeedSwitchAction.REQUEST_PERMISSION
    }

/** Pure trigger condition for [SettingsScreen]'s background-location escalation — extracted out of
 *  `escalateToBackgroundLocationIfNeeded()` so it is directly unit-testable. Mirrors the exact
 *  boolean expression that function evaluated inline before this extraction (no behavior change):
 *  short-circuits on `alreadyPrompted` before ever consulting [LocationPermissions.hasBackgroundLocation]. */
internal fun shouldEscalateToBackgroundLocation(
    alreadyPrompted: Boolean,
    sdkInt: Int,
    backgroundLocationGranted: Boolean
): Boolean = !alreadyPrompted &&
    LocationPermissions.needsBackgroundLocationRequest(sdkInt, backgroundLocationGranted)

/** Whether turning the hideNavOnDrive switch should also ask for location: the row shown in place
 *  of the hidden nav bar (`AmbientStatusRow`) looks up the outside temperature for the current
 *  position, and fine OR coarse location is enough for that. Deliberately NOT a gate like
 *  [determineGpsSpeedSwitchAction]: hideNavOnDrive itself has nothing to do with location and always
 *  follows the toggle — without a grant the row just shows no temperature. Extracted for the same
 *  JVM-testability reason as the two functions above.
 *
 *  [alreadyPrompted] short-circuits before ever consulting the other three parameters — the exact
 *  same shape as [shouldEscalateToBackgroundLocation]'s own parameter of that name. Added because
 *  the catch-up effect below otherwise re-prompts on every Settings visit/rotation while the
 *  switch is on and permission is still denied, since without it there was no persisted "already
 *  asked" guard. Backed by `AppPreferences.weatherLocationPrompted`, checked-and-set exactly like
 *  `backgroundLocationPrompted`.
 *
 *  Called from a single call site, `LaunchedEffect(hideNavOnDrive)` — both the switch's own
 *  toggle-to-on (state change triggers the effect) and, since this catches up users whose switch
 *  was already on from before this weather feature existed, every fresh composition where the
 *  DataStore-backed value resolves to `true` (same `useGpsSpeed`/background-location catch-up
 *  shape as [shouldEscalateToBackgroundLocation] above). */
internal fun shouldRequestLocationForWeather(
    alreadyPrompted: Boolean,
    turningOn: Boolean,
    fineGranted: Boolean,
    coarseGranted: Boolean,
): Boolean = !alreadyPrompted && turningOn && !fineGranted && !coarseGranted

/** Validates the OBD watchdog timeout field below: a whole number of seconds, at least
 *  [AppPreferences.MIN_OBD_WATCHDOG_TIMEOUT_SECONDS] — a few seconds would let the watchdog force
 *  reconnects on ordinary timeout noise. Returns null for anything else. Pulled into its own
 *  top-level function so it's JVM-testable without Compose, mirroring `GearSettingsScreen.kt`'s
 *  `parsePositiveFloat`. */
internal fun parseWatchdogTimeoutSeconds(text: String): Int? =
    text.toIntOrNull()?.takeIf { it >= AppPreferences.MIN_OBD_WATCHDOG_TIMEOUT_SECONDS }

/** Validates the reconnect base-delay field below, mirroring [parseWatchdogTimeoutSeconds]
 *  exactly (same reasoning, different floor: [AppPreferences.MIN_RECONNECT_BASE_DELAY_SECONDS]). */
internal fun parseReconnectBaseDelaySeconds(text: String): Int? =
    text.toIntOrNull()?.takeIf { it >= AppPreferences.MIN_RECONNECT_BASE_DELAY_SECONDS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    vm: SettingsViewModel = hiltViewModel(),
    recordingVm: RecordingSettingsViewModel = hiltViewModel(),
    driveVm: DriveStateViewModel = hiltViewModel(),
    displayVm: DisplaySettingsViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    val logOnlyDtc by recordingVm.logOnlyDtc.collectAsState()
    val logOnlyDiscovered by recordingVm.logOnlyDiscovered.collectAsState()
    val hideNavOnDrive by driveVm.hideNavOnDrive.collectAsState()
    val useGpsSpeed by displayVm.useGpsSpeed.collectAsState()
    val backgroundLocationPrompted by displayVm.backgroundLocationPrompted.collectAsState()
    val weatherLocationPrompted by displayVm.weatherLocationPrompted.collectAsState()

    // Die Standortberechtigung wird erst angefragt, wenn der Nutzer GPS-Geschwindigkeit oder das
    // Ausblenden des Hauptmenues (dort fuer die Aussentemperatur) einschaltet — nicht beim Start
    // der App. Nur beim GPS-Schalter entscheidet die Antwort ueber den Schalter: verweigert, bleibt
    // er aus.
    val context = LocalContext.current

    // Ergebnis wird nicht ausgewertet: GPS-Speed funktioniert so oder so im Vordergrund weiter,
    // ob der Nutzer die zusaetzliche Hintergrund-Berechtigung erteilt oder ablehnt. Android
    // verlangt diese Anfrage getrennt von Fine/Coarse — beides zusammen wird ab API 30 ignoriert.
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    @SuppressLint("InlinedApi")
    fun escalateToBackgroundLocationIfNeeded() {
        if (shouldEscalateToBackgroundLocation(
                backgroundLocationPrompted, Build.VERSION.SDK_INT, LocationPermissions.hasBackgroundLocation(context)
            )
        ) {
            displayVm.setBackgroundLocationPrompted(true)
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        displayVm.setUseGpsSpeed(granted)
    }

    // Ergebnis wird bewusst nicht ausgewertet (gleiches Muster wie backgroundLocationLauncher):
    // hideNavOnDrive folgt immer dem Schalter. Die Berechtigung braucht nur die Aussentemperatur in
    // der Zeile, die statt des ausgeblendeten Hauptmenues erscheint — ohne sie fehlt dort einfach
    // die Temperatur, Datum und Uhrzeit bleiben. FINE und COARSE zusammen angefragt: ab Android 12
    // waehlt der Nutzer im System-Dialog "Genau" oder "Ungefaehr", beides genuegt fuers Wetter.
    // Ausgeloest ausschliesslich von LaunchedEffect(hideNavOnDrive) weiter unten (Toggle UND
    // Catch-up fuer schon laenger eingeschaltete Nutzer laufen darueber gemeinsam).
    val weatherLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    // Holt Nutzer nach, deren Schalter aus einer frueheren Version bereits an ist, aber die den
    // Hintergrund-Prompt sonst nie zu sehen bekaemen. Key ist useGpsSpeed statt Unit: der Wert
    // kommt asynchron aus dem DataStore und ist bei der ersten Komposition noch der Default
    // (false), daher muss der Effect erneut laufen, sobald der echte Wert eintrifft — und deckt
    // damit als einziger Aufrufpunkt zugleich den frischen Grant und das Switch-Toggle ab.
    LaunchedEffect(useGpsSpeed) {
        if (useGpsSpeed) escalateToBackgroundLocationIfNeeded()
    }

    // Holt, nach demselben Muster wie der useGpsSpeed-Effect oben, Nutzer nach, deren
    // hideNavOnDrive-Schalter aus einer frueheren Version (vor dieser Wetter-Funktion) bereits an
    // ist und die den weatherLocationLauncher-Prompt sonst nie zu sehen bekaemen: hideNavOnDrive
    // kommt genauso asynchron aus dem DataStore und ist bei der ersten Komposition noch der
    // Default (false), der Effect laeuft also erneut, sobald der echte (persistierte) Wert
    // eintrifft. Einziger Aufrufpunkt fuer diese Anfrage — der Switch unten ruft
    // weatherLocationLauncher nicht mehr direkt aus onCheckedChange, sonst wuerde ein Toggle-auf-an
    // sie zusammen mit diesem Effect doppelt auslösen.
    //
    // weatherLocationPrompted (seit 2026-09-27) ist der Einmal-Guard, der genau dieser Katch-up-
    // Logik fehlte: ohne ihn fragte dieser Effect bei JEDEM Settings-Besuch/jeder Rotation erneut
    // an, solange der Schalter an und die Berechtigung noch verweigert war (der Effect laeuft bei
    // jeder frischen Komposition mit hideNavOnDrive==true neu, nicht nur beim Toggle). Check-and-
    // set exakt wie escalateToBackgroundLocationIfNeeded()/backgroundLocationPrompted oben.
    LaunchedEffect(hideNavOnDrive) {
        if (shouldRequestLocationForWeather(
                alreadyPrompted = weatherLocationPrompted,
                turningOn = hideNavOnDrive,
                fineGranted = LocationPermissions.hasFineLocation(context),
                coarseGranted = LocationPermissions.hasCoarseLocation(context),
            )
        ) {
            displayVm.setWeatherLocationPrompted(true)
            weatherLocationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }
    val useImperial by displayVm.useImperial.collectAsState()
    val theme by displayVm.theme.collectAsState()
    val showAllBtDevices by vm.showAllBtDevices.collectAsState()
    LaunchedEffect(showAllBtDevices) { vm.loadDevices() }
    val watchdogTimeoutSeconds by vm.obdWatchdogTimeoutSeconds.collectAsState()
    val reconnectBaseDelaySeconds by vm.reconnectBaseDelaySeconds.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.testTag(SETTINGS_LIST_TEST_TAG),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
                start = 16.dp,
                end = 16.dp
            )
        ) {
            // ── Connection error ──────────────────────────────────────────
            state.error?.let { error ->
                item {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
            }

            // ── Paired devices ────────────────────────────────────────────
            item {
                Text(stringResource(R.string.settings_paired_devices), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (state.isConnecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_show_all_bt_devices)) },
                    supportingContent = { Text(stringResource(R.string.settings_show_all_bt_devices_desc)) },
                    trailingContent = {
                        Switch(
                            checked = showAllBtDevices,
                            onCheckedChange = { vm.toggleShowAllBtDevices() }
                        )
                    }
                )
                HorizontalDivider()
            }
            items(state.bondedDevices, key = { it.address }) { device ->
                val isConnected = device.address == state.connectedDeviceAddress
                DeviceRow(
                    device = device,
                    onClick = { vm.connect(device) },
                    onDisconnect = { vm.disconnect() },
                    enabled = !state.isConnecting,
                    isLastConnected = device.address == state.lastDeviceAddress,
                    isConnected = isConnected
                )
                HorizontalDivider()
            }
            if (state.bondedDevices.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.settings_no_paired_devices),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
            item { HorizontalDivider() }

            // ── Recording section ─────────────────────────────────────────
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.settings_recording_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_log_only_dtc)) },
                    supportingContent = { Text(stringResource(R.string.settings_log_only_dtc_desc)) },
                    trailingContent = {
                        Switch(
                            checked = logOnlyDtc,
                            onCheckedChange = { recordingVm.toggleLogOnlyDtc() }
                        )
                    }
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_log_only_discovered)) },
                    supportingContent = { Text(stringResource(R.string.settings_log_only_discovered_desc)) },
                    trailingContent = {
                        Switch(
                            checked = logOnlyDiscovered,
                            onCheckedChange = { recordingVm.toggleLogOnlyDiscovered() }
                        )
                    }
                )
                HorizontalDivider()
            }

            // ── Connection section ────────────────────────────────────────
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.settings_connection_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
            }
            item {
                // hasBeenEdited-gated buffer (same guard as GearRatioField in
                // GearSettingsScreen.kt): every valid keystroke here persists immediately, so a
                // plain remember(watchdogTimeoutSeconds) would re-seed the buffer from the
                // DataStore round-trip of the user's OWN previous keystroke — e.g. typing "45"
                // could persist "4" first, have it echo back before "5" is typed, and clobber the
                // buffer back to "4". Once the user has edited, only their typing drives the
                // buffer; the upstream value still seeds it before that first edit.
                var text by remember { mutableStateOf(watchdogTimeoutSeconds.toString()) }
                var hasBeenEdited by remember { mutableStateOf(false) }
                LaunchedEffect(watchdogTimeoutSeconds) {
                    if (!hasBeenEdited) {
                        text = watchdogTimeoutSeconds.toString()
                    }
                }
                val isValid = parseWatchdogTimeoutSeconds(text) != null
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_obd_watchdog_timeout)) },
                    supportingContent = {
                        Text(
                            if (isValid) stringResource(R.string.settings_obd_watchdog_timeout_desc)
                            else pluralStringResource(
                                R.plurals.settings_obd_watchdog_timeout_invalid,
                                AppPreferences.MIN_OBD_WATCHDOG_TIMEOUT_SECONDS,
                                AppPreferences.MIN_OBD_WATCHDOG_TIMEOUT_SECONDS
                            )
                        )
                    },
                    trailingContent = {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { newText ->
                                hasBeenEdited = true
                                text = newText
                                parseWatchdogTimeoutSeconds(newText)?.let { vm.setObdWatchdogTimeoutSeconds(it) }
                            },
                            isError = !isValid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp),
                            singleLine = true,
                        )
                    }
                )
                HorizontalDivider()
            }
            item {
                // Gleiches hasBeenEdited-Gerüst wie beim Watchdog-Timeout-Feld oben, aus demselben
                // Grund (Backlog-Cleanup, Sweep 2026-09-28/29).
                var text by remember { mutableStateOf(reconnectBaseDelaySeconds.toString()) }
                var hasBeenEdited by remember { mutableStateOf(false) }
                LaunchedEffect(reconnectBaseDelaySeconds) {
                    if (!hasBeenEdited) {
                        text = reconnectBaseDelaySeconds.toString()
                    }
                }
                val isValid = parseReconnectBaseDelaySeconds(text) != null
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_reconnect_base_delay)) },
                    supportingContent = {
                        Text(
                            if (isValid) stringResource(R.string.settings_reconnect_base_delay_desc)
                            else pluralStringResource(
                                R.plurals.settings_reconnect_base_delay_invalid,
                                AppPreferences.MIN_RECONNECT_BASE_DELAY_SECONDS,
                                AppPreferences.MIN_RECONNECT_BASE_DELAY_SECONDS
                            )
                        )
                    },
                    trailingContent = {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { newText ->
                                hasBeenEdited = true
                                text = newText
                                parseReconnectBaseDelaySeconds(newText)?.let { vm.setReconnectBaseDelaySeconds(it) }
                            },
                            isError = !isValid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(80.dp),
                            singleLine = true,
                        )
                    }
                )
                HorizontalDivider()
            }

            // ── Display section ───────────────────────────────────────────
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.settings_display_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_hide_nav_on_drive)) },
                    supportingContent = { Text(stringResource(R.string.settings_hide_nav_on_drive_desc)) },
                    trailingContent = {
                        Switch(
                            checked = hideNavOnDrive,
                            // Die Standortanfrage selbst laeuft ausschliesslich ueber
                            // LaunchedEffect(hideNavOnDrive) oben (reagiert auf genau diese
                            // Zustandsaenderung) — das mirrort, wie useGpsSpeed oben seine
                            // Hintergrund-Standort-Eskalation ausschliesslich ueber deren eigenen
                            // LaunchedEffect abwickelt statt zusaetzlich hier direkt.
                            onCheckedChange = { driveVm.toggleHideNavOnDrive() },
                            modifier = Modifier.testTag(HIDE_NAV_ON_DRIVE_SWITCH_TEST_TAG)
                        )
                    }
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_gps_speed)) },
                    supportingContent = { Text(stringResource(R.string.settings_gps_speed_desc)) },
                    trailingContent = {
                        Switch(
                            checked = useGpsSpeed,
                            onCheckedChange = { wanted ->
                                val granted = LocationPermissions.hasFineLocation(context)
                                when (determineGpsSpeedSwitchAction(wanted, granted)) {
                                    GpsSpeedSwitchAction.SET_OFF -> displayVm.setUseGpsSpeed(false)
                                    GpsSpeedSwitchAction.SET_ON -> displayVm.setUseGpsSpeed(true)
                                    GpsSpeedSwitchAction.REQUEST_PERMISSION -> locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.testTag(GPS_SPEED_SWITCH_TEST_TAG)
                        )
                    }
                )
                HorizontalDivider()
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_imperial_units)) },
                    supportingContent = { Text(stringResource(R.string.settings_imperial_units_desc)) },
                    trailingContent = {
                        Switch(
                            checked = useImperial,
                            onCheckedChange = { displayVm.toggleUseImperial() }
                        )
                    }
                )
                HorizontalDivider()
            }
            item {
                ThemePickerItem(theme = theme, onThemeChange = { displayVm.setTheme(it) })
                HorizontalDivider()
            }

            // ── Navigation ────────────────────────────────────────────────
            item {
                TextButton(
                    onClick = { navController.navigate(Screen.DashboardConfig.route) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_configure_dashboard)) }
                HorizontalDivider()
                TextButton(
                    onClick = { navController.navigate(Screen.LoggingConfig.route) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_logging_pids)) }
                HorizontalDivider()
                TextButton(
                    onClick = { navController.navigate(Screen.AppLogs.route) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_app_logs)) }
                HorizontalDivider()
                TextButton(
                    onClick = { navController.navigate(Screen.GearSettings.route) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_gear_ratios)) }
                HorizontalDivider()
                TextButton(
                    onClick = { navController.navigate(Screen.VehicleInfo.route) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_vehicle_info)) }
                HorizontalDivider()
                TextButton(
                    onClick = { navController.navigate(Screen.AppInfo.route) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.settings_app_info)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceRow(
    device: ScannedDevice,
    onClick: () -> Unit,
    onDisconnect: () -> Unit = {},
    enabled: Boolean = true,
    isLastConnected: Boolean = false,
    isConnected: Boolean = false
) {
    ListItem(
        headlineContent = { Text(device.name) },
        supportingContent = { Text(device.type.name) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLastConnected && !isConnected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.settings_last_connected_desc),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                if (isConnected) {
                    Button(onClick = onDisconnect, enabled = enabled) { Text(stringResource(R.string.settings_disconnect)) }
                } else {
                    Button(onClick = onClick, enabled = enabled) { Text(stringResource(R.string.settings_connect)) }
                }
            }
        }
    )
}
