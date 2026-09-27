package de.adv990de.obd2.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
