package de.adv990de.obd2.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity

@Database(
    entities = [SessionEntity::class, SensorReadingEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sensorReadingDao(): SensorReadingDao
}
