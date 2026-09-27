package de.adv990de.obd2.ui.components

import de.adv990de.obd2.obd.convertUnit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Formatiert Datum+Uhrzeit fuer die Ambient-Statuszeile (Datum kurz, Uhrzeit ohne Sekunden).
 * `zone` als Parameter statt `ZoneId.systemDefault()` intern — Testbarkeits-Konvention dieses
 * Projekts (Zeit/Zone von aussen hereinreichen, nie in der Funktion selbst ermitteln).
 */
fun formatDashboardHeader(epochMillis: Long, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String {
    val dateTime = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val datePart = DateTimeFormatter.ofPattern("EEE dd.MM.", locale).format(dateTime)
    val timePart = DateTimeFormatter.ofPattern("HH:mm", locale).format(dateTime)
    return "$datePart · $timePart"
}

/** Einheit, in der `WeatherApi` liefert — dieselbe Domaenen-Einheit wie bei jeder °C-PID in
 *  `PidRegistry`, damit [convertUnit] sie erkennt (Einheiten sind hier Daten, keine UI-Strings). */
private const val OUTSIDE_TEMP_UNIT = "°C"

/**
 * Aussentemperatur fuer die Ambient-Statuszeile: laeuft wie in `SensorCard` durch [convertUnit]
 * (Einstellung "Imperiale Einheiten" -> °F), erst danach auf ganze Grad gerundet.
 * `roundToInt()` statt `"%.0f"`: -0,4 °C wird "0 °C", nicht "-0 °C".
 *
 * `null`, wenn keine Temperatur vorliegt — die Zeile laesst sie dann einfach weg.
 */
fun formatOutsideTemperature(tempC: Float?, useImperial: Boolean): String? {
    if (tempC == null) return null
    val (value, unit) = convertUnit(tempC, OUTSIDE_TEMP_UNIT, useImperial)
    return "${value.roundToInt()} $unit"
}
