package de.adv990de.obd2.logbook

import java.time.LocalDate

/**
 * Was an einem Eintrag auffaellt. **Fehler** sperren das Speichern (beim Import wird die Zeile abgelehnt), **Warnungen** brauchen eine Rueckfrage
 * ("Trotzdem speichern?"; beim Import werden sie gezaehlt und der Eintrag uebernommen).
 */
sealed interface LogIssue {
    sealed interface Error : LogIssue
    sealed interface Warning : LogIssue

    /** Kilometerstand ausserhalb von 0 bis 999 999. */
    data object OdometerRange : Error

    /** Tanken, Oel oder Kuehlfluessigkeit ohne Menge. */
    data object AmountMissing : Error

    /** Menge nicht groesser als 0 oder ueber dem Hoechstwert der Art ([LogType.maxAmountMl]). */
    data object AmountRange : Error

    /** Datum nach heute. */
    data object DateInFuture : Error

    /** Kosten bei einer Art ohne Kosten, oder nicht in 0,01 bis 999,99 Euro. */
    data object CostRange : Error

    /** Notiz laenger als [MAX_NOTE_LENGTH] Zeichen. */
    data object NoteTooLong : Error

    /** Reifenwechsel ohne Fabrikat (leer oder nur Leerzeichen zaehlt als fehlend). */
    data object BrandMissing : Error

    /** Fabrikat laenger als [MAX_BRAND_LENGTH] Zeichen. */
    data object BrandTooLong : Error

    /** Fabrikat bei einer Art, die keines hat (alle ausser [LogType.TIRE_CHANGE]). */
    data object BrandNotAllowed : Error

    /** Ein Eintrag mit frueherem Datum hat einen hoeheren Stand (oder einer mit spaeterem Datum einen niedrigeren): [other] ist der erste solche Eintrag. */
    data class OdometerOutOfOrder(val other: LogEntry) : Warning

    /** Preis je Liter ausserhalb von 0,50 bis 5,00 Euro; [centsPerLiter] auf den Cent gerundet. */
    data class PriceImplausible(val centsPerLiter: Int) : Warning

    /** Das Intervall bis zu dieser vollen Tankung ergaebe weniger als 2 oder mehr als 20 l/100 km. */
    data class ConsumptionImplausible(val litersPer100Km: Double) : Warning
}

const val MAX_ODOMETER_KM = 999_999
const val MAX_NOTE_LENGTH = 200
const val MAX_BRAND_LENGTH = 80

/** Hoechste Kosten einer Tankung in Cent (999,99 Euro). */
const val MAX_COST_CENTS = 99_999

private const val MIN_PRICE_CENTS_PER_LITER = 50L
private const val MAX_PRICE_CENTS_PER_LITER = 500L

/** Grenzen des Verbrauchs in Millilitern je Kilometer ([ml] ÷ 10 ÷ [km] = l/100 km): unter 2 und ueber 20 l/100 km sind unplausibel. */
private const val MIN_CONSUMPTION_ML_PER_KM = 20L
private const val MAX_CONSUMPTION_ML_PER_KM = 200L

/**
 * Prueft [entry] gegen die Regeln des Logbuchs. [others] sind die uebrigen Eintraege (ohne [entry] selbst); [today] kommt vom Aufrufer (keine Uhr hier).
 * Hat der Eintrag Fehler, werden nur diese geliefert — Warnungen ueber ungueltige Werte waeren sinnlos.
 */
fun validate(entry: LogEntry, others: List<LogEntry>, today: LocalDate): List<LogIssue> {
    val errors = errorsOf(entry, today)
    if (errors.isNotEmpty()) return errors
    return listOfNotNull(outOfOrder(entry, others), priceWarning(entry), consumptionWarning(entry, others))
}

private fun errorsOf(entry: LogEntry, today: LocalDate): List<LogIssue.Error> = buildList {
    if (entry.odometerKm !in 0..MAX_ODOMETER_KM) add(LogIssue.OdometerRange)
    if (entry.type.hasAmount) {
        val amount = entry.amountMl
        if (amount == null) add(LogIssue.AmountMissing)
        else if (amount <= 0 || amount > entry.type.maxAmountMl) add(LogIssue.AmountRange)
    }
    if (entry.date.isAfter(today)) add(LogIssue.DateInFuture)
    val cost = entry.costCents
    if (cost != null && (entry.type != LogType.FUEL || cost <= 0 || cost > MAX_COST_CENTS)) add(LogIssue.CostRange)
    if ((entry.note?.length ?: 0) > MAX_NOTE_LENGTH) add(LogIssue.NoteTooLong)
    val brand = entry.brand
    if (entry.type.hasBrand) {
        if (brand.isNullOrBlank()) add(LogIssue.BrandMissing) else if (brand.length > MAX_BRAND_LENGTH) add(LogIssue.BrandTooLong)
    } else if (brand != null) add(LogIssue.BrandNotAllowed)
}

