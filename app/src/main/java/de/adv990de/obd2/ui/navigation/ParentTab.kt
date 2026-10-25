package de.adv990de.obd2.ui.navigation

/**
 * The bottom-navigation tab that owns [route]: each tab owns its own route, and every sub-page
 * belongs to the tab it is opened from (freeze frame → Fahrten; vehicle info, logging config, gear
 * settings, app info, app logs and the licences page → Settings). `BottomNav` marks that tab as selected, so a
 * sub-page no longer leaves the whole bar without a highlight. Unknown or missing routes have no
 * parent. Pure, so the mapping is JVM-testable without a NavController.
 */
internal fun parentTab(route: String?): Screen? = when (route) {
    Screen.Dashboard.route -> Screen.Dashboard
    Screen.TripsAndFaults.route, Screen.FreezeFrame.route, Screen.LogEntry.route -> Screen.TripsAndFaults
    Screen.Settings.route,
    Screen.VehicleInfo.route,
    Screen.AppInfo.route,
    Screen.AppLogs.route,
    Screen.GearSettings.route,
    Screen.Licenses.route -> Screen.Settings
    else -> null
}
