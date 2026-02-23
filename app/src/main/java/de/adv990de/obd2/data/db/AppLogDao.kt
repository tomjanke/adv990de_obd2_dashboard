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

    @Query("SELECT * FROM app_log ORDER BY timestamp DESC")
    fun getAllNewestFirst(): Flow<List<AppLogEntity>>

    @Query("DELETE FROM app_log")
    suspend fun deleteAll()
}
