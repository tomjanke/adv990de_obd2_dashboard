package de.adv990de.obd2.logbook

/** Der abgeleitete Verbrauch an einer Listenzeile: Liter je 100 km (Tanken) oder Milliliter je 1000 km (Oel, Kuehlfluessigkeit). */
sealed interface EntryConsumption {
    data class Fuel(val litersPer100Km: Double) : EntryConsumption
    data class Fluid(val mlPer1000Km: Double) : EntryConsumption
}

/**
 * Alles, was das Logbuch aus seinen Eintraegen ableitet, in einem Aufruf: die drei Verbrauchsberichte, die Wartungs-Uebersicht und der Verbrauch je Eintrag ([consumption],
 * Schluessel = Id des Eintrags, der das Intervall schliesst; die erste Tankung, Teiltankungen und der erste Eintrag einer Fluessigkeit haben keinen Wert).
 */
data class LogbookInsights(
    val fuel: FuelReport,
    val oil: FluidReport,
    val coolant: FluidReport,
    val maintenance: List<MaintenanceItem>,
    val consumption: Map<Long, EntryConsumption>,
)

fun logbookInsights(entries: List<LogEntry>): LogbookInsights {
    val fuel = fuelReport(entries)
    val oil = fluidReport(entries, LogType.OIL)
    val coolant = fluidReport(entries, LogType.COOLANT)
    val consumption = buildMap<Long, EntryConsumption> {
        fuel.intervals.forEach { put(it.endEntryId, EntryConsumption.Fuel(it.litersPer100Km)) }
        oil.intervals.forEach { put(it.endEntryId, EntryConsumption.Fluid(it.mlPer1000Km)) }
        coolant.intervals.forEach { put(it.endEntryId, EntryConsumption.Fluid(it.mlPer1000Km)) }
    }
    return LogbookInsights(fuel, oil, coolant, maintenanceStatus(entries), consumption)
}
