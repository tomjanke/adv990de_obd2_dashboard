package de.adv990de.obd2.transfer

/**
 * Die Arten von Daten, die „Daten sichern und wiederherstellen“ kennt. [packageEntry] ist der Dateiname im Paket — **ein Vertrag ab dem ersten Release**, nie umbenennen.
 * [importable] = false: der App-Log lässt sich nur ausgeben, nie einlesen.
 */
enum class DataPart(val importable: Boolean, val packageEntry: String) {
    TRIPS(true, "trips.csv"),
    TRACKS(true, "tracks.gpx"),
    LOGBOOK(true, "logbook.csv"),
    SETTINGS(true, "settings.json"),
    COUNTERS(true, "counters.json"),
    DTC_HISTORY(true, "dtc_history.csv"),
    APP_LOG(false, "applog.csv");

    companion object {
        /** Reihenfolge beim Einlesen (D9): der Kilometer-Anker der Zähler hängt am Logbuch. */
        val IMPORT_ORDER: List<DataPart> = listOf(LOGBOOK, TRIPS, TRACKS, DTC_HISTORY, SETTINGS, COUNTERS)

        /**
         * Teile, die diese Version ausgeben kann (die sechs alten); die Exportseite zeigt nur sie. Die Tracks lassen sich schon einlesen ([IMPORT_AVAILABLE]), aber noch nicht
         * ausgeben (F8 Aufgabe 10): erst dann gehören sie hierher.
         */
        val AVAILABLE: Set<DataPart> = entries.toSet() - TRACKS

        /** Teile, die diese Version einlesen kann: jeder einlesbare Teil. */
        val IMPORT_AVAILABLE: Set<DataPart> = entries.filter { it.importable }.toSet()

        /** Vorgewählt von der Einstellungsseite (D5): alles Verfügbare außer dem App-Log. */
        val DEFAULT_EXPORT: Set<DataPart> get() = AVAILABLE - APP_LOG
    }
}
