package de.adv990de.obd2.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.location.LocationPermissions
import de.adv990de.obd2.ui.permission.appSettingsIntent
import de.adv990de.obd2.ui.permission.rememberLocationPermissionState
import de.adv990de.obd2.ui.DriveStateViewModel
import de.adv990de.obd2.ui.navigation.Screen

/** Test-only selector for the GPS-speed [Switch] — its [ListItem] siblings have no other stable
 *  anchor to distinguish it from the other switches on this screen. Purely a semantics marker,
 *  no effect on production behavior. */
internal const val GPS_SPEED_SWITCH_TEST_TAG = "settings_gps_speed_switch"

/** Test-only selectors of the two permission hint rows under the GPS and hide-nav switches. */
internal const val GPS_PERMISSION_HINT_TEST_TAG = "settings_gps_permission_hint"
internal const val WEATHER_PERMISSION_HINT_TEST_TAG = "settings_weather_permission_hint"

/** Test-only selector for the hideNavOnDrive [Switch], same reasoning as [GPS_SPEED_SWITCH_TEST_TAG]. */
internal const val HIDE_NAV_ON_DRIVE_SWITCH_TEST_TAG = "settings_hide_nav_on_drive_switch"

/** Test-only selector for this screen's [LazyColumn], so instrumentation tests can
 *  `performScrollToNode` to items below the initially-composed viewport instead of relying on
 *  them already being laid out — needed once the list grew past what a single screen shows. */
internal const val SETTINGS_LIST_TEST_TAG = "settings_list"

/** Test-only selectors for the two fixed-choice rows of the Connection section. Both groups offer
 *  "10" and "30", so instrumentation tests locate a segment by its text PLUS this ancestor tag. */
