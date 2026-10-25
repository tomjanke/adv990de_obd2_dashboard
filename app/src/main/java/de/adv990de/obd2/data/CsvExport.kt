package de.adv990de.obd2.data

import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.PidRegistry
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Die reinen Teile des CSV-Exports (QS C: DAT-06/07/09 und die PID-Spalte). `SessionRepository.exportToCsv` setzt
 * sie zusammen; hier stehen sie ohne Android-Bezug, damit sie direkt testbar sind.
 */

/** Exportierte Dateien, die aelter sind, werden aufgeraeumt. Der Empfaenger kopiert die Datei beim Teilen. */
internal const val EXPORT_MAX_AGE_MS: Long = 24L * 60 * 60 * 1000

/** Der Unterordner des Caches, in dem alle Exporte (Fahrten und App-Log) liegen; `res/xml/file_paths.xml` gibt genau ihn fuer das Teilen frei. */
internal const val EXPORT_CACHE_DIR = "exports"

/** Ein Export prueft nach so vielen Zeilen, ob der Aufrufer abgebrochen hat. */
internal const val CANCELLATION_CHECK_ROWS = 2_000

/**
 * Die Abfrage des Exports. `ORDER BY id` statt `ORDER BY timestamp`: Der Index auf `sessionId` liefert die Zeilen
 * eines Laufs schon in Einfuegereihenfolge (SQLite haengt die rowid an jeden Indexeintrag an), also ohne Sortierlauf —
 * `ORDER BY timestamp` zwang SQLite, alle Zeilen der Fahrt zu lesen und zu sortieren, bevor die erste geschrieben
 * wurde, was der Doku "konstanter Speicher" widersprach (DAT-09). Die Reihenfolge der Aufzeichnung ist zudem die
 * wahrheitsgemaesse: ein Uhrensprung waehrend der Fahrt wuerde sonst Zeilen umsortieren.
 */
internal const val EXPORT_QUERY_SQL =
    "SELECT timestamp, pidId, value, source FROM sensor_readings WHERE sessionId = ? ORDER BY id ASC"

/**
 * Ein CSV-Feld nach RFC 4180: nur bei Komma, Anfuehrungszeichen oder Zeilenumbruch in Anfuehrungszeichen, ein
 * Anfuehrungszeichen darin wird verdoppelt. Bekannte Grenze: Excel macht aus einer Spalte wie `0105` beim
 * Doppelklick-Oeffnen die Zahl 105 — Anfuehrungszeichen verhindern das nicht; deshalb gibt der Export zusaetzlich den
 * Klartext-Namen des PIDs aus.
 */
internal fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\""
    else value

/**
 * Der stabile Klartext-Name einer PID-Spalte: OBD-PIDs nennt die [PidRegistry], berechnete Werte (der Gang) [DerivedPids]; eine unbekannte Id
 * (Altdaten, spaeter entfernte PIDs) hat einen leeren Namen.
 */
internal fun csvPidName(pidId: String): String =
    (PidRegistry.all[pidId] ?: DerivedPids.all[pidId])?.name.orEmpty()

/**
 * Die Spalten des Exports. Die ersten fuenf sind unveraendert (ein Parser, der nach Position liest, laeuft weiter); `session` — die Fahrt-ID — steht
 * hinten, damit sich die Fahrten in der Datei trennen lassen, wenn mehrere in einer stehen (seit 1.34.0, auch beim Export einer einzelnen Fahrt:
 * gleiches Format, Dateien lassen sich zusammenfuehren).
 */
internal val EXPORT_COLUMNS = arrayOf("timestamp", "pid", "value", "name", "source", "session")

/** Eine CSV-Zeile samt abschliessendem Zeilenumbruch. */
internal fun csvRow(vararg fields: String): String = fields.joinToString(",") { csvField(it) } + "\n"

private val EXPORT_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)

/**
 * `session_<Fahrt>_<UTC-Zeit auf die Millisekunde>.csv`. Vorher hiess die Datei fest `session_<Fahrt>.csv`: ein
 * zweiter Export derselben Fahrt loeschte die Datei, waehrend die erste Weitergabe sie noch las (DAT-06).
 */
internal fun exportFileName(sessionId: Long, nowMillis: Long): String =
    "session_${sessionId}_${EXPORT_TIME_FORMAT.format(Instant.ofEpochMilli(nowMillis))}.csv"

/** `session_all_<UTC-Zeit auf die Millisekunde>.csv`: der Export mehrerer Fahrten in EINER Datei. Der Name passt zum Aufraeumen alter Exporte (`session_*.csv`). */
internal fun exportAllFileName(nowMillis: Long): String =
    "session_all_${EXPORT_TIME_FORMAT.format(Instant.ofEpochMilli(nowMillis))}.csv"

/** Der Dateiname fuer die Fahrten [sessionIds]: eine einzelne Fahrt heisst wie bisher nach ihrer ID, mehrere tragen den gemeinsamen Namen. */
internal fun exportFileNameFor(sessionIds: List<Long>, nowMillis: Long): String =
    if (sessionIds.size == 1) exportFileName(sessionIds.single(), nowMillis) else exportAllFileName(nowMillis)

/** Wie [createExportFile] fuer eine einzelne Fahrt (Name nach ihrer ID). */
internal fun createExportFile(dir: File, sessionId: Long, nowMillis: Long): File =
    createExportFile(dir, exportFileName(sessionId, nowMillis))

/**
 * Legt die (leere) Exportdatei an und garantiert, dass sie neu ist: Gibt es den Namen schon (zwei Exporte in
 * derselben Millisekunde), kommt `-1`, `-2` ... davor `.csv`. `createNewFile` ist atomar — kein Pruefen-dann-Anlegen.
 */
internal fun createExportFile(dir: File, name: String): File {
    dir.mkdirs()
    var attempt = 0
    while (true) {
        val candidate = File(dir, if (attempt == 0) name else name.removeSuffix(".csv") + "-$attempt.csv")
        if (candidate.createNewFile()) return candidate
        attempt++
    }
}

private val EXPORT_FILE = Regex("""(?:session|applog|logbook)_.*\.csv""")

/**
 * Loescht Exportdateien in [dir], die aelter als [maxAgeMs] sind (DAT-07: sie wurden nie geloescht). Angefasst
 * werden nur Dateien, die so heissen wie unsere Exporte; Verzeichnisse und alles andere bleiben. Ein Fehler bei
 * einer Datei stoppt die uebrigen nicht. Liefert die Zahl der geloeschten Dateien.
 */
internal fun deleteStaleExports(dir: File, nowMillis: Long, maxAgeMs: Long = EXPORT_MAX_AGE_MS): Int {
    val files = dir.listFiles() ?: return 0
    var deleted = 0
    for (file in files) {
        if (!file.isFile || !EXPORT_FILE.matches(file.name)) continue
        if (nowMillis - file.lastModified() > maxAgeMs && file.delete()) deleted++
    }
    return deleted
}
