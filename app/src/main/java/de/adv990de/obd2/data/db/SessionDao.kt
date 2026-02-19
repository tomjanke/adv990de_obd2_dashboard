package de.adv990de.obd2.data.db

import androidx.room.*
import de.adv990de.obd2.data.db.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert fun insert(session: SessionEntity): Long
    @Update fun update(session: SessionEntity)
    @Query("SELECT * FROM sessions ORDER BY startTime DESC") fun getAll(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun getById(id: Long): SessionEntity?
    @Delete fun delete(session: SessionEntity)
}
