package de.adv990de.obd2.ui.navigation

import de.adv990de.obd2.logbook.LogType
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
        assertEquals(Screen.TripsAndFaults, parentTab(Screen.TripsAndFaults.route))
        assertEquals(Screen.Settings, parentTab(Screen.Settings.route))
    }

    @Test
    fun `the freeze frame page belongs to the Fahrten tab`() {
        assertEquals(Screen.TripsAndFaults, parentTab(Screen.FreezeFrame.route))
    }

    @Test
    fun `the logbook entry page belongs to the Fahrten tab - under the route pattern the NavController reports`() {
        // `destination.route` liefert das MUSTER mit dem Platzhalter, nicht die geoeffnete Adresse.
        assertEquals("logbook_entry?id={id}", Screen.LogEntry.route)
        assertEquals(Screen.TripsAndFaults, parentTab(Screen.LogEntry.route))
    }

    @Test
    fun `the address that opens an entry carries its id, 0 is a new entry`() {
        assertEquals("logbook_entry?id=0", Screen.LogEntry.routeFor(0))
        assertEquals("logbook_entry?id=42", Screen.LogEntry.routeFor(42))
    }

    @Test
    fun `the maintenance intervals page belongs to the settings tab`() {
        assertEquals("maintenance_intervals", Screen.MaintenanceIntervals.route)
        assertEquals(Screen.Settings, parentTab(Screen.MaintenanceIntervals.route))
    }

    @Test
    fun `the analysis page of the logbook belongs to the trips tab and is opened with the kind in the route`() {
        assertEquals("logbook_stats/{type}", Screen.LogbookStats.route)
        assertEquals(Screen.TripsAndFaults, parentTab(Screen.LogbookStats.route))
        assertEquals("logbook_stats/FUEL", Screen.LogbookStats.routeFor(LogType.FUEL))
        assertEquals("logbook_stats/OIL", Screen.LogbookStats.routeFor(LogType.OIL))
        assertEquals("logbook_stats/COOLANT", Screen.LogbookStats.routeFor(LogType.COOLANT))
    }

    @Test
    fun `the data transfer page belongs to the settings tab - under the route pattern the NavController reports`() {
        assertEquals("data_transfer?mode={mode}&part={part}&pick={pick}", Screen.DataTransfer.route)
        assertEquals(Screen.Settings, parentTab(Screen.DataTransfer.route))
    }

    @Test
    fun `every settings sub-page belongs to the Settings tab`() {
        for (route in listOf(
            Screen.VehicleInfo.route,
            Screen.AppInfo.route,
            Screen.AppLogs.route,
            Screen.GearSettings.route,
            Screen.Licenses.route,
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

    @Test
    fun `the routes of the two former tabs are gone - dtc and history belong to nobody`() {
        // Seit 1.33.0 teilen sich Fahrten und Fehlercodes einen Reiter; ein Rest der alten Routen darf keinen Reiter markieren.
        assertNull(parentTab("dtc"))
        assertNull(parentTab("history"))
    }

    /** Exhaustive `when` over the sealed class: a new [Screen] does not compile here until someone has
     *  decided which tab owns it — so it cannot silently be left without a highlight. */
    private fun expectedParent(screen: Screen): Screen = when (screen) {
        Screen.Dashboard -> Screen.Dashboard
        Screen.TripsAndFaults, Screen.FreezeFrame, Screen.LogEntry, Screen.LogbookStats -> Screen.TripsAndFaults
        Screen.Settings, Screen.VehicleInfo, Screen.AppInfo, Screen.AppLogs,
        Screen.GearSettings, Screen.Licenses, Screen.MaintenanceIntervals, Screen.DataTransfer -> Screen.Settings
    }

    @Test
    fun `parentTab matches the documented parent of every screen`() {
        val all = listOf(
            Screen.Dashboard, Screen.TripsAndFaults, Screen.FreezeFrame, Screen.VehicleInfo,
            Screen.Settings, Screen.AppInfo, Screen.AppLogs, Screen.GearSettings,
            Screen.Licenses, Screen.LogEntry, Screen.LogbookStats, Screen.MaintenanceIntervals, Screen.DataTransfer,
        )
        for (screen in all) assertEquals("route ${screen.route}", expectedParent(screen), parentTab(screen.route))
    }
}
