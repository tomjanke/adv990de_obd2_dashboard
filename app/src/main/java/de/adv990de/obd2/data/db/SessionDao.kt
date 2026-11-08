package de.adv990de.obd2.data.db

import androidx.paging.PagingSource
import androidx.room.*
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.tripimport.SessionSpan
import kotlinx.coroutines.flow.Flow

/** Die FIN und die Fehlercodes einer Fahrt (der Export des Pakets, `trips_meta.csv`). */
data class SessionMetaRow(val id: Long, val vehicleVin: String?, val dtcCodes: String?)

@Dao
interface SessionDao {
    @Insert suspend fun insert(session: SessionEntity): Long
    @Update suspend fun update(session: SessionEntity)
    @Query("SELECT * FROM sessions ORDER BY startTime DESC") fun pagingSource(): PagingSource<Int, SessionEntity>
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun getById(id: Long): SessionEntity?
    @Delete suspend fun delete(session: SessionEntity)
    /** Ids of the trips that started before [cutoffMs] — the caller deletes them in chunks (see [SessionRepository.deleteOldSessions]). */
    @Query("SELECT id FROM sessions WHERE startTime < :cutoffMs ORDER BY id")
    suspend fun idsOlderThan(cutoffMs: Long): List<Long>

    /** Every trip id, oldest first — the history decides from this which trips "delete all" would touch. */
    @Query("SELECT id FROM sessions ORDER BY id") fun observeIds(): Flow<List<Long>>

    /** The ids of the trips that have at least one reading, oldest first — only those have rows in an export file. */
    @Query("SELECT id FROM sessions WHERE EXISTS (SELECT 1 FROM sensor_readings WHERE sessionId = sessions.id) ORDER BY id")
    suspend fun idsWithReadings(): List<Long>

    /**
     * Beginn und Ende jeder Fahrt, fuer den Fahrten-Import (Fahrten, die sich zeitlich beruehren, sind dieselbe Fahrt). Eine nie beendete Fahrt (ein getoeteter Prozess laesst
     * `endTime` leer) endet beim letzten Messwert, ohne Messwerte bei ihrem Beginn.
     */
    @Query(
        "SELECT startTime AS startMs, " +
            "COALESCE(endTime, (SELECT MAX(timestamp) FROM sensor_readings WHERE sessionId = sessions.id), startTime) AS endMs " +
            "FROM sessions",
    )
    suspend fun spans(): List<SessionSpan>

    /** At most 500 ids per call (the repository chunks): SQLite allows only 999 bound variables on API 26. */
    @Query("SELECT id, vehicleVin, dtcCodes FROM sessions WHERE id IN (:ids)")
    suspend fun metaByIds(ids: List<Long>): List<SessionMetaRow>

    /** Callers pass at most a few dozen ids (the repository chunks): SQLite allows only 999 bound variables on API 26. */
    @Query("DELETE FROM sessions WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<Long>)
}
