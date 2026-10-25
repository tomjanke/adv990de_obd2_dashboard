package de.adv990de.obd2.ui.navigation

sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object FreezeFrame : Screen("freeze_frame")
    object VehicleInfo : Screen("vehicle_info")
    /** Der eine Reiter "Fahrten" (seit 1.33.0): die Fahrten und die Fehlercodes unter einem Umschalter. */
    object TripsAndFaults : Screen("trips_faults")
    object Settings : Screen("settings")
    object AppInfo : Screen("app_info")
    object AppLogs : Screen("app_logs")
    object GearSettings : Screen("gear_settings")
    object Licenses : Screen("licenses")

    /**
     * Die Eintragsseite des Logbuchs (neu oder bearbeiten), erreicht aus dem Bereich "Logbuch" des Reiters "Fahrten". Die Id des Eintrags ist ein optionales Argument:
     * `0` oder fehlend heisst neuer Eintrag. Die Route ist das **Muster** (`destination.route` liefert es), geoeffnet wird mit [routeFor].
     */
    object LogEntry : Screen("logbook_entry?id={id}") {
        fun routeFor(id: Long) = "logbook_entry?id=$id"
    }
}
