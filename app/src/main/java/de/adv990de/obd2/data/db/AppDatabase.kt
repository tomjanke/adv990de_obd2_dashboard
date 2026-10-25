package de.adv990de.obd2.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.adv990de.obd2.data.db.entity.AppLogEntity
import de.adv990de.obd2.data.db.entity.DtcHistoryEntity
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.data.db.entity.VehicleLogEntity

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

/**
 * Die Herkunft eines Messwerts (`ECU`/`GPS`). Nullable und ohne Vorgabewert: bestehende Zeilen bleiben `NULL` = "unbekannt". Von Hand
 * geschrieben wie [MIGRATION_1_2]; `AppDatabaseMigrationTest` validiert sie gegen das exportierte 4.json und prueft, dass Altdaten bleiben.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sensor_readings ADD COLUMN source TEXT")
    }
}

/**
 * Die Fehlercodes einer Fahrt bei ihrem Beginn (`sessions.dtcCodes`, Format: `SessionDtcs.kt`). Nullable und ohne Vorgabewert: bestehende
 * Fahrten bleiben `NULL` = "nicht erfasst" — ein "keine Codes" (leerer Text) waere eine Behauptung, die niemand pruefte. Von Hand
 * geschrieben wie [MIGRATION_3_4]; `AppDatabaseMigrationTest` validiert sie gegen das exportierte 5.json und prueft, dass Altdaten bleiben.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN dtcCodes TEXT")
    }
}

/**
 * Das Logbuch (`vehicle_log`, s. [VehicleLogEntity]). Neue Tabelle, nichts Bestehendes wird angefasst: Fahrten, Messwerte, Logs und Fehlerhistorie bleiben. Von Hand
 * geschrieben wie [MIGRATION_1_2]; `AppDatabaseMigrationTest` validiert sie gegen das exportierte 6.json und prueft, dass Altdaten bleiben.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE vehicle_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, " +
            "epochDay INTEGER NOT NULL, odometerKm INTEGER NOT NULL, amountMl INTEGER, fullTank INTEGER, " +
            "costCents INTEGER, note TEXT, brand TEXT)"
        )
    }
}

/**
 * Die Version der Datenbank — die EINE Stelle (`@Database(version = …)` unten und die Pruefung in
 * `AppDatabaseMigrationsTest`, dass [ALL_MIGRATIONS] bis hierher reicht, lesen sie).
 */
const val APP_DATABASE_VERSION = 6

/**
 * Alle Migrationen, in der Reihenfolge ihrer Versionen. Produktion ([appDatabaseBuilder]) und Tests benutzen
 * DIESE Liste statt sie von Hand nachzubauen (B36): Wer eine Migration schreibt, traegt sie hier ein — fehlt sie,
 * oder fehlt sie fuer eine neue Version, schlaegt `AppDatabaseMigrationsTest` auf dem JVM fehl, statt dass ein
 * Update-Nutzer beim ersten Start abstuerzt.
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

/**
 * Der Produktions-Builder der Datenbank: Name und Migrationen. Kein destruktiver Fallback — ein Schema-Fehler
 * soll laut werden, nicht still Fahrten loeschen. Tests bauen ihre Datenbank hierueber (statt `Room.databaseBuilder`
 * mit nachgebauter Konfiguration), damit sie das pruefen, was ausgeliefert wird.
 *
 * Fremdschluessel: SQLite schaltet sie je Verbindung ab; Rooms generierter `onOpen` setzt
 * `PRAGMA foreign_keys = ON` selbst (`AppDatabaseBootstrapTest` weist es am Geraet nach). Ein eigener
 * Callback dafuer war bis 1.24.4 redundant.
 */
fun appDatabaseBuilder(context: Context, name: String?): RoomDatabase.Builder<AppDatabase> =
    Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*ALL_MIGRATIONS)

@Database(
    entities = [SessionEntity::class, SensorReadingEntity::class, AppLogEntity::class, DtcHistoryEntity::class, VehicleLogEntity::class],
    version = APP_DATABASE_VERSION,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sensorReadingDao(): SensorReadingDao
    abstract fun appLogDao(): AppLogDao
    abstract fun dtcHistoryDao(): DtcHistoryDao
    abstract fun vehicleLogDao(): VehicleLogDao
}
