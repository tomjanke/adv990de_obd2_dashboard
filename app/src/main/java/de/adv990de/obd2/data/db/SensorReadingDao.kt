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
    /**
     * How many readings there are outside the trip [excludedSessionId] (`null` = all of them): the number of data rows an export of every finished trip writes. A trip without
     * readings adds nothing, so this is the row count of the trips the export takes.
     */
    @Query("SELECT COUNT(*) FROM sensor_readings WHERE :excludedSessionId IS NULL OR sessionId != :excludedSessionId")
    suspend fun countExcept(excludedSessionId: Long?): Long

    @Query("DELETE FROM sensor_readings WHERE sessionId IN (:sessionIds)")
    suspend fun deleteBySessions(sessionIds: List<Long>)
}
