package de.adv990de.obd2.logbook

import de.adv990de.obd2.data.csvRow
import de.adv990de.obd2.data.parseCsv
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Das CSV des Logbuchs (Export und Import), ohne Android-Bezug. Spalten: `date` (`yyyy-MM-dd`), `type` (Name des [LogType]), `odometer_km`,
 * `amount_ml` (ganze Milliliter), `full_tank` (`1`/`0` oder leer), `cost_eur` (Euro mit Punkt, z. B. `14.75`; der Import liest auch `14,75`), `note`, `brand` (Fabrikat, nur beim Reifenwechsel;
 * die Spalte steht hinten, damit eine aeltere Datei ohne sie weiter lesbar bleibt).
 * Geschrieben wird mit [csvRow] (RFC 4180), gelesen von [parseCsv] (`data/CsvRead.kt`, derselbe Leser wie beim Fahrten-Import).
 */
val LOGBOOK_COLUMNS = arrayOf("date", "type", "odometer_km", "amount_ml", "full_tank", "cost_eur", "note", "brand")

/** Hoechstzahl der Datenzeilen eines Imports; mehr bricht den Import ab (Schutz vor einer falschen, riesigen Datei). */
const val MAX_IMPORT_ROWS = 20_000

private val REQUIRED_COLUMNS = listOf("date", "type", "odometer_km")

private val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)

/** `logbook_<UTC-Zeit auf die Millisekunde>.csv` — der Name passt zum Aufraeumen alter Exporte (s. `deleteStaleExports`). */
fun logbookFileName(nowMillis: Long): String = "logbook_${FILE_TIME_FORMAT.format(Instant.ofEpochMilli(nowMillis))}.csv"

fun logbookCsvHeader(): String = csvRow(*LOGBOOK_COLUMNS)

/** Eine Zeile der Datei, mit Zeilenumbruch am Ende; nicht vorgesehene Werte bleiben leer. */
fun logbookCsvLine(entry: LogEntry): String = csvRow(
    entry.date.toString(),
    entry.type.name,
    entry.odometerKm.toString(),
    entry.amountMl?.toString().orEmpty(),
    when (entry.fullTank) { true -> "1"; false -> "0"; null -> "" },
    entry.costCents?.let { BigDecimal.valueOf(it.toLong(), 2).toPlainString() }.orEmpty(),
    entry.note.orEmpty(),
    entry.brand.orEmpty(),
)

private val COST_PATTERN = Regex("""-?\d+([.,]\d{1,2})?""")

/** Euro mit Punkt oder Komma, eine oder zwei Nachkommastellen, kein Tausendertrennzeichen → ganze Cent; sonst `null`. */
private fun parseCostCents(text: String): Int? {
    if (!COST_PATTERN.matches(text)) return null
    return try {
        BigDecimal(text.replace(',', '.')).movePointRight(2).intValueExact()
    } catch (_: ArithmeticException) {
        null
    }
}

/**
 * Liest ein Logbuch-CSV: Spalten nach Namen (Reihenfolge frei, unbekannte werden ignoriert, `date`, `type` und `odometer_km` sind Pflicht), jede
 * Datenzeile wird gelesen und mit den Regeln des Formulars geprueft ([validate] ohne die anderen Eintraege: Fehler lehnen die Zeile ab,
 * Warnungen bleiben an der Zeile). Leerzeilen werden uebersprungen. Eine fehlende Pflichtspalte, eine leere Datei, ein offenes Anfuehrungszeichen
 * und mehr als [MAX_IMPORT_ROWS] Zeilen brechen mit [IllegalArgumentException] ab.
 *
 * Ein leeres `full_tank` heisst beim Tanken "voll", bei den anderen Arten bleibt es `null`; die Menge der Arten ohne Menge wird verworfen.
 */
fun parseLogbookCsv(text: String, today: LocalDate): ParsedLogbook {
    val records = parseCsv(text).filterNot { record -> record.fields.all { it.isBlank() } }
    require(records.isNotEmpty()) { "The file is empty" }
    val index = HashMap<String, Int>()
    records.first().fields.forEachIndexed { i, name -> index.putIfAbsent(name.trim(), i) }
    for (column in REQUIRED_COLUMNS) require(column in index) { "Required column missing: $column" }
    val rows = records.drop(1)
    require(rows.size <= MAX_IMPORT_ROWS) { "More than $MAX_IMPORT_ROWS rows" }

    val parsed = ArrayList<ParsedRow>()
    val errors = ArrayList<RowError>()
    for (record in rows) {
        fun field(name: String): String = index[name]?.let { record.fields.getOrNull(it) }?.trim().orEmpty()
        val bad = ArrayList<ImportReason>()

        val date = try { LocalDate.parse(field("date")) } catch (_: DateTimeParseException) { bad += ImportReason.BadValue("date"); null }
        val type = LogType.entries.firstOrNull { it.name == field("type") }.also { if (it == null) bad += ImportReason.BadValue("type") }
        val km = field("odometer_km").toIntOrNull().also { if (it == null) bad += ImportReason.BadValue("odometer_km") }
        val amountText = field("amount_ml")
        val amount = if (amountText.isEmpty()) null else amountText.toIntOrNull().also { if (it == null) bad += ImportReason.BadValue("amount_ml") }
        val fullText = field("full_tank")
        val full = when (fullText) {
            "" -> null
            "1" -> true
            "0" -> false
            else -> { bad += ImportReason.BadValue("full_tank"); null }
        }
        val costText = field("cost_eur")
        val cost = if (costText.isEmpty()) null else parseCostCents(costText).also { if (it == null) bad += ImportReason.BadValue("cost_eur") }

        if (bad.isNotEmpty()) {
            errors += RowError(record.line, bad)
            continue
        }
        val entry = LogEntry(
            type = type!!,
            date = date!!,
            odometerKm = km!!,
            amountMl = if (type.hasAmount) amount else null,
            fullTank = if (type == LogType.FUEL) full ?: true else null,
            costCents = cost,
            note = field("note").ifEmpty { null },
            brand = field("brand").ifEmpty { null },
        )
        val issues = validate(entry, emptyList(), today)
        val rules = issues.filterIsInstance<LogIssue.Error>()
        if (rules.isNotEmpty()) errors += RowError(record.line, rules.map { ImportReason.Rule(it) })
        else parsed += ParsedRow(record.line, entry, issues.filterIsInstance<LogIssue.Warning>())
    }
    return ParsedLogbook(parsed, errors)
}
