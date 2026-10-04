package de.adv990de.obd2.data

import de.adv990de.obd2.data.db.AppLogDao
import de.adv990de.obd2.data.db.entity.AppLogEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLogRepository @Inject constructor(private val dao: AppLogDao) {

    val logs: Flow<List<AppLogEntity>> by lazy { dao.getAllNewestFirst() }

    /**
     * Schreibt eine Protokollzeile — und wirft nie. `log()` steht in Verbindungs-, Wetter- und
     * Pipeline-Pfaden, die im `viewModelScope` laufen; eine Room-Ausnahme (Platte voll, Datenbank
     * gesperrt) haette dort den ganzen Prozess beendet, fuer eine Zeile Beiwerk. Ein Fehler ist
     * deshalb hier verschluckt (es gibt nichts, wohin man ihn melden koennte — das Log ist die
     * Meldestelle selbst). Nur die Abbruch-Ausnahme des Aufrufers bleibt erhalten.
     */
    suspend fun log(level: LogLevel, message: String) {
        try {
            dao.insert(AppLogEntity(timestamp = System.currentTimeMillis(), level = level.name, message = message))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    suspend fun pruneOlderThan(maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000) {
        dao.deleteOlderThan(System.currentTimeMillis() - maxAgeMs)
    }

    /**
     * Haelt das Log auf die [keep] neuesten Zeilen — dieselbe Zahl, die der Bildschirm zeigt. Ohne diese
     * Obergrenze war die "1000" nur ein Abfrage-`LIMIT`; die Tabelle selbst wuchs bis zum Altersschnitt.
     */
    suspend fun trimToNewest(keep: Int = MAX_ROWS) = dao.deleteBeyondNewest(keep)

    suspend fun deleteUpTo(maxId: Long) = dao.deleteUpTo(maxId)

    companion object {
        /** Zeilen, die das Log hoechstens behaelt; entspricht dem `LIMIT` der Anzeige. */
        const val MAX_ROWS = 1000
    }
}
