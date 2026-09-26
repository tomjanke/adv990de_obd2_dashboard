package de.adv990de.obd2.ui.settings

import android.os.Build
import de.adv990de.obd2.data.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for the pure decision functions extracted out of [SettingsScreen]'s GPS-speed
 * switch and background-location escalation — the same extract-pure-function convention as
 * `DashboardComponentsTest.kt` (which covers `DashboardComponents.kt`'s internal decision
 * functions). Both functions are plain booleans/enums in, no Context/Compose/Android framework
 * dependency, so they need no Robolectric or instrumentation to exercise every branch.
 *
 * What these two functions do NOT cover — deliberately, and documented instead of silently
 * skipped: whether `escalateToBackgroundLocationIfNeeded()` actually wires
 * `shouldEscalateToBackgroundLocation()`'s true branch to `displayVm.setBackgroundLocationPrompted(true)`
 * + `backgroundLocationLauncher.launch(...)`, and whether the GPS-speed switch's
 * `REQUEST_PERMISSION` outcome actually wires to `locationPermissionLauncher.launch(...)`. Driving
 * either real trigger through `SettingsScreenTest` (androidTest) would fire a real system
 * permission dialog — this project's only precedent for permission-gated instrumentation tests
 * (`ObdForegroundServiceTest`, `GearRatioFieldUiTest`) is `GrantPermissionRule`, which only
 * *pre-grants* permissions before a test starts; there is no established pattern here for
 * driving or dismissing a live system permission dialog mid-test, and inventing one (e.g. via
 * UiAutomator) was explicitly out of scope. The androidTest suite instead covers the "does NOT
 * escalate / does NOT request" branches, which are safe to exercise for real (see
 * `SettingsScreenTest` in `androidTest`).
 */
class SettingsScreenTest {

    // ── determineGpsSpeedSwitchAction ────────────────────────────────────────

    @Test
    fun `switching off always sets useGpsSpeed false, regardless of permission grant`() {
        assertEquals(GpsSpeedSwitchAction.SET_OFF, determineGpsSpeedSwitchAction(wanted = false, granted = false))
        assertEquals(GpsSpeedSwitchAction.SET_OFF, determineGpsSpeedSwitchAction(wanted = false, granted = true))
    }

    @Test
    fun `switching on with fine location already granted sets useGpsSpeed true directly`() {
        assertEquals(GpsSpeedSwitchAction.SET_ON, determineGpsSpeedSwitchAction(wanted = true, granted = true))
    }

    @Test
    fun `switching on without fine location granted requests the permission instead`() {
        assertEquals(
            GpsSpeedSwitchAction.REQUEST_PERMISSION,
            determineGpsSpeedSwitchAction(wanted = true, granted = false)
        )
    }

    // ── shouldEscalateToBackgroundLocation ───────────────────────────────────

    @Test
    fun `escalates when not yet prompted, not yet granted, and API is 29+`() {
        assertEquals(
            true,
            shouldEscalateToBackgroundLocation(
                alreadyPrompted = false,
                sdkInt = Build.VERSION_CODES.Q,
                backgroundLocationGranted = false
            )
        )
    }

    @Test
    fun `does not escalate a second time once already prompted`() {
        // Short-circuits on alreadyPrompted before ever consulting the grant state — this case
        // stays false even though backgroundLocationGranted=false would otherwise call for it.
        assertEquals(
            false,
            shouldEscalateToBackgroundLocation(
                alreadyPrompted = true,
                sdkInt = Build.VERSION_CODES.Q,
                backgroundLocationGranted = false
            )
        )
    }

    @Test
    fun `does not escalate once background location is already granted`() {
        assertEquals(
            false,
            shouldEscalateToBackgroundLocation(
                alreadyPrompted = false,
                sdkInt = Build.VERSION_CODES.Q,
                backgroundLocationGranted = true
            )
        )
    }

    @Test
    fun `does not escalate below API 29, regardless of prompted or grant state`() {
        assertEquals(
            false,
            shouldEscalateToBackgroundLocation(
                alreadyPrompted = false,
                sdkInt = Build.VERSION_CODES.P,
                backgroundLocationGranted = false
            )
        )
    }

    // ── parseWatchdogTimeoutSeconds (OBD watchdog timeout field validation) ──

    @Test
    fun `a whole number of at least 10 seconds parses to itself`() {
        assertEquals(10, parseWatchdogTimeoutSeconds("10"))  // the minimum itself is allowed
        assertEquals(20, parseWatchdogTimeoutSeconds("20"))
        assertEquals(300, parseWatchdogTimeoutSeconds("300"))
    }

    @Test
    fun `a value below 10 seconds is rejected`() {
        // Any positive number used to pass; at 1 s the watchdog would force a reconnect on
        // ordinary timeout noise (a single PID timeout alone blocks for 5 s).
        assertNull(parseWatchdogTimeoutSeconds("9"))
        assertNull(parseWatchdogTimeoutSeconds("1"))
    }

    @Test
    fun `zero is rejected`() {
        assertNull(parseWatchdogTimeoutSeconds("0"))
    }

    @Test
    fun `a negative number is rejected`() {
        assertNull(parseWatchdogTimeoutSeconds("-5"))
    }

    @Test
    fun `a decimal or non-numeric value is rejected`() {
        assertNull(parseWatchdogTimeoutSeconds("12.5"))
        assertNull(parseWatchdogTimeoutSeconds("abc"))
        assertNull(parseWatchdogTimeoutSeconds(""))
    }

    @Test
    fun `the default timeout passes its own validation`() {
        // Otherwise the field would flag the untouched default as invalid on first open.
        val default = AppPreferences.DEFAULT_OBD_WATCHDOG_TIMEOUT_SECONDS
        assertEquals(default, parseWatchdogTimeoutSeconds(default.toString()))
    }
}