private fun conflicts(a: LogEntry, b: LogEntry): Boolean =
    (b.date < a.date && b.odometerKm > a.odometerKm) || (b.date > a.date && b.odometerKm < a.odometerKm)

private fun outOfOrder(entry: LogEntry, others: List<LogEntry>): LogIssue.OdometerOutOfOrder? =
    others.filter { conflicts(entry, it) }
        .minWithOrNull(compareBy<LogEntry>({ it.date }, { it.odometerKm }, { it.id }))
        ?.let { LogIssue.OdometerOutOfOrder(it) }

/** Exakt gerechnet in ganzen Zahlen: Preis je Liter = [LogEntry.costCents] × 1000 ÷ [LogEntry.amountMl] Cent, die Grenzen sind einschliesslich. */
private fun priceWarning(entry: LogEntry): LogIssue.PriceImplausible? {
    if (entry.type != LogType.FUEL) return null
    val cost = entry.costCents?.toLong() ?: return null
    val ml = entry.amountMl?.toLong() ?: return null
    val scaled = cost * 1000
    return if (scaled < MIN_PRICE_CENTS_PER_LITER * ml || scaled > MAX_PRICE_CENTS_PER_LITER * ml) {
        LogIssue.PriceImplausible(((scaled + ml / 2) / ml).toInt())
    } else null
}

/**
 * Das Intervall dieser vollen Tankung (Voll-zu-Voll): von der vorigen vollen Tankung bei niedrigerem Stand bis hierher, mit den Litern der
 * Teiltankungen dazwischen. Ohne volle Vorgaengerin gibt es nichts zu beurteilen; eine Teiltankung wird nie beurteilt.
 */
private fun consumptionWarning(entry: LogEntry, others: List<LogEntry>): LogIssue.ConsumptionImplausible? {
    if (entry.type != LogType.FUEL || entry.fullTank == false) return null
    val ml = entry.amountMl?.toLong() ?: return null
    val before = others.filter { it.type == LogType.FUEL && it.odometerKm < entry.odometerKm }
    val previous = before.filter { it.fullTank != false }
        .maxWithOrNull(compareBy<LogEntry>({ it.odometerKm }, { it.date }, { it.id })) ?: return null
    val partialMl = before.filter { it.fullTank == false && it.odometerKm > previous.odometerKm }.sumOf { (it.amountMl ?: 0).toLong() }
    val km = (entry.odometerKm - previous.odometerKm).toLong()
    val totalMl = ml + partialMl
    return if (totalMl < MIN_CONSUMPTION_ML_PER_KM * km || totalMl > MAX_CONSUMPTION_ML_PER_KM * km) {
        LogIssue.ConsumptionImplausible(totalMl / (10.0 * km))
    } else null
}

/**
 * Die Reihenfolge-Warnung fuer viele Eintraege auf einmal (Import), in O(n log n): `result[i]` ist wahr, wenn [entries]`[i]` mit einem anderen
 * Eintrag im Widerspruch steht (frueheres Datum mit hoeherem Stand oder spaeteres Datum mit niedrigerem). Dieselbe Regel wie [validate], dort fuer
 * einen einzelnen Eintrag; `LogValidationTest` haelt beide Wege gegeneinander.
 */
internal fun findOutOfOrder(entries: List<LogEntry>): BooleanArray {
    val result = BooleanArray(entries.size)
    val sorted = entries.indices.sortedBy { entries[it].date }
    val suffixMin = IntArray(sorted.size + 1).also { it[sorted.size] = Int.MAX_VALUE }
    for (k in sorted.indices.reversed()) suffixMin[k] = minOf(suffixMin[k + 1], entries[sorted[k]].odometerKm)
    var maxBefore = Int.MIN_VALUE
    var start = 0
    while (start < sorted.size) {
        var end = start
        while (end < sorted.size && entries[sorted[end]].date == entries[sorted[start]].date) end++
        val minAfter = suffixMin[end]
        var dayMax = maxBefore
        for (k in start until end) {
            val km = entries[sorted[k]].odometerKm
            if (km < maxBefore || km > minAfter) result[sorted[k]] = true
            dayMax = maxOf(dayMax, km)
        }
        maxBefore = dayMax
        start = end
    }
    return result
}
