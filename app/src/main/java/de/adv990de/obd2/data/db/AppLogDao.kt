package de.adv990de.obd2.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import de.adv990de.obd2.data.db.entity.AppLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppLogDao {
    @Insert
    suspend fun insert(entry: AppLogEntity)

    @Query("SELECT * FROM app_log ORDER BY timestamp DESC LIMIT 1000")
    fun getAllNewestFirst(): Flow<List<AppLogEntity>>

    @Query("DELETE FROM app_log WHERE timestamp < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)

    /** Keeps only the [keep] newest rows (ids only grow, so the highest ids are the newest). */
    @Query("DELETE FROM app_log WHERE id NOT IN (SELECT id FROM app_log ORDER BY id DESC LIMIT :keep)")
    suspend fun deleteBeyondNewest(keep: Int)

    /** Deletes every entry up to and including [maxId] — also rows beyond the 1000 the screen shows.
     *  Newer rows (written while a delete is still undoable) are untouched, which `DELETE ... WHERE
     *  id IN (...)` or "delete all" could not guarantee; ids only grow (`autoGenerate`). */
    @Query("DELETE FROM app_log WHERE id <= :maxId")
    suspend fun deleteUpTo(maxId: Long)
}
