package de.adv990de.obd2.tripimport

import de.adv990de.obd2.data.CANCELLATION_CHECK_ROWS
import de.adv990de.obd2.data.CsvRecord
import de.adv990de.obd2.data.CsvRecordReader
import de.adv990de.obd2.obd.model.ValueSource
import de.adv990de.obd2.transfer.TripMeta
import java.io.Reader

/**
 * Der Fahrten-Import liest die CSV, die der Export schreibt (`SessionRepository.exportSessionsToCsv`, Spalten `timestamp,pid,value,name,source,session`), ohne
 * Android-Bezug. Gelesen wird **nach Spaltennamen**: `timestamp`, `pid` und `value` sind Pflicht; `source` fehlt in Dateien vor 1.25.0, `session` in Dateien vor
 * 1.34.0 (dort ist die ganze Datei eine Fahrt); `name` und jede unbekannte Spalte werden ignoriert (der Name folgt aus der PID).
 *
 * Die Datei kennt weder Beginn noch Ende einer Fahrt noch FIN oder Fehlercodes: Beginn und Ende einer Fahrt sind der kleinste und der groesste Zeitstempel ihrer
 * Zeilen, FIN und Fehlercodes bleiben leer (`null` = "nicht erfasst" — nie "keine gefunden"); aus einem Paket kommen sie von `trips_meta.csv` (`ParsedTrip.withMeta`).
 */
const val TRIP_COLUMN_TIMESTAMP = "timestamp"
const val TRIP_COLUMN_PID = "pid"
const val TRIP_COLUMN_VALUE = "value"
const val TRIP_COLUMN_SOURCE = "source"
const val TRIP_COLUMN_SESSION = "session"

/** Hoechstzahl der Datenzeilen (ohne Leerzeilen); mehr bricht den Import ab. Die Zeilen stehen kompakt im Speicher (rund 15 Byte je Zeile), nie als Text. */
const val MAX_TRIP_IMPORT_ROWS = 1_500_000

/** Hoechstzahl verschiedener PID-Namen in einer Datei: der Export kennt rund zwei Dutzend, mehr heisst, dass die Datei etwas anderes ist. */
const val MAX_TRIP_IMPORT_PIDS = 1_000

/** So viele abgelehnte Zeilen behaelt das Ergebnis einzeln (die Oberflaeche nennt hoechstens 20); gezaehlt werden alle. */
const val MAX_KEPT_REJECTED_ROWS = 100

/** Ein Zeitstempel vor dem 1.1.2000 (UTC) ist kein Zeitstempel einer Aufzeichnung. */
private const val EARLIEST_TIMESTAMP_MS = 946_684_800_000L

/** Ein Zeitstempel, der mehr als einen Tag nach "jetzt" liegt, ist keiner (Uhrenfehler der Quelle laesst sich nicht von Hand zurechtruecken). */
private const val MAX_FUTURE_MS = 24L * 60 * 60 * 1000

private const val MAX_PID_LENGTH = 32

private val REQUIRED_COLUMNS = listOf(TRIP_COLUMN_TIMESTAMP, TRIP_COLUMN_PID, TRIP_COLUMN_VALUE)

/** Die Datei ist kein Fahrten-Export dieser App oder zu gross fuer den Import ([kind] sagt, warum — die Oberflaeche hat je Art einen Text). */
class TripFileException(val kind: Kind, message: String) : IllegalArgumentException(message) {
    enum class Kind {
        /** Pflichtspalte fehlt, die Datei ist leer oder hat zu viele verschiedene PIDs. */
        NOT_AN_EXPORT,

        /** Mehr als [MAX_TRIP_IMPORT_ROWS] Datenzeilen. */
        TOO_MANY_ROWS,

        /** Die Zeilen einer Fahrt stehen nicht zusammen: so schreibt der Export nie, die Datei ist zusammengesetzt oder veraendert. */
        SPLIT_TRIP,
    }
}

/** Eine abgelehnte Zeile; [line] ist die Zeile der Datei (die Kopfzeile ist Zeile 1), [column] die erste Spalte, deren Wert nicht lesbar oder nicht plausibel war. */
data class RejectedRow(val line: Int, val column: String)

/**
 * Die Messwerte einer Fahrt, kompakt: je Zeile ein Zeitstempel, eine PID (als Nummer in der gemeinsamen Tabelle [pids]), ein Wert und eine Herkunft. Boxen und
 * Strings je Zeile waeren bei hunderttausenden Zeilen ein Vielfaches (und der Plan liegt im ViewModel, solange die Vorschau offen ist).
 */
