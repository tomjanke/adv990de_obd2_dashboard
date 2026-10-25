package de.adv990de.obd2.logbook

import java.time.LocalDate

/** Warum eine Zeile des Imports abgelehnt wurde: ein Wert war nicht lesbar ([BadValue], mit dem Spaltennamen) oder eine Regel des Formulars verletzt ([Rule]). */
sealed interface ImportReason {
    data class BadValue(val column: String) : ImportReason
    data class Rule(val error: LogIssue.Error) : ImportReason
}

/** Eine abgelehnte Zeile; [line] ist die Zeile der Datei (die Kopfzeile ist Zeile 1). */
data class RowError(val line: Int, val reasons: List<ImportReason>)

/** Eine gelesene, gueltige Zeile mit ihren Warnungen (ohne Bezug zu anderen Eintraegen, also Preis je Liter). */
data class ParsedRow(val line: Int, val entry: LogEntry, val warnings: List<LogIssue.Warning>)

data class ParsedLogbook(val entries: List<ParsedRow>, val errors: List<RowError>)

/**
 * Was ein Import tun wuerde, bevor er etwas schreibt (die Vorschau). [toInsert] sind die neuen Eintraege in Dateireihenfolge, [duplicates] die
 * uebersprungenen, [rejected] die abgelehnten Zeilen, [warnings] die Zahl der neuen Eintraege mit mindestens einer Warnung.
 */
data class ImportPlan(val toInsert: List<LogEntry>, val duplicates: Int, val rejected: List<RowError>, val warnings: Int)

/**
 * Gleich ist, was Art, Datum, Stand, Menge und Fabrikat teilt (Notiz, Kosten und `id` zaehlen nicht): ein zweiter Import derselben Datei aendert nichts.
 * Das Fabrikat gehoert dazu, weil vorne und hinten am selben Tag und Stand gewechselt werden koennen.
 */
private data class DuplicateKey(val type: LogType, val date: LocalDate, val odometerKm: Int, val amountMl: Int?, val brand: String?)

private fun LogEntry.duplicateKey() = DuplicateKey(type, date, odometerKm, amountMl, brand)

/**
 * Macht aus der gelesenen Datei und den vorhandenen Eintraegen den [ImportPlan]. Ein Eintrag, der einem vorhandenen oder einer frueheren Zeile
 * derselben Datei gleicht, wird uebersprungen (die erste Zeile bleibt). Die Warnung "Stand passt nicht zum Datum" gilt gegen alle vorhandenen und
 * neuen Eintraege zusammen (in O(n log n), [findOutOfOrder]); gezaehlt werden nur die neuen.
 */
fun planImport(parsed: ParsedLogbook, existing: List<LogEntry>): ImportPlan {
    val seen = HashSet<DuplicateKey>()
    existing.forEach { seen += it.duplicateKey() }
    val fresh = ArrayList<ParsedRow>()
    var duplicates = 0
    for (row in parsed.entries) {
        if (seen.add(row.entry.duplicateKey())) fresh += row else duplicates++
    }
    val outOfOrder = findOutOfOrder(existing + fresh.map { it.entry })
    val warnings = fresh.indices.count { i -> fresh[i].warnings.isNotEmpty() || outOfOrder[existing.size + i] }
    return ImportPlan(fresh.map { it.entry }, duplicates, parsed.errors, warnings)
}
