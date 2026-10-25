package de.adv990de.obd2.logbook

/** Die sechs Arten der Wartungs-Uebersicht, in der Reihenfolge der Arten: dieselben wie der Filter "Wartung" (eine Quelle). */
val MAINTENANCE_TYPES: List<LogType> = LogType.entries.filter { LogFilter.Maintenance.matches(it) }

/**
 * Der Stand einer Wartungsart. [last] ist der letzte Eintrag (der mit dem hoechsten Kilometerstand, bei Gleichstand der juengere), `null` ohne Eintrag; [currentKm] ist der
 * **hoechste eingegebene Kilometerstand aller Eintraege** (kein Tacho, keine Schaetzung), `null` ohne jeden Eintrag; [kmSince] = [currentKm] − Stand von [last]. [averageSpacingKm]
 * ist der mittlere Abstand zwischen den Eintraegen dieser Art, ab zwei Eintraegen; [count] die Zahl der Eintraege.
 */
data class MaintenanceItem(
    val type: LogType,
    val last: LogEntry?,
    val kmSince: Int?,
    val averageSpacingKm: Double?,
    val currentKm: Int?,
    val count: Int,
)

/**
 * Je Wartungsart ([MAINTENANCE_TYPES]) ein [MaintenanceItem] — auch ohne Eintrag (die Oberflaeche zeigt dann "noch kein Eintrag"). Die Reihenfolge der Eingabe ist egal.
 * Werden Reifen vorne und hinten getrennt gewechselt, gilt der letzte Eintrag der Art; eine Trennung nach vorne/hinten gibt es nicht.
 */
fun maintenanceStatus(entries: List<LogEntry>): List<MaintenanceItem> {
    val currentKm = entries.maxOfOrNull { it.odometerKm }
    return MAINTENANCE_TYPES.map { type ->
        val ofType = entries.filter { it.type == type }.sortedWith(compareBy({ it.odometerKm }, { it.date }, { it.id }))
        val last = ofType.lastOrNull()
        MaintenanceItem(
            type = type,
            last = last,
            kmSince = if (last != null && currentKm != null) currentKm - last.odometerKm else null,
            averageSpacingKm = if (ofType.size >= 2) (ofType.last().odometerKm - ofType.first().odometerKm).toDouble() / (ofType.size - 1) else null,
            currentKm = currentKm,
            count = ofType.size,
        )
    }
}