class TripRows internal constructor(
    private val timestamps: LongArray,
    private val pidIndices: ShortArray,
    private val values: FloatArray,
    private val sources: ByteArray,
    private val pids: List<String>,
) {
    val size: Int get() = timestamps.size

    fun timestamp(i: Int): Long = timestamps[i]
    fun pid(i: Int): String = pids[pidIndices[i].toInt()]
    fun value(i: Int): Float = values[i]

    /** `ECU`, `GPS` oder `DERIVED`; `null`, wenn die Zeile keine Herkunft nennt (Dateien vor 1.25.0). */
    fun source(i: Int): String? = sources[i].toInt().let { if (it == NO_SOURCE) null else ValueSource.entries[it - 1].name }

    internal companion object {
        const val NO_SOURCE = 0
    }
}

/** Eine Fahrt der Datei: [sourceId] ist ihre Nummer in der Datei (die der Quell-App, nicht die der neuen), [startMs] und [endMs] der kleinste und groesste Zeitstempel. */
class ParsedTrip(
    val sourceId: String,
    val startMs: Long,
    val endMs: Long,
    val rows: TripRows,
    /** Die FIN aus `trips_meta.csv` eines Pakets; `null` = keine erfasst (die CSV allein kennt sie nicht). */
    val vin: String? = null,
    /** Die Fehlercodes bei Beginn im gespeicherten Format (`SessionDtcs.kt`): `null` = nicht erfasst, `""` = gelesen und keine Codes. */
    val dtcCodes: String? = null,
) {
    /** Dieselbe Fahrt mit den Angaben aus [meta]; ohne Angaben ([meta] `null`) bleibt alles unveraendert. */
    fun withMeta(meta: TripMeta?): ParsedTrip = if (meta == null) this else ParsedTrip(sourceId, startMs, endMs, rows, meta.vin, meta.dtcCodes)
}

/** Was die Datei enthaelt: die Fahrten in Dateireihenfolge, die abgelehnten Zeilen ([rejected] nur die ersten [MAX_KEPT_REJECTED_ROWS], [rejectedCount] alle) und die Zahl der gueltigen Zeilen. */
class ParsedTripFile(val trips: List<ParsedTrip>, val rejected: List<RejectedRow>, val rejectedCount: Int, val rowCount: Int)

/** Waechst, ohne je Zeile ein Objekt anzulegen. */
private class RowsBuilder(var startMs: Long, var endMs: Long) {
    var size = 0
    var timestamps = LongArray(INITIAL_CAPACITY)
    var pidIndices = ShortArray(INITIAL_CAPACITY)
    var values = FloatArray(INITIAL_CAPACITY)
    var sources = ByteArray(INITIAL_CAPACITY)

    fun add(timestamp: Long, pidIndex: Int, value: Float, source: Int) {
        if (size == timestamps.size) {
            val capacity = size * 2
            timestamps = timestamps.copyOf(capacity)
            pidIndices = pidIndices.copyOf(capacity)
            values = values.copyOf(capacity)
            sources = sources.copyOf(capacity)
        }
        timestamps[size] = timestamp
        pidIndices[size] = pidIndex.toShort()
        values[size] = value
        sources[size] = source.toByte()
        size++
        if (timestamp < startMs) startMs = timestamp
        if (timestamp > endMs) endMs = timestamp
    }

    fun build(pids: List<String>) = TripRows(timestamps.copyOf(size), pidIndices.copyOf(size), values.copyOf(size), sources.copyOf(size), pids)

    private companion object {
        const val INITIAL_CAPACITY = 256
    }
}

private fun isValidPid(pid: String): Boolean =
    pid.length in 1..MAX_PID_LENGTH && pid.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == '_' }

private fun CsvRecord.isBlank() = fields.all { it.isBlank() }

/**
 * Liest die Fahrten-CSV aus [reader] in einem Zug (der Text steht nie ganz im Speicher). Jede Datenzeile wird geprueft: ein nicht lesbarer oder unplausibler Wert
 * (Zeitstempel vor 2000 oder mehr als einen Tag nach [nowMs], Wert keine endliche Zahl, PID leer oder mit Sonderzeichen, unbekannte Herkunft, fehlende Fahrt-Nummer
 * bei vorhandener Spalte `session`) lehnt **die Zeile** ab ([RejectedRow]); die uebrigen bleiben. Leerzeilen werden uebersprungen. Eine fehlende Pflichtspalte, eine
 * leere Datei, ein offenes Anfuehrungszeichen, mehr als [maxRows] Zeilen, mehr als [maxPids] PID-Namen und eine Fahrt, deren Zeilen nicht zusammenstehen, brechen mit
 * [IllegalArgumentException] ab ([TripFileException] mit Art, ausser beim offenen Anfuehrungszeichen). [checkCancelled] wird alle [CANCELLATION_CHECK_ROWS] gelesenen Zeilen (auch
 * Leerzeilen) gerufen und darf werfen (Abbruch des Aufrufers).
 */
