package de.adv990de.obd2.logbook

/** Grenzen des Wartungsintervalls einer Art in ganzen Kilometern: von 50 (kurze Pflegeintervalle wie die Kette) bis 100 000. */
object MaintenanceIntervalLimits {
    const val MIN_KM = 50
    const val MAX_KM = 100_000
}

/** Das Ergebnis der Eingabe des Intervalls: leer loescht es, sonst eine ganze Zahl in [MaintenanceIntervalLimits]. */
sealed interface MaintenanceIntervalInput {
    data object Cleared : MaintenanceIntervalInput
    data class Valid(val km: Int) : MaintenanceIntervalInput
    data object Invalid : MaintenanceIntervalInput
}

/** Hoechstens so viele Stellen hat die Eingabe ([MaintenanceIntervalLimits.MAX_KM] = 100000). */
private const val MAX_INPUT_DIGITS = 6

/**
 * Liest die Eingabe des Intervalls: nur ASCII-Ziffern, hoechstens sechs Stellen (keine Dezimalzahl, kein Vorzeichen, keine Einheit); fuehrende Nullen zaehlen als Stellen. Leer
 * (oder nur Leerzeichen) loescht das Intervall. Rein, JVM-getestet.
 */
fun parseMaintenanceInterval(raw: String): MaintenanceIntervalInput {
    val text = raw.trim()
    if (text.isEmpty()) return MaintenanceIntervalInput.Cleared
    if (text.length > MAX_INPUT_DIGITS || !text.all { it in '0'..'9' }) return MaintenanceIntervalInput.Invalid
    val km = text.toInt()
    return if (km in MaintenanceIntervalLimits.MIN_KM..MaintenanceIntervalLimits.MAX_KM) MaintenanceIntervalInput.Valid(km) else MaintenanceIntervalInput.Invalid
}

/** Wie nah die Faelligkeit ist: [Ok], kurz davor ([Soon], im letzten Zehntel des Intervalls) oder erreicht und ueberschritten ([Overdue]). */
enum class DueState { Ok, Soon, Overdue }

/**
 * Die Faelligkeit einer Wartungsart: [NotSet] = der Nutzer hat kein Intervall festgelegt (es gibt keinen vorbelegten Wert), [NoBaseline] = Intervall festgelegt, aber kein Eintrag der Art
 * (oder kein Stand), von dem aus gezaehlt werden koennte, [Planned] = Rest bis zur Faelligkeit in km ([Planned.remainingKm] ist 0 oder negativ, sobald sie erreicht ist).
 */
sealed interface MaintenanceDue {
    data object NotSet : MaintenanceDue
    data object NoBaseline : MaintenanceDue
    data class Planned(val remainingKm: Int, val state: DueState) : MaintenanceDue
}

/**
 * Rechnet die Faelligkeit von [item] mit dem Intervall [intervalKm] (`null` oder 0 = nicht festgelegt) und dem aktuellen Stand [currentKm]: Rest = Stand des letzten Eintrags + Intervall −
 * [currentKm]. **Bald** ist der Rest im letzten Zehntel des Intervalls (mindestens 1 km), **ueberfaellig** ab 0 km Rest. Die Aufteilung "bald" ist eine Darstellung, kein Wert des Nutzers.
 */
fun maintenanceDue(item: MaintenanceItem, intervalKm: Int?, currentKm: Int?): MaintenanceDue {
    if (intervalKm == null || intervalKm <= 0) return MaintenanceDue.NotSet
    val last = item.last
    if (last == null || currentKm == null) return MaintenanceDue.NoBaseline
    val remaining = last.odometerKm + intervalKm - currentKm
    val state = when {
        remaining <= 0 -> DueState.Overdue
        remaining <= maxOf(1, intervalKm / 10) -> DueState.Soon
        else -> DueState.Ok
    }
    return MaintenanceDue.Planned(remaining, state)
}

/**
 * Eine Zeile der Uebersicht: die Wartungsart mit ihrem Stand, das Intervall des Nutzers (`null` = nicht festgelegt), die Faelligkeit und [kmSince] — die Kilometer seit dem letzten Eintrag gegen
 * den **geschaetzten** Stand (`null` ohne Eintrag oder Stand).
 */
data class MaintenanceRow(val item: MaintenanceItem, val intervalKm: Int?, val due: MaintenanceDue, val kmSince: Int?)

/**
 * Die Zeilen der Uebersicht: je [items] das Intervall aus [intervals] und die Faelligkeit, gerechnet gegen den **geschaetzten** Stand [estimatedKm] (letzter Logbuch-Stand plus GPS-Kilometer, s.
 * [estimateKm]); ohne Schaetzung oder bei einem niedrigeren Wert gilt der Logbuch-Stand des Eintrags.
 */
fun maintenanceRows(items: List<MaintenanceItem>, intervals: Map<LogType, Int>, estimatedKm: Int?): List<MaintenanceRow> =
    items.map { item ->
        val current = listOfNotNull(item.currentKm, estimatedKm).maxOrNull()
        val interval = intervals[item.type]
        MaintenanceRow(item, interval, maintenanceDue(item, interval, current), kmSince = item.last?.let { last -> current?.let { it - last.odometerKm } })
    }
