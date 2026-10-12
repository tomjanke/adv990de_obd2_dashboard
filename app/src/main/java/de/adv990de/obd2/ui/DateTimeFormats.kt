package de.adv990de.obd2.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Die eine Stelle fuer Datum und Uhrzeit in Listen (Verlauf, Fehlerhistorie, App-Logs). Das Format richtet sich nach der Sprache
 * des Geraets (`FormatStyle`): deutsch `12.10.2026`, englisch `Oct 12, 2026`, 24- bzw. 12-Stunden-Uhr. Vorher standen hier drei
 * feste `dd.MM.yyyy`-Muster, eines davon einmal beim Laden der Klasse gebaut — eine Sprachaenderung erreichte es nie.
 *
 * Sprache und Zeitzone als Parameter mit dem Geraete-Standard: so bleibt die Funktion testbar (Konvention des Projekts, vgl.
 * `formatDashboardHeader`), und der Standard wird bei JEDEM Aufruf gelesen. Die Kopfzeile des Dashboards (`formatDashboardHeader`,
 * "Mo 12.10. · 14:30") ist ein eigenes, bewusst kompaktes Format und bleibt davon unberuehrt.
 */

/** Nur das Datum, z. B. `12.10.2026` — fuer "erstmals/zuletzt gesehen". */
fun formatDate(
    epochMillis: Long,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/** Datum und Uhrzeit ohne Sekunden, z. B. `12.10.2026, 14:30` — fuer den Beginn einer Fahrt. */
fun formatDateTime(
    epochMillis: Long,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
    .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/** Datum und Uhrzeit mit Sekunden, z. B. `12.10.2026, 14:30:05` — fuer App-Log-Zeilen, die dicht aufeinander folgen. */
fun formatDateTimeSeconds(
    epochMillis: Long,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM).withLocale(locale)
    .format(Instant.ofEpochMilli(epochMillis).atZone(zone))