internal const val OBD_WATCHDOG_CHOICE_TEST_TAG = "settings_obd_watchdog_choice"
internal const val RECONNECT_BASE_DELAY_CHOICE_TEST_TAG = "settings_reconnect_base_delay_choice"

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

    // Der Stand der Standortberechtigung (liest bei jedem ON_RESUME neu: sie kann in den Systemeinstellungen
    // geaendert werden) und die Antwort auf die letzte GPS-Anfrage — beides speist die Hinweiszeilen (B10, B21).
    val locationPermission = rememberLocationPermissionState()
    var gpsRequestDenied by rememberSaveable { mutableStateOf(false) }
    val openAppSettings: () -> Unit = {
        try {
            context.startActivity(appSettingsIntent(context.packageName))
        } catch (_: ActivityNotFoundException) {
            // Kein Einstellungs-Handler (exotisches OEM-Image): der Hinweistext bleibt stehen.
        }
    }

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
        // Ohne genauen Standort ("Ungefaehr" oder "Ablehnen" im System-Dialog) bleibt der Schalter aus — die
        // Hinweiszeile sagt dann, warum (B21).
        gpsRequestDenied = !granted
        locationPermission.refresh()
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
    ) { locationPermission.refresh() }

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
    val rpmBlinkThreshold by displayVm.rpmBlinkThreshold.collectAsState()
    val rpmDynamicThreshold by displayVm.rpmDynamicThreshold.collectAsState()
    val watchdogTimeoutSeconds by vm.obdWatchdogTimeoutSeconds.collectAsState()
    val reconnectBaseDelaySeconds by vm.reconnectBaseDelaySeconds.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { padding ->
        // One flat, grouped page (AOSP settings guideline: related settings clustered under a heading and
        // hairlines): Verbindung · Dashboard & Anzeige · Aufzeichnung · Fahrzeug · Info & Wartung.
        // Sub-pages are LINK rows with a summary and a chevron; every switch is a whole-row toggle.
        LazyColumn(
            modifier = Modifier.testTag(SETTINGS_LIST_TEST_TAG),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
                start = 16.dp,
                end = 16.dp
            )
        ) {
            // ── Connection error ──────────────────────────────────────────
            state.error?.let { error ->
                item {
                    Text(error.asString(), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                }
            }

            // ═══ Verbindung: Adapter + Reconnect-Verhalten ════════════════
            item { SettingsGroupHeader(stringResource(R.string.settings_connection_section)) }
            item {
                Text(
                    stringResource(R.string.settings_paired_devices),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (state.isConnecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_show_all_bt_devices),
                    summary = stringResource(R.string.settings_show_all_bt_devices_desc),
                    checked = showAllBtDevices,
                    onCheckedChange = { vm.toggleShowAllBtDevices() },
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
            item {
                ChoiceSetting(
                    headline = stringResource(R.string.settings_obd_watchdog_timeout),
                    supportingText = stringResource(R.string.settings_obd_watchdog_timeout_desc),
                    options = AppPreferences.OBD_WATCHDOG_TIMEOUT_OPTIONS_SECONDS,
                    selected = watchdogTimeoutSeconds,
                    onSelect = vm::setObdWatchdogTimeoutSeconds,
                    optionDescriptionPlural = R.plurals.settings_seconds_a11y,
                    modifier = Modifier.testTag(OBD_WATCHDOG_CHOICE_TEST_TAG),
                )
                HorizontalDivider()
            }
            item {
                // Shows what the chosen base really means (the waits are doubled per attempt) —
                // derived from the production reconnectDelays(), so it cannot drift.
                val backoff = remember(reconnectBaseDelaySeconds) {
                    reconnectBackoffSummary(reconnectBaseDelaySeconds)
                }
                ChoiceSetting(
                    headline = stringResource(R.string.settings_reconnect_base_delay),
                    supportingText = stringResource(R.string.settings_reconnect_base_delay_desc),
                    options = AppPreferences.RECONNECT_BASE_DELAY_OPTIONS_SECONDS,
                    selected = reconnectBaseDelaySeconds,
                    onSelect = vm::setReconnectBaseDelaySeconds,
                    optionDescriptionPlural = R.plurals.settings_seconds_a11y,
                    additionalSupportingText = stringResource(
                        R.string.settings_reconnect_base_delay_series,
                        backoff.delaysSeconds.joinToString(" · "),
                        formatMinutesSeconds(backoff.totalSeconds)
                    ),
                    modifier = Modifier.testTag(RECONNECT_BASE_DELAY_CHOICE_TEST_TAG),
                )
                HorizontalDivider()
            }

            // ═══ Dashboard & Anzeige ═════════════════════════════════════
            item { SettingsGroupHeader(stringResource(R.string.settings_display_section)) }
            item {
                ThemePickerItem(theme = theme, onThemeChange = { displayVm.setTheme(it) })
                HorizontalDivider()
            }
            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_imperial_units),
                    summary = stringResource(R.string.settings_imperial_units_desc),
                    checked = useImperial,
                    onCheckedChange = { displayVm.toggleUseImperial() },
                )
                HorizontalDivider()
            }
            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_hide_nav_on_drive),
                    summary = stringResource(R.string.settings_hide_nav_on_drive_desc),
                    checked = hideNavOnDrive,
                    // Die Standortanfrage selbst laeuft ausschliesslich ueber
                    // LaunchedEffect(hideNavOnDrive) oben (reagiert auf genau diese
                    // Zustandsaenderung) — das mirrort, wie useGpsSpeed oben seine
                    // Hintergrund-Standort-Eskalation ausschliesslich ueber deren eigenen
                    // LaunchedEffect abwickelt statt zusaetzlich hier direkt.
                    onCheckedChange = { driveVm.toggleHideNavOnDrive() },
                    modifier = Modifier.testTag(HIDE_NAV_ON_DRIVE_SWITCH_TEST_TAG),
                )
                HorizontalDivider()
            }
            // Die Berechtigung wird nur EINMAL angefragt; wer "Nicht erlauben" waehlte, sah nie, warum die
            // Aussentemperatur fehlt (B21).
            if (weatherPermissionHint(hideNavOnDrive, locationPermission.fine, locationPermission.coarse)) {
                item {
                    PermissionHintRow(
                        text = stringResource(R.string.settings_weather_permission_missing),
                        buttonLabel = stringResource(R.string.settings_permission_open_settings),
                        onOpenSettings = openAppSettings,
                        modifier = Modifier.testTag(WEATHER_PERMISSION_HINT_TEST_TAG),
                    )
                    HorizontalDivider()
                }
            }
            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_gps_speed),
                    summary = stringResource(R.string.settings_gps_speed_desc),
                    checked = useGpsSpeed,
                    onCheckedChange = { wanted ->
                        gpsRequestDenied = false
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
                    modifier = Modifier.testTag(GPS_SPEED_SWITCH_TEST_TAG),
                )
                HorizontalDivider()
            }
            // Schalter AN, Berechtigung weg (entzogen / "Nur dieses Mal" abgelaufen) oder die Anfrage ohne genauen
            // Standort beantwortet: GPS liefert still nichts — der Hinweis sagt es (B10, B21).
            if (gpsSpeedPermissionHint(useGpsSpeed, locationPermission.fine, gpsRequestDenied)) {
                item {
                    PermissionHintRow(
                        text = stringResource(R.string.settings_gps_permission_missing),
                        buttonLabel = stringResource(R.string.settings_permission_open_settings),
                        onOpenSettings = openAppSettings,
                        modifier = Modifier.testTag(GPS_PERMISSION_HINT_TEST_TAG),
                    )
                    HorizontalDivider()
                }
            }
            // Fruehere eigene Seite "Dashboard konfigurieren": Blinkgrenze + dynamische Schwelle.
            item {
                RpmThresholdSection(
                    rpmBlinkThreshold = rpmBlinkThreshold,
                    rpmDynamicThreshold = rpmDynamicThreshold,
                    onThresholdChangeFinished = displayVm::setRpmBlinkThreshold,
                    onDynamicThresholdChange = displayVm::setRpmDynamicThreshold,
                )
            }
            item {
                SettingsLinkRow(
                    title = stringResource(R.string.settings_gear_ratios),
                    summary = stringResource(R.string.settings_gear_ratios_summary),
                    onClick = { navController.navigate(Screen.GearSettings.route) },
                )
                HorizontalDivider()
            }

            // ═══ Aufzeichnung ════════════════════════════════════════════
            item { SettingsGroupHeader(stringResource(R.string.settings_recording_section)) }
            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_log_only_dtc),
                    summary = stringResource(R.string.settings_log_only_dtc_desc),
                    checked = logOnlyDtc,
                    onCheckedChange = { recordingVm.toggleLogOnlyDtc() },
                )
                HorizontalDivider()
            }
            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_log_only_discovered),
                    summary = stringResource(R.string.settings_log_only_discovered_desc),
                    checked = logOnlyDiscovered,
                    onCheckedChange = { recordingVm.toggleLogOnlyDiscovered() },
                )
                HorizontalDivider()
            }
            item {
                SettingsLinkRow(
                    title = stringResource(R.string.settings_logging_pids),
                    summary = stringResource(R.string.settings_logging_pids_summary),
                    onClick = { navController.navigate(Screen.LoggingConfig.route) },
                )
                HorizontalDivider()
            }

            // ═══ Fahrzeug ════════════════════════════════════════════════
            item { SettingsGroupHeader(stringResource(R.string.settings_group_vehicle)) }
            item {
                SettingsLinkRow(
                    title = stringResource(R.string.settings_vehicle_info),
                    summary = stringResource(R.string.settings_vehicle_info_summary),
                    onClick = { navController.navigate(Screen.VehicleInfo.route) },
                )
                HorizontalDivider()
            }

            // ═══ Info & Wartung ══════════════════════════════════════════
            item { SettingsGroupHeader(stringResource(R.string.settings_group_info)) }
            item {
                SettingsLinkRow(
                    title = stringResource(R.string.settings_app_logs),
                    summary = stringResource(R.string.settings_app_logs_summary),
                    onClick = { navController.navigate(Screen.AppLogs.route) },
                )
                HorizontalDivider()
            }
            item {
                SettingsLinkRow(
                    title = stringResource(R.string.settings_app_info),
                    summary = stringResource(R.string.settings_app_info_summary),
                    onClick = { navController.navigate(Screen.AppInfo.route) },
                )
                HorizontalDivider()
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
