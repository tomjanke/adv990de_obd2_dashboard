package de.adv990de.obd2.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.location.LocationPermissions
import de.adv990de.obd2.ui.permission.appSettingsIntent
import de.adv990de.obd2.ui.permission.rememberLocationPermissionState
import de.adv990de.obd2.ui.DriveStateViewModel
import de.adv990de.obd2.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    vm: SettingsViewModel = hiltViewModel(),
    recordingVm: RecordingSettingsViewModel = hiltViewModel(),
    driveVm: DriveStateViewModel = hiltViewModel(),
    displayVm: DisplaySettingsViewModel = hiltViewModel(),
    crashVm: CrashReportingSettingsViewModel = hiltViewModel(),
    fuelVm: FuelSettingsViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val logOnlyDtc by recordingVm.logOnlyDtc.collectAsStateWithLifecycle()
    val fuelRangeFullKm by fuelVm.rangeFullKm.collectAsStateWithLifecycle()
    val hideNavOnDrive by driveVm.hideNavOnDrive.collectAsStateWithLifecycle()
    val useGpsSpeed by displayVm.useGpsSpeed.collectAsStateWithLifecycle()
    val backgroundLocationPrompted by displayVm.backgroundLocationPrompted.collectAsStateWithLifecycle()
    val weatherLocationPrompted by displayVm.weatherLocationPrompted.collectAsStateWithLifecycle()

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
    val useImperial by displayVm.useImperial.collectAsStateWithLifecycle()
    val theme by displayVm.theme.collectAsStateWithLifecycle()
    val showAllBtDevices by vm.showAllBtDevices.collectAsStateWithLifecycle()
    LaunchedEffect(showAllBtDevices) { vm.loadDevices() }
    val rpmBlinkThreshold by displayVm.rpmBlinkThreshold.collectAsStateWithLifecycle()
    val rpmDynamicThreshold by displayVm.rpmDynamicThreshold.collectAsStateWithLifecycle()
    val watchdogTimeoutSeconds by vm.obdWatchdogTimeoutSeconds.collectAsStateWithLifecycle()
    val reconnectBaseDelaySeconds by vm.reconnectBaseDelaySeconds.collectAsStateWithLifecycle()

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

            recordingSection(logOnlyDtc, recordingVm)
            vehicleSection(navController, fuelRangeFullKm, fuelVm::setRangeFullKm)
            infoSection(navController, crashVm)
        }
    }
}
