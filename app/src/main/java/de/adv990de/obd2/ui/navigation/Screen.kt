package de.adv990de.obd2.ui.navigation

import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.ui.transfer.TransferMode

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

    /** Die Unterseite der Einstellungen (Gruppe "Fahrzeug") fuer die Wartungsintervalle des Logbuchs (Phase 3). */
    object MaintenanceIntervals : Screen("maintenance_intervals")

    /**
     * Die Eintragsseite des Logbuchs (neu oder bearbeiten), erreicht aus dem Bereich "Logbuch" des Reiters "Fahrten". Die Id des Eintrags ist ein optionales Argument:
     * `0` oder fehlend heisst neuer Eintrag. Die Route ist das **Muster** (`destination.route` liefert es), geoeffnet wird mit [routeFor].
     */
    object LogEntry : Screen("logbook_entry?id={id}") {
        fun routeFor(id: Long) = "logbook_entry?id=$id"
    }

    /**
     * Die Detailseite der Auswertung einer Art (Tanken, Oel oder Kuehlfluessigkeit), erreicht ueber eine Karte des Logbuchs. Die Art steht in der Route: [routeFor] setzt den Namen
     * des [LogType] ein, `route` ist das Muster (`destination.route`).
     */
    object LogbookStats : Screen("logbook_stats/{type}") {
        fun routeFor(type: LogType) = "logbook_stats/${type.name}"
    }

    /**
     * Die Seite „Daten sichern und wiederherstellen“ (Einstellungen → Info & Wartung) und ihre Abkuerzungen aus den Symbolen der Listen. Die Argumente sind alle optional:
     * `mode` (`export`/`import`), `part` (der Name einer [DataPart]-Art, leer = keine) und `pick` (der Dateidialog des Imports oeffnet sofort). Die Route ist das **Muster**
     * (`destination.route`), geoeffnet wird mit [routeFor].
     */
    object DataTransfer : Screen("data_transfer?mode={mode}&part={part}&pick={pick}") {
        fun routeFor(mode: TransferMode = TransferMode.EXPORT, part: DataPart? = null, pick: Boolean = false): String =
            "data_transfer?mode=${mode.name.lowercase()}&part=${part?.name.orEmpty()}&pick=$pick"
    }
}
