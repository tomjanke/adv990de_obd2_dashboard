package de.adv990de.obd2.transfer

import de.adv990de.obd2.tripimport.tripImportRowLimit

enum class ExportFormat { PACKAGE, CSV }

/**
 * Wie die Fahrten eines Exports zur Grenze des Imports stehen. Der Export selbst kennt keine Grenze, der Import bricht bei mehr als [tripImportRowLimit] Datenzeilen ab (im Paket
 * wird der Teil „Fahrten“ dann unlesbar): ohne Hinweis schriebe die App eine Sicherung, die sie selbst nicht mehr einlesen kann.
 */
enum class TripExportSize {
    OK,

    /** Ab [TRIP_EXPORT_NOTICE_PERCENT] % der Grenze: noch einlesbar, die Vorwarnung. */
    NEAR_LIMIT,

    /** Mehr Zeilen, als der Import annimmt: die Fahrten dieser Datei liest die App nicht wieder ein. */
    OVER_LIMIT,
}

/** Ab diesem Anteil der Import-Grenze warnt die Exportseite vor (Entscheidung des Nutzers 2026-11-12: zwei Stufen, 80 % und 100 %). */
const val TRIP_EXPORT_NOTICE_PERCENT = 80

/**
 * Die Stufe fuer [rows] Datenzeilen gegen [limit] — **dieselbe Grenze und derselbe Vergleich wie der Import auf diesem Geraet** (`readTripCsv`: mehr als die Grenze bricht ab, genau die Grenze geht).
 * Gerechnet in Long: `rows * 100` liefe in Int ueber.
 */
fun tripExportSize(rows: Long, limit: Int = tripImportRowLimit()): TripExportSize = when {
    rows > limit -> TripExportSize.OVER_LIMIT
    rows * 100 >= limit.toLong() * TRIP_EXPORT_NOTICE_PERCENT -> TripExportSize.NEAR_LIMIT
    else -> TripExportSize.OK
}

/** Der Anteil der Grenze in ganzen Prozent, abgerundet (99,9 % sind 99 %: „100 %“ steht erst da, wenn die Grenze erreicht ist); nie unter 0. */
fun tripExportPercent(rows: Long, limit: Int = tripImportRowLimit()): Int = (rows.coerceAtLeast(0) * 100 / limit.coerceAtLeast(1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** Die Arten, die es auch als einzelne CSV-Datei gibt. */
val CSV_PARTS: Set<DataPart> = setOf(DataPart.TRIPS, DataPart.LOGBOOK, DataPart.APP_LOG)

/** Die eine Art, wenn genau sie (und nichts sonst) gewählt ist und es sie als CSV gibt (D6), sonst `null`. */
fun csvPartOf(parts: Set<DataPart>): DataPart? = parts.singleOrNull()?.takeIf { it in CSV_PARTS }

/** CSV nur, wenn [csvPartOf] eine Art liefert, sonst immer das Paket. */
fun effectiveFormat(parts: Set<DataPart>, wanted: ExportFormat): ExportFormat =
    if (wanted == ExportFormat.CSV && csvPartOf(parts) != null) ExportFormat.CSV else ExportFormat.PACKAGE
