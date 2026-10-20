package de.adv990de.obd2.data

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Die reinen Teile des App-Log-Exports, ohne Android-Bezug (das Schreiben steht in [AppLogExporter]). Das Format ist das der Fahrten-Exporte
 * (CSV nach RFC 4180 mit [csvRow], Zeitstempel in Millisekunden UTC), damit sich beide Dateien mit denselben Werkzeugen lesen lassen.
 */

/** Die Spalten des Exports: Zeit (Millisekunden seit 1970, UTC), Stufe (`INFO`, `WARN`, `ERROR`) und die Meldung. */
internal val APP_LOG_EXPORT_COLUMNS = arrayOf("timestamp", "level", "message")

/**
 * Die Abfrage des Exports: alle Zeilen **nach** der gegebenen Id, aelteste zuerst. `ORDER BY id` ist die Reihenfolge des Primaerschluessels — SQLite
 * braucht keinen Sortierlauf, der Export liest also mit konstantem Speicher (der Geraete-Test prueft den Abfrageplan). Die Id ist die obere Grenze
 * dessen, was der Nutzer nicht mehr sieht: Zeilen, die er gerade geloescht hat und die noch im Rueckgaengig-Fenster stehen, gehoeren nicht in die Datei.
 */
internal const val APP_LOG_EXPORT_QUERY_SQL = "SELECT timestamp, level, message FROM app_log WHERE id > ? ORDER BY id ASC"

private val APP_LOG_EXPORT_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)

/** `applog_<UTC-Zeit auf die Millisekunde>.csv` — der Name passt zum Aufraeumen alter Exporte (`applog_*.csv`, s. [deleteStaleExports]). */
internal fun appLogExportFileName(nowMillis: Long): String =
    "applog_${APP_LOG_EXPORT_TIME_FORMAT.format(Instant.ofEpochMilli(nowMillis))}.csv"
