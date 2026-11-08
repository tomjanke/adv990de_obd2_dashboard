package de.adv990de.obd2.transfer

import de.adv990de.obd2.data.CsvRecordReader
import de.adv990de.obd2.data.DtcHistoryEntry
import de.adv990de.obd2.data.csvRow
import de.adv990de.obd2.data.isValidDtcCode
import java.io.Reader

/**
 * `dtc_history.csv`, die lokale Fehlerhistorie. Die Spalten sind ein Vertrag des Formats 1 (nie umbenennen): `code`, `first_seen` und `last_seen` (Millisekunden seit 1970 UTC)
 * und `last_was_pending` (`1`/`0`).
 */
val DTC_HISTORY_COLUMNS = arrayOf("code", "first_seen", "last_seen", "last_was_pending")

fun dtcHistoryCsvHeader(): String = csvRow(*DTC_HISTORY_COLUMNS)

/** Eine Zeile mit Zeilenumbruch; `last_was_pending` als `1`/`0`. */
fun dtcHistoryCsvLine(entry: DtcHistoryEntry): String = csvRow(entry.code, entry.firstSeen.toString(), entry.lastSeen.toString(), if (entry.lastWasPending) "1" else "0")

/** Was die Datei enthaelt (eine Zeile je Code, doppelte schon zusammengefuehrt) und wie viele Zeilen abgelehnt wurden. */
class ParsedDtcHistory(val entries: List<DtcHistoryEntry>, val rejected: Int)

/** Ein Zeitstempel vor dem 1.1.2000 (UTC) ist keiner einer Aufzeichnung (wie in `tripimport/TripCsv.kt`). */
private const val EARLIEST_TIMESTAMP_MS = 946_684_800_000L

/** Mehr als einen Tag nach "jetzt" ist kein Zeitstempel (Uhrenfehler der Quelle). */
private const val MAX_FUTURE_MS = 24L * 60 * 60 * 1000

private const val COLUMN_CODE = "code"
private const val COLUMN_FIRST = "first_seen"
private const val COLUMN_LAST = "last_seen"
private const val COLUMN_PENDING = "last_was_pending"

/** Die Obergrenze der Tabelle (`DtcHistoryRepository`); die Vorschau rechnet damit, was ein Import ueber die Grenze schiebt. */
const val DTC_HISTORY_CAP = 200

private fun parseTime(text: String?, latest: Long): Long? {
    val value = text?.trim()?.toLongOrNull() ?: return null
    return value.takeIf { it in EARLIEST_TIMESTAMP_MS..latest }
}

private fun parsePending(text: String?): Boolean? = when (text?.trim()?.lowercase()) {
    "1", "true" -> true
    "0", "false" -> false
    else -> null
}

/**
 * Liest `dtc_history.csv` nach Spaltennamen; [IllegalArgumentException], wenn die Spalte `code` fehlt (oder die Datei leer ist). Eine Zeile wird **abgelehnt** und gezaehlt bei: Code nicht
 * `[PCBU][0-9A-F]{4}` ([isValidDtcCode]), Zeitstempel kein `Long`, vor 2000-01-01 oder mehr als einen Tag nach [nowMs], `first_seen` nach `last_seen`, `last_was_pending` nicht
 * `1`/`0`/`true`/`false` (auch fehlend). Doppelte Codes in der Datei werden mit [mergeDtcHistoryEntry] zusammengefuehrt (die frueher stehende Zeile gilt als vorhanden).
 */
