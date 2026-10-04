package de.adv990de.obd2.data.db

import android.database.Cursor
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import de.adv990de.obd2.data.db.entity.SensorReadingEntity

@Dao
interface SensorReadingDao {
    @Insert suspend fun insert(reading: SensorReadingEntity)
    @Insert suspend fun insertAll(readings: List<SensorReadingEntity>)
    @RawQuery
    fun getBySessionCursor(query: SupportSQLiteQuery): Cursor
    @Query("DELETE FROM sensor_readings WHERE sessionId IN (:sessionIds)")
    suspend fun deleteBySessions(sessionIds: List<Long>)
}
