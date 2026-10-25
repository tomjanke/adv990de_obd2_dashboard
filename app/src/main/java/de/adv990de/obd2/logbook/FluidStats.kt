package de.adv990de.obd2.logbook

import java.time.LocalDate

/**
 * Ein Verbrauchsintervall von Oel oder Kuehlfluessigkeit: zwischen zwei Eintraegen derselben Art ([startKm] bis [endKm]); [amountMl] ist die Nachfuellmenge des **spaeteren**
 * Eintrags (bis zur Maximalmarke aufgefuellt = das, was in dieser Strecke verbraucht wurde). [date] und [endEntryId] gehoeren zu diesem spaeteren Eintrag.
 */
data class FluidInterval(
    val startKm: Int,
    val endKm: Int,
    val amountMl: Int,
    val date: LocalDate,
    val endEntryId: Long,
) {
    val km: Int get() = endKm - startKm

    /** Milliliter je 1000 km. */
    val mlPer1000Km: Double get() = amountMl * 1000.0 / km
}

/**
 * Die Kennzahlen: [averageMlPer1000Km] ist Summe der Milliliter durch Summe der Kilometer (die Einzelwerte streuen stark — 50-ml-Schritte, Ablesung nach Augenmass —, der Durchschnitt
 * ueber alle Intervalle ist die Aussage). [totalMl] und [totalKm] gelten fuer die Intervalle, nicht fuer den ersten Eintrag (er ist nur Bezug).
 */
data class FluidSummary(
    val intervalCount: Int,
    val averageMlPer1000Km: Double,
    val totalMl: Long,
    val totalKm: Int,
    val latest: FluidInterval,
)

/** [intervals] aelteste zuerst; [skipped] zaehlt die Intervalle, die wegen gleichen Kilometerstands entfielen; [summary] ist `null` ohne Intervall. */
data class FluidReport(val intervals: List<FluidInterval>, val skipped: Int, val summary: FluidSummary?)

/**
 * Der Verbrauch von [type] (nur [LogType.OIL] oder [LogType.COOLANT]) aus [entries]; Eintraege anderer Arten zaehlen nicht, die Reihenfolge der Eingabe ist egal (Kilometerstand,
 * Datum, Id). **Die App kennt keine Wechsel:** ein Intervall, das ueber einen Wechsel laeuft, ist zu niedrig. Gleicher Stand wie der Vorgaenger: das Intervall entfaellt
 * ([FluidReport.skipped]), der Eintrag bleibt Bezug. Ein Eintrag ohne Menge wird uebergangen.
 */
fun fluidReport(entries: List<LogEntry>, type: LogType): FluidReport {
    require(type == LogType.OIL || type == LogType.COOLANT) { "Kein Nachfuell-Verbrauch fuer $type" }
    val rows = entries
        .filter { it.type == type && (it.amountMl ?: 0) > 0 }
        .sortedWith(compareBy({ it.odometerKm }, { it.date }, { it.id }))

    val intervals = mutableListOf<FluidInterval>()
    var skipped = 0
    for (i in 1 until rows.size) {
        val previous = rows[i - 1]
        val current = rows[i]
        if (current.odometerKm > previous.odometerKm) {
            intervals += FluidInterval(previous.odometerKm, current.odometerKm, current.amountMl!!, current.date, current.id)
        } else {
            skipped++
        }
    }

    val summary = if (intervals.isEmpty()) null else {
        val km = intervals.sumOf { it.km }
        val ml = intervals.sumOf { it.amountMl.toLong() }
        FluidSummary(intervals.size, ml * 1000.0 / km, ml, km, intervals.last())
    }
    return FluidReport(intervals, skipped, summary)
}
