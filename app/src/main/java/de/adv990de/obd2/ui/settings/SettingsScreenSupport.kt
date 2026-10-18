package de.adv990de.obd2.ui.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.location.LocationPermissions

/** Test-only selector for the GPS-speed [Switch] — its [ListItem] siblings have no other stable
 *  anchor to distinguish it from the other switches on this screen. Purely a semantics marker,
 *  no effect on production behavior. */
internal const val GPS_SPEED_SWITCH_TEST_TAG = "settings_gps_speed_switch"

/** Test-only selector for the crash-reports switch (only present in a build with a DSN). */
internal const val CRASH_REPORTS_SWITCH_TEST_TAG = "settings_crash_reports_switch"

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

/** Test-only selectors of the group "Alarme": the three switches, the four threshold rows ([ChoiceSetting]) and the hint row about blocked notifications. A segment is
 *  located by its text PLUS the tag of its row, like the segments of the two connection rows. */
internal const val ALARM_VOLTAGE_SWITCH_TEST_TAG = "settings_alarm_voltage_switch"
internal const val ALARM_COOLANT_SWITCH_TEST_TAG = "settings_alarm_coolant_switch"
internal const val ALARM_FAULTS_SWITCH_TEST_TAG = "settings_alarm_faults_switch"
internal const val ALARM_VOLTAGE_NOTICE_CHOICE_TEST_TAG = "settings_alarm_voltage_notice_choice"
internal const val ALARM_VOLTAGE_WARNING_CHOICE_TEST_TAG = "settings_alarm_voltage_warning_choice"
internal const val ALARM_COOLANT_NOTICE_CHOICE_TEST_TAG = "settings_alarm_coolant_notice_choice"
internal const val ALARM_COOLANT_WARNING_CHOICE_TEST_TAG = "settings_alarm_coolant_warning_choice"
internal const val ALARM_NOTIFICATION_HINT_TEST_TAG = "settings_alarm_notification_hint"

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
