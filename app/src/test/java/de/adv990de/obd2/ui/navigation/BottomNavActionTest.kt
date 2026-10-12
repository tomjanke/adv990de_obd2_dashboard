package de.adv990de.obd2.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [bottomNavAction]: what a tap on a bottom-navigation tab does, given the route the user is on.
 *
 * The bug this pins down (found on the Pixel, 2026-10-12): on a sub-page (App-Info, Getriebe, ...) the
 * Settings tab is highlighted, and tapping it again did nothing — `navigate(..., restoreState = true)`
 * restored the very back stack the tap had just saved, so the user could not get back to the tab's
 * start page. A re-tap on the highlighted tab must go to the tab's start page.
 */
class BottomNavActionTest {

    @Test
    fun `tapping the tab you are on at its start page does nothing`() {
        for (tab in listOf(Screen.Dashboard, Screen.Dtc, Screen.History, Screen.Settings)) {
            assertEquals("tab ${tab.route}", BottomNavAction.Ignore, bottomNavAction(tab.route, tab))
        }
    }

    @Test
    fun `tapping the Settings tab on a settings sub-page returns to the Settings start page`() {
        for (route in listOf(
            Screen.VehicleInfo.route,
            Screen.LoggingConfig.route,
            Screen.AppInfo.route,
            Screen.AppLogs.route,
            Screen.GearSettings.route,
            Screen.Licenses.route,
        )) {
            assertEquals("from $route", BottomNavAction.PopToTabRoot, bottomNavAction(route, Screen.Settings))
        }
    }

    @Test
    fun `tapping the DTC tab on the freeze frame page returns to the DTC start page`() {
        assertEquals(BottomNavAction.PopToTabRoot, bottomNavAction(Screen.FreezeFrame.route, Screen.Dtc))
    }

    @Test
    fun `tapping another tab switches tabs - from a start page and from a sub-page`() {
        assertEquals(BottomNavAction.SwitchTab, bottomNavAction(Screen.Dashboard.route, Screen.History))
        assertEquals(BottomNavAction.SwitchTab, bottomNavAction(Screen.AppInfo.route, Screen.Dashboard))
        assertEquals(BottomNavAction.SwitchTab, bottomNavAction(Screen.FreezeFrame.route, Screen.Settings))
    }

    @Test
    fun `an unknown or missing route has no tab - every tap switches`() {
        assertEquals(BottomNavAction.SwitchTab, bottomNavAction(null, Screen.Dashboard))
        assertEquals(BottomNavAction.SwitchTab, bottomNavAction("somewhere_else", Screen.Settings))
    }
}
