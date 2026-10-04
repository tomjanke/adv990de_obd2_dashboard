package de.adv990de.obd2.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [parentTab]: which bottom-navigation tab owns a route. Every tab owns its own route, and every
 * sub-page (reached from a tab, never from the bar) belongs to the tab it was opened from — so the
 * bar can keep that tab highlighted instead of showing no selection at all.
 */
class ParentTabTest {

    @Test
    fun `each tab owns its own route`() {
        assertEquals(Screen.Dashboard, parentTab(Screen.Dashboard.route))
        assertEquals(Screen.Dtc, parentTab(Screen.Dtc.route))
        assertEquals(Screen.History, parentTab(Screen.History.route))
        assertEquals(Screen.Settings, parentTab(Screen.Settings.route))
    }

    @Test
    fun `the freeze frame page belongs to the DTC tab`() {
        assertEquals(Screen.Dtc, parentTab(Screen.FreezeFrame.route))
    }

    @Test
    fun `every settings sub-page belongs to the Settings tab`() {
        for (route in listOf(
            Screen.VehicleInfo.route,
            Screen.LoggingConfig.route,
            Screen.AppInfo.route,
            Screen.AppLogs.route,
            Screen.GearSettings.route,
        )) {
            assertEquals("route $route", Screen.Settings, parentTab(route))
        }
    }

    @Test
    fun `an unknown route or no route has no parent tab`() {
        assertNull(parentTab(null))
        assertNull(parentTab("somewhere_else"))
        assertNull(parentTab(""))
    }

    /** Exhaustive `when` over the sealed class: a new [Screen] does not compile here until someone has
     *  decided which tab owns it — so it cannot silently be left without a highlight. */
    private fun expectedParent(screen: Screen): Screen = when (screen) {
        Screen.Dashboard -> Screen.Dashboard
        Screen.Dtc, Screen.FreezeFrame -> Screen.Dtc
        Screen.History -> Screen.History
        Screen.Settings, Screen.VehicleInfo, Screen.LoggingConfig, Screen.AppInfo, Screen.AppLogs,
        Screen.GearSettings -> Screen.Settings
    }

    @Test
    fun `parentTab matches the documented parent of every screen`() {
        val all = listOf(
            Screen.Dashboard, Screen.Dtc, Screen.FreezeFrame, Screen.VehicleInfo, Screen.History,
            Screen.Settings, Screen.LoggingConfig, Screen.AppInfo, Screen.AppLogs, Screen.GearSettings,
        )
        for (screen in all) assertEquals("route ${screen.route}", expectedParent(screen), parentTab(screen.route))
    }
}
