package de.adv990de.obd2.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for the pure decision functions extracted out of [SettingsScreen]'s GPS-speed
 * switch and the weather location request — the same extract-pure-function convention as
 * `DashboardComponentsTest.kt` (which covers `DashboardComponents.kt`'s internal decision
 * functions). Both are plain booleans/enums in, no Context/Compose/Android framework
 * dependency, so they need no Robolectric or instrumentation to exercise every branch.
 *
 * What these functions do NOT cover — deliberately, and documented instead of silently
 * skipped: whether the GPS-speed switch's `REQUEST_PERMISSION` outcome actually wires to
 * `locationPermissionLauncher.launch(...)`. Driving the real trigger through `SettingsScreenTest`
 * (androidTest) would fire a real system permission dialog — this project's only precedent for
 * permission-gated instrumentation tests (`ObdForegroundServiceDeviceTest`, `GearRatioFieldUiTest`)
 * is `GrantPermissionRule`, which only *pre-grants* permissions before a test starts; there is no
 * established pattern here for driving or dismissing a live system permission dialog mid-test.
 * (Since 2026-11-10 there is no background-location request any more, so no second dialog exists.)
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

    // ── shouldRequestLocationForWeather (hideNavOnDrive switch) ──────────────
    //
    // Unlike the GPS-speed switch, the answer never gates the switch itself: hideNavOnDrive always
    // follows the toggle, the permission request is only an opportunistic side request for the
    // outside temperature shown in place of the hidden nav bar.

    @Test
    fun `turning hideNavOnDrive on without any location permission requests it`() {
        assertEquals(
            true,
            shouldRequestLocationForWeather(
                alreadyPrompted = false, turningOn = true, fineGranted = false, coarseGranted = false
            )
        )
    }

    @Test
    fun `turning hideNavOnDrive on with fine location already granted requests nothing`() {
        assertEquals(
            false,
            shouldRequestLocationForWeather(
                alreadyPrompted = false, turningOn = true, fineGranted = true, coarseGranted = false
            )
        )
    }

    @Test
    fun `turning hideNavOnDrive on with only coarse location granted requests nothing`() {
        // Approximate location is enough for weather — asking again would only nag the user for
        // "precise", which the weather lookup does not need (coordinates are rounded anyway).
        assertEquals(
            false,
            shouldRequestLocationForWeather(
                alreadyPrompted = false, turningOn = true, fineGranted = false, coarseGranted = true
            )
        )
    }

    @Test
    fun `turning hideNavOnDrive off never requests location, regardless of grant state`() {
        assertEquals(
            false,
            shouldRequestLocationForWeather(
                alreadyPrompted = false, turningOn = false, fineGranted = false, coarseGranted = false
            )
        )
        assertEquals(
            false,
            shouldRequestLocationForWeather(
                alreadyPrompted = false, turningOn = false, fineGranted = true, coarseGranted = true
            )
        )
    }

    @Test
    fun `does not request a second time once already prompted`() {
        // Short-circuits on alreadyPrompted before ever consulting turningOn/grant state.
        // Stays false even though turningOn=true, fineGranted=false, coarseGranted=false would
        // otherwise call for it.
        assertEquals(
            false,
            shouldRequestLocationForWeather(
                alreadyPrompted = true, turningOn = true, fineGranted = false, coarseGranted = false
            )
        )
    }
}
