package de.adv990de.obd2.transfer

/**
 * Die Arten von Daten, die „Daten sichern und wiederherstellen“ kennt. [packageEntry] ist der Dateiname im Paket — **ein Vertrag ab dem ersten Release**, nie umbenennen.
 * [importable] = false: der App-Log lässt sich nur ausgeben, nie einlesen.
 */
enum class DataPart(val importable: Boolean, val packageEntry: String) {
    TRIPS(true, "trips.csv"),
    LOGBOOK(true, "logbook.csv"),
    SETTINGS(true, "settings.json"),
    COUNTERS(true, "counters.json"),
    DTC_HISTORY(true, "dtc_history.csv"),
    APP_LOG(false, "applog.csv");

    companion object {
        /** Reihenfolge beim Einlesen (D9): der Kilometer-Anker der Zähler hängt am Logbuch. */
        val IMPORT_ORDER: List<DataPart> = listOf(LOGBOOK, TRIPS, DTC_HISTORY, SETTINGS, COUNTERS)

        /** Teile, die diese Version kann (seit Phase 3 alle sechs); die Oberfläche zeigt nur sie. */
        val AVAILABLE: Set<DataPart> = entries.toSet()

        /** Vorgewählt von der Einstellungsseite (D5): alles Verfügbare außer dem App-Log. */
        val DEFAULT_EXPORT: Set<DataPart> get() = AVAILABLE - APP_LOG
    }
}
