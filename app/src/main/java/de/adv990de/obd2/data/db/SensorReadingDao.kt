package de.adv990de.obd2.data.db

import androidx.room.*
import de.adv990de.obd2.data.db.entity.SensorReadingEntity

@Dao
interface SensorReadingDao {
    @Insert suspend fun insert(reading: SensorReadingEntity)
    @Insert suspend fun insertAll(readings: List<SensorReadingEntity>)
    @Query("SELECT * FROM sensor_readings WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getBySession(sessionId: Long): List<SensorReadingEntity>
    @Query("DELETE FROM sensor_readings WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}