fun parseDtcHistoryCsv(reader: Reader, nowMs: Long): ParsedDtcHistory {
    val csv = CsvRecordReader(reader)
    var header: List<String>? = null
    while (header == null) {
        val record = csv.next() ?: break
        if (record.fields.any { it.isNotBlank() }) header = record.fields
    }
    if (header == null) throw IllegalArgumentException("The file is empty")
    val columns = HashMap<String, Int>()
    header.forEachIndexed { i, name -> columns.putIfAbsent(name.trim(), i) }
    val codeColumn = columns[COLUMN_CODE] ?: throw IllegalArgumentException("Required column missing: $COLUMN_CODE")
    val firstColumn = columns[COLUMN_FIRST]
    val lastColumn = columns[COLUMN_LAST]
    val pendingColumn = columns[COLUMN_PENDING]
    val latest = nowMs + MAX_FUTURE_MS

    val byCode = LinkedHashMap<String, DtcHistoryEntry>()
    var rejected = 0
    while (true) {
        val record = csv.next() ?: break
        val fields = record.fields
        if (fields.all { it.isBlank() }) continue
        val code = fields.getOrNull(codeColumn)?.trim().orEmpty()
        val first = parseTime(firstColumn?.let { fields.getOrNull(it) }, latest)
        val last = parseTime(lastColumn?.let { fields.getOrNull(it) }, latest)
        val pending = parsePending(pendingColumn?.let { fields.getOrNull(it) })
        if (!isValidDtcCode(code) || first == null || last == null || pending == null || first > last) {
            rejected++
            continue
        }
        val entry = DtcHistoryEntry(code, first, last, pending)
        val known = byCode[code]
        byCode[code] = if (known == null) entry else mergeDtcHistoryEntry(known, entry)
    }
    return ParsedDtcHistory(byCode.values.toList(), rejected)
}

/**
 * Die Regel des Zusammenfuehrens (D9): `first` ist das kleinere, `last` das groessere, `pending` stammt von dem Eintrag mit dem groesseren `last`; bei Gleichstand gilt der Bestand
 * ([existing]) — dasselbe, was `DtcHistoryDao.mergeAll` in SQL tut (`touch` verschiebt `lastSeen` nur vorwaerts).
 */
fun mergeDtcHistoryEntry(existing: DtcHistoryEntry, incoming: DtcHistoryEntry): DtcHistoryEntry = DtcHistoryEntry(
    code = existing.code,
    firstSeen = minOf(existing.firstSeen, incoming.firstSeen),
    lastSeen = maxOf(existing.lastSeen, incoming.lastSeen),
    lastWasPending = if (incoming.lastSeen > existing.lastSeen) incoming.lastWasPending else existing.lastWasPending,
)

/**
 * Was ein Import der Fehlerhistorie tut: [entries] sind die **zusammengefuehrten** neuen und geaenderten Eintraege (was geschrieben wird, nicht die unveraenderten), die Zahlen die
 * Vorschau. [droppedByCap] = wie viele der aeltesten Codes nach dem Zusammenfuehren ueber die Grenze fallen (auch schon vorhandene).
 */
class DtcHistoryPlan(val entries: List<DtcHistoryEntry>, val newCodes: Int, val updatedCodes: Int, val unchangedCodes: Int, val droppedByCap: Int, val rejected: Int)

fun planDtcHistoryMerge(existing: List<DtcHistoryEntry>, parsed: ParsedDtcHistory, cap: Int = DTC_HISTORY_CAP): DtcHistoryPlan {
    val state = LinkedHashMap<String, DtcHistoryEntry>()
    existing.forEach { state[it.code] = it }
    val changed = ArrayList<DtcHistoryEntry>()
    var new = 0
    var updated = 0
    var unchanged = 0
    for (incoming in parsed.entries) {
        val known = state[incoming.code]
        if (known == null) {
            state[incoming.code] = incoming
            changed += incoming
            new++
        } else {
            val merged = mergeDtcHistoryEntry(known, incoming)
            if (merged == known) {
                unchanged++
            } else {
                state[incoming.code] = merged
                changed += merged
                updated++
            }
        }
    }
    return DtcHistoryPlan(changed, new, updated, unchanged, droppedByCap = (state.size - cap).coerceAtLeast(0), rejected = parsed.rejected)
}
