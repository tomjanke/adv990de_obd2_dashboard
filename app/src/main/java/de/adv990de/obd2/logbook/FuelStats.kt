package de.adv990de.obd2.logbook

import java.time.LocalDate

/**
 * Ein Verbrauchsintervall "Voll-zu-Voll": von einer vollen Tankung ([startKm]) bis zur naechsten vollen ([endKm]). [amountMl] ist die Menge der Tankung am Ende **plus aller
 * Teiltankungen dazwischen** (ihr Kraftstoff steckt in dieser Strecke). [costCents] ist die Summe der Kosten genau dieser Tankungen, `null`, sobald eine davon keine hat
 * (eine Teilsumme waere ein falscher Preis). [date] und [endEntryId] gehoeren zur schliessenden Tankung — die Listenzeile zeigt den Wert an diesem Eintrag.
 */
data class FuelInterval(
    val startKm: Int,
    val endKm: Int,
    val amountMl: Long,
    val date: LocalDate,
    val costCents: Long?,
    val endEntryId: Long,
) {
    val km: Int get() = endKm - startKm
    val liters: Double get() = amountMl / 1000.0

    /** Liter je 100 km: ml / 1000 ÷ km × 100 = ml ÷ (10 · km). */
    val litersPer100Km: Double get() = amountMl / (10.0 * km)
}

/**
 * Die Kennzahlen aller Intervalle. [averageLitersPer100Km] ist **Summe der Liter durch Summe der Kilometer** (nicht der Mittelwert der Einzelwerte: der ist verzerrt, ein kurzes
 * Intervall mit hohem Verbrauch zaehlt dort so viel wie ein langes). [totalMl] sind **alle** Tankungen (auch die erste und die Teiltankungen), [intervalMl] nur die in den
 * Intervallen. [averagePriceCentsPerLiter] ueber jede Tankung mit Kosten; [costCentsPer100Km] nur ueber Intervalle, in denen alle beteiligten Tankungen Kosten haben.
 * [best] ist der niedrigste, [worst] der hoechste Verbrauch; bei Gleichstand gilt das juengere Intervall.
 */
data class FuelSummary(
    val intervalCount: Int,
    val averageLitersPer100Km: Double,
    val latest: FuelInterval,
    val best: FuelInterval,
    val worst: FuelInterval,
    val intervalKm: Int,
    val intervalMl: Long,
    val totalMl: Long,
    val averageKmPerInterval: Double,
    val averagePriceCentsPerLiter: Double?,
    val costCentsPer100Km: Double?,
)

/** [intervals] aelteste zuerst; [skipped] zaehlt die Intervalle, die wegen gleichen Kilometerstands entfielen; [summary] ist `null` ohne Intervall. */
data class FuelReport(val intervals: List<FuelInterval>, val skipped: Int, val summary: FuelSummary?)

/**
 * Die Verbrauchsrechnung aus den Tankungen in [entries] (andere Arten zaehlen nicht; die Reihenfolge der Eingabe ist egal). Sortiert wird nach Kilometerstand, bei Gleichstand nach
 * Datum, dann nach Id.
 *
 * Ein Intervall endet bei jeder **vollen** Tankung (`fullTank != false`), die eine volle Vorgaengerin hat; **Teiltankungen vor der ersten vollen** zaehlen nicht. Ist die
 * Kilometer-Differenz 0 (gleicher Stand), entfaellt das Intervall ([FuelReport.skipped]), die Tankung bleibt aber Bezug fuer das naechste. Eine Tankung ohne Menge wird uebergangen.
 */
fun fuelReport(entries: List<LogEntry>): FuelReport {
    val fills = entries
        .filter { it.type == LogType.FUEL && (it.amountMl ?: 0) > 0 }
        .sortedWith(compareBy({ it.odometerKm }, { it.date }, { it.id }))

    val intervals = mutableListOf<FuelInterval>()
    var skipped = 0
    var reference: LogEntry? = null
    var pendingMl = 0L
    var pendingCost: Long? = 0L

    for (fill in fills) {
        val ml = fill.amountMl!!.toLong()
        val cost = fill.costCents?.toLong()
        if (fill.fullTank == false) {
            // Eine Teiltankung vor der ersten vollen sammelt hier auch, aber die erste volle Tankung setzt die Summe zurueck (s. u.): sie zaehlt nicht.
            pendingMl += ml
            pendingCost = if (pendingCost != null && cost != null) pendingCost + cost else null
            continue
        }
        val start = reference
        if (start != null) {
            if (fill.odometerKm > start.odometerKm) {
                intervals += FuelInterval(
                    startKm = start.odometerKm,
                    endKm = fill.odometerKm,
                    amountMl = pendingMl + ml,
                    date = fill.date,
                    costCents = if (pendingCost != null && cost != null) pendingCost + cost else null,
                    endEntryId = fill.id,
                )
            } else {
                skipped++
            }
        }
        reference = fill
        pendingMl = 0L
        pendingCost = 0L
    }

    return FuelReport(intervals, skipped, summarize(intervals, fills))
}

private fun summarize(intervals: List<FuelInterval>, fills: List<LogEntry>): FuelSummary? {
    if (intervals.isEmpty()) return null
    val km = intervals.sumOf { it.km }
    val ml = intervals.sumOf { it.amountMl }
    val priced = fills.filter { it.costCents != null }
    val pricedMl = priced.sumOf { it.amountMl!!.toLong() }
    val complete = intervals.filter { it.costCents != null }
    val completeKm = complete.sumOf { it.km }
    return FuelSummary(
        intervalCount = intervals.size,
        averageLitersPer100Km = ml / (10.0 * km),
        latest = intervals.last(),
        best = intervals.withIndex().minWithOrNull(compareBy({ it.value.litersPer100Km }, { -it.index }))!!.value,
        worst = intervals.withIndex().maxWithOrNull(compareBy({ it.value.litersPer100Km }, { it.index }))!!.value,
        intervalKm = km,
        intervalMl = ml,
        totalMl = fills.sumOf { it.amountMl!!.toLong() },
        averageKmPerInterval = km.toDouble() / intervals.size,
        averagePriceCentsPerLiter = if (priced.isEmpty()) null else priced.sumOf { it.costCents!!.toLong() } * 1000.0 / pricedMl,
        costCentsPer100Km = if (complete.isEmpty()) null else complete.sumOf { it.costCents!! } * 100.0 / completeKm,
    )
}