fun readTripCsv(
    reader: Reader,
    nowMs: Long,
    maxRows: Int = MAX_TRIP_IMPORT_ROWS,
    maxPids: Int = MAX_TRIP_IMPORT_PIDS,
    checkCancelled: () -> Unit = {},
): ParsedTripFile {
    val csv = CsvRecordReader(reader)
    var header: CsvRecord? = null
    while (header == null) {
        val record = csv.next() ?: break
        if (!record.isBlank()) header = record
    }
    if (header == null) throw TripFileException(TripFileException.Kind.NOT_AN_EXPORT, "The file is empty")
    val columns = HashMap<String, Int>()
    header.fields.forEachIndexed { i, name -> columns.putIfAbsent(name.trim(), i) }
    for (name in REQUIRED_COLUMNS) {
        if (name !in columns) throw TripFileException(TripFileException.Kind.NOT_AN_EXPORT, "Required column missing: $name")
    }
    val timestampColumn = columns.getValue(TRIP_COLUMN_TIMESTAMP)
    val pidColumn = columns.getValue(TRIP_COLUMN_PID)
    val valueColumn = columns.getValue(TRIP_COLUMN_VALUE)
    val sourceColumn = columns[TRIP_COLUMN_SOURCE]
    val sessionColumn = columns[TRIP_COLUMN_SESSION]

    val latestTimestamp = nowMs + MAX_FUTURE_MS
    val pidIndex = HashMap<String, Int>()
    val pids = ArrayList<String>()
    val trips = LinkedHashMap<String, RowsBuilder>()
    val rejected = ArrayList<RejectedRow>()
    var rejectedCount = 0
    var records = 0
    var dataRows = 0
    var validRows = 0
    var currentKey: String? = null
    var current: RowsBuilder? = null

    while (true) {
        val record = csv.next() ?: break
        // Auch Leerzeilen zaehlen fuer die Abbruchpruefung: eine Datei aus lauter Zeilenumbruechen soll sich abbrechen lassen.
        if (++records % CANCELLATION_CHECK_ROWS == 0) checkCancelled()
        if (record.isBlank()) continue
        dataRows++
        if (dataRows > maxRows) throw TripFileException(TripFileException.Kind.TOO_MANY_ROWS, "More than $maxRows rows")

        val fields = record.fields
        fun reject(column: String) {
            rejectedCount++
            if (rejected.size < MAX_KEPT_REJECTED_ROWS) rejected += RejectedRow(record.line, column)
        }

        val timestamp = fields.getOrNull(timestampColumn)?.trim()?.toLongOrNull()
        if (timestamp == null || timestamp < EARLIEST_TIMESTAMP_MS || timestamp > latestTimestamp) { reject(TRIP_COLUMN_TIMESTAMP); continue }
        val pid = fields.getOrNull(pidColumn)?.trim()
        if (pid == null || !isValidPid(pid)) { reject(TRIP_COLUMN_PID); continue }
        val value = fields.getOrNull(valueColumn)?.trim()?.toFloatOrNull()
        if (value == null || !value.isFinite()) { reject(TRIP_COLUMN_VALUE); continue }
        var source = TripRows.NO_SOURCE
        if (sourceColumn != null) {
            val text = fields.getOrNull(sourceColumn)?.trim().orEmpty()
            if (text.isNotEmpty()) {
                val found = ValueSource.entries.indexOfFirst { it.name == text }
                if (found < 0) { reject(TRIP_COLUMN_SOURCE); continue }
                source = found + 1
            }
        }
        var key = ""
        if (sessionColumn != null) {
            key = fields.getOrNull(sessionColumn)?.trim().orEmpty()
            if (key.isEmpty()) { reject(TRIP_COLUMN_SESSION); continue }
        }

        var index = pidIndex[pid]
        if (index == null) {
            if (pids.size >= maxPids) throw TripFileException(TripFileException.Kind.NOT_AN_EXPORT, "More than $maxPids different PIDs")
            index = pids.size
            pidIndex[pid] = index
            pids += pid
        }
        if (key != currentKey) {
            if (key in trips) throw TripFileException(TripFileException.Kind.SPLIT_TRIP, "The rows of trip $key are not together")
            current = RowsBuilder(timestamp, timestamp).also { trips[key] = it }
            currentKey = key
        }
        current!!.add(timestamp, index, value, source)
        validRows++
    }

    val sharedPids = pids.toList()
    return ParsedTripFile(
        trips = trips.map { (id, rows) -> ParsedTrip(id, rows.startMs, rows.endMs, rows.build(sharedPids)) },
        rejected = rejected,
        rejectedCount = rejectedCount,
        rowCount = validRows,
    )
}
