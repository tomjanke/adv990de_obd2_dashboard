package de.adv990de.obd2.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.adv990de.obd2.data.db.entity.AppLogEntity
import de.adv990de.obd2.data.db.entity.DtcHistoryEntity
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE app_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "timestamp INTEGER NOT NULL, level TEXT NOT NULL, message TEXT NOT NULL)"
        )
    }
}

/** The local fault history. Hand-written like [MIGRATION_1_2]; `AppDatabaseMigrationTest` validates it against the exported 3.json. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE dtc_history (code TEXT NOT NULL, firstSeen INTEGER NOT NULL, " +
            "lastSeen INTEGER NOT NULL, lastWasPending INTEGER NOT NULL, PRIMARY KEY(code))"
        )
    }
}

@Database(
    entities = [SessionEntity::class, SensorReadingEntity::class, AppLogEntity::class, DtcHistoryEntity::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sensorReadingDao(): SensorReadingDao
    abstract fun appLogDao(): AppLogDao
    abstract fun dtcHistoryDao(): DtcHistoryDao
}
