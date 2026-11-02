package de.adv990de.obd2.data.db

import androidx.paging.PagingSource
import androidx.room.*
import de.adv990de.obd2.data.db.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

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

    /** Callers pass at most a few dozen ids (the repository chunks): SQLite allows only 999 bound variables on API 26. */
    @Query("DELETE FROM sessions WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<Long>)
}
