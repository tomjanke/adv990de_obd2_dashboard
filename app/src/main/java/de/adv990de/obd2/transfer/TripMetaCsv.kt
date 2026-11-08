package de.adv990de.obd2.transfer

import de.adv990de.obd2.data.CsvRecordReader
import de.adv990de.obd2.data.csvRow
import de.adv990de.obd2.data.decodeSessionDtcs
import de.adv990de.obd2.data.encodeSessionDtcs
import de.adv990de.obd2.obd.VinDecoder
import java.io.Reader

/**
 * `trips_meta.csv`, die Angaben je Fahrt, die `trips.csv` nicht tragen: die FIN und die Fehlercodes bei Beginn der Fahrt. Die Spalten sind ein Vertrag des Formats 1
 * (nie umbenennen): `session` (die Fahrt-Nummer wie in `trips.csv`), `vin`, `dtcs_recorded` (`1` = die Codes wurden gelesen, sonst nicht erfasst) und `dtc_codes`
 * (das gespeicherte Format aus `SessionDtcs.kt`, z. B. `P0301,P0420?`).
 */
val TRIP_META_COLUMNS = arrayOf("session", "vin", "dtcs_recorded", "dtc_codes")

/**
 * Die Angaben einer Fahrt. [vin] `null` = keine FIN erfasst. [dtcCodes] im gespeicherten Format: `null` = **nicht erfasst**, `""` = gelesen und keine Codes, sonst die Codes —
 * ein "keine Codes" darf nie aus einem "nicht erfasst" entstehen (das waere eine Entwarnung, die das Fahrzeug nie gegeben hat).
 */
data class TripMeta(val vin: String?, val dtcCodes: String?) {
    val isEmpty: Boolean get() = vin == null && dtcCodes == null
}

fun tripMetaCsvHeader(): String = csvRow(*TRIP_META_COLUMNS)

/** Eine Zeile mit Zeilenumbruch; `dtcs_recorded` ist `1`, wenn [TripMeta.dtcCodes] nicht `null` ist, sonst `0` (und `dtc_codes` leer). */
fun tripMetaCsvLine(sessionId: String, meta: TripMeta): String = csvRow(
    sessionId,
    meta.vin.orEmpty(),
    if (meta.dtcCodes != null) "1" else "0",
    meta.dtcCodes.orEmpty(),
)

/** Was die Datei enthaelt: die Angaben je Fahrt-Nummer und was beim Lesen verworfen wurde (jeweils gezaehlt). */
class ParsedTripMeta(val byTrip: Map<String, TripMeta>, val invalidVins: Int, val rejectedRows: Int, val duplicateRows: Int)

private const val COLUMN_SESSION = "session"
private const val COLUMN_VIN = "vin"
private const val COLUMN_RECORDED = "dtcs_recorded"
private const val COLUMN_CODES = "dtc_codes"

/**
 * Liest `trips_meta.csv` nach Spaltennamen; [IllegalArgumentException], wenn die Spalte `session` fehlt (oder die Datei leer ist oder nicht lesbares CSV). Je Zeile: eine leere `session`
 * lehnt die Zeile ab; eine leere `vin` ist `null`, eine ungueltige ([VinDecoder.isValidVin]) wird verworfen und gezaehlt — die Fahrt bleibt; `dtcs_recorded` = `1` liest die Codes (ein leeres
 * Feld heisst "gelesen, keine Codes", Ungueltiges faellt weg), alles andere heisst "nicht erfasst" und ein vorhandener Codetext wird **ignoriert**; bei einer doppelten `session` gilt die
 * erste Zeile.
 */
fun parseTripMetaCsv(reader: Reader): ParsedTripMeta {
    val csv = CsvRecordReader(reader)
    var header: List<String>? = null
    while (header == null) {
        val record = csv.next() ?: break
        if (record.fields.any { it.isNotBlank() }) header = record.fields
    }
    if (header == null) throw IllegalArgumentException("The file is empty")
    val columns = HashMap<String, Int>()
    header.forEachIndexed { i, name -> columns.putIfAbsent(name.trim(), i) }
    val sessionColumn = columns[COLUMN_SESSION] ?: throw IllegalArgumentException("Required column missing: $COLUMN_SESSION")
    val vinColumn = columns[COLUMN_VIN]
    val recordedColumn = columns[COLUMN_RECORDED]
    val codesColumn = columns[COLUMN_CODES]

    val byTrip = LinkedHashMap<String, TripMeta>()
    var invalidVins = 0
    var rejected = 0
    var duplicates = 0
    while (true) {
        val record = csv.next() ?: break
        val fields = record.fields
        if (fields.all { it.isBlank() }) continue
        val session = fields.getOrNull(sessionColumn)?.trim().orEmpty()
        if (session.isEmpty()) { rejected++; continue }
        if (session in byTrip) { duplicates++; continue }

        val vinText = vinColumn?.let { fields.getOrNull(it)?.trim() }.orEmpty()
        val vin = when {
            vinText.isEmpty() -> null
            VinDecoder.isValidVin(vinText) -> vinText
            else -> { invalidVins++; null }
        }
        val recorded = recordedColumn?.let { fields.getOrNull(it)?.trim() } == "1"
        val dtcCodes = if (recorded) encodeSessionDtcs(decodeSessionDtcs(codesColumn?.let { fields.getOrNull(it) }.orEmpty())) else null
        byTrip[session] = TripMeta(vin, dtcCodes)
    }
    return ParsedTripMeta(byTrip, invalidVins, rejected, duplicates)
}
