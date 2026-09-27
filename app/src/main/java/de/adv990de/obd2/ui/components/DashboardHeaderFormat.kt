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

private const val MILLIS_PER_MINUTE = 60_000L

/**
 * Millisekunden bis zur naechsten vollen Minute — damit die Uhr in der Ambient-Statuszeile genau
 * zum Minutenwechsel umspringt statt bis zu einem Tick-Intervall zu spaet. Genau auf der Grenze:
 * eine volle Minute, nie 0 (sonst liefe die Tick-Schleife leer). Minutengrenzen in Epoch-Millis
 * fallen fuer jede Zeitzone mit ganzzahligem Minuten-Offset (alle heutigen) mit den lokalen
 * zusammen.
 */
fun millisUntilNextMinute(nowMillis: Long): Long =
    MILLIS_PER_MINUTE - Math.floorMod(nowMillis, MILLIS_PER_MINUTE)

/** Einheit, in der `WeatherApi` liefert — dieselbe Domaenen-Einheit wie bei jeder °C-PID in
 *  `PidRegistry`, damit [convertUnit] sie erkennt (Einheiten sind hier Daten, keine UI-Strings). */
private const val OUTSIDE_TEMP_UNIT = "°C"

/**
 * Aussentemperatur fuer die Ambient-Statuszeile: laeuft wie in `SensorCard` durch [convertUnit]
 * (Einstellung "Imperiale Einheiten" -> °F), erst danach auf ganze Grad gerundet.
 * `roundToInt()` statt `"%.0f"`: -0,4 °C wird "0 °C", nicht "-0 °C".
 *
 * `null`, wenn keine Temperatur vorliegt — die Zeile laesst sie dann einfach weg. Ebenso fuer
 * NaN/Unendlich (gleicher Schutz wie `SensorCard`/`formatLoggingRowValue`): `NaN.roundToInt()`
 * wirft, auf dem UI-Thread waere das ein Absturz. `OpenMeteoApi` filtert solche Werte schon beim
 * Parsen; das hier haelt die Funktion fuer jeden Aufrufer total.
 */
fun formatOutsideTemperature(tempC: Float?, useImperial: Boolean): String? {
    if (tempC == null || !tempC.isFinite()) return null
    val (value, unit) = convertUnit(tempC, OUTSIDE_TEMP_UNIT, useImperial)
    return "${value.roundToInt()} $unit"
}
