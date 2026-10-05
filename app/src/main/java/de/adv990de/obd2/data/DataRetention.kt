package de.adv990de.obd2.data

import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Die automatische Aufbewahrung lokaler Daten, einmal je App-Start (`Obd2Application`):
 * Fahrten aelter als 90 Tage, App-Log-Zeilen aelter als 30 Tage, das Log auf seine neuesten
 * [AppLogRepository.MAX_ROWS] Zeilen und exportierte CSV-Dateien aelter als 24 Stunden.
 *
 * Frueher lief das nur, wenn der Nutzer Verlauf bzw. App-Logs oeffnete — wer das nie tat, hatte
 * unbegrenztes Wachstum. Jeder Schritt ist "best effort": ein Fehler in einem (Datenbank gesperrt)
 * stoppt die uebrigen nicht, landet im App-Log und beendet die App nicht. Nur der Abbruch des
 * Aufrufers wird durchgereicht. Geloescht wird ausschliesslich lokal.
 */
class DataRetention @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val appLogRepository: AppLogRepository,
) {
    suspend fun run() {
        step("old trips") { sessionRepository.deleteOldSessions() }
        step("old log lines") { appLogRepository.pruneOlderThan() }
        step("log row cap") { appLogRepository.trimToNewest() }
        step("old CSV exports") { sessionRepository.purgeStaleExports() }
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            appLogRepository.log(LogLevel.WARN, "Retention ($name) failed: ${e.message}")
        }
    }
}
