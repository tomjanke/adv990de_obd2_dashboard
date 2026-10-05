package de.adv990de.obd2.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test"

/**
 * Covers [MIGRATION_1_2] and [MIGRATION_2_3], which hand-write their `CREATE TABLE` statements
 * instead of relying on Room's generated migrations — a hand-written migration has no compile-time
 * guarantee of matching the exported schema, so this asserts the resulting table shape (Room validates
 * it against the exported JSON), that rows written before the migration survive it, and that the DAO
 * can actually read/write through the new table. No destructive fallback is configured, so a wrong
 * migration would crash every updating user at the first database open.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate1To2_createsAppLogTableWithExpectedColumns() {
        // Create the DB at v1 (no app_log table yet) using the exported v1 schema.
        helper.createDatabase(TEST_DB, 1).close()

        // Run MIGRATION_1_2 and let Room validate the result against the exported v2 schema.
        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        val columns = mutableSetOf<String>()
        migratedDb.query("PRAGMA table_info(app_log)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex))
            }
        }
        assertEquals(setOf("id", "timestamp", "level", "message"), columns)
        migratedDb.close()
    }

    @Test
    fun migrate1To2_insertAndReadRowAfterMigration() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).close()

        // Re-open through Room itself (not the raw SupportSQLiteDatabase) so the DAO can be used.
        val db = appDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, TEST_DB).build()

        helper.closeWhenFinished(db)

        runBlocking {
            db.appLogDao().insert(
                de.adv990de.obd2.data.db.entity.AppLogEntity(
                    timestamp = 1234L,
                    level = "INFO",
                    message = "hello after migration"
                )
            )
            val entries = db.appLogDao().getAllNewestFirst().first()
            assertEquals(1, entries.size)
            assertEquals("hello after migration", entries[0].message)
        }
    }

    // ── 2 -> 3: the local fault history ───────────────────────────────────────

    @Test
    fun migrate2To3_createsDtcHistoryTableWithExpectedColumns() {
        helper.createDatabase(TEST_DB, 2).close()

        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        val columns = mutableSetOf<String>()
        migratedDb.query("PRAGMA table_info(dtc_history)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns.add(cursor.getString(nameIndex))
        }
        assertEquals(setOf("code", "firstSeen", "lastSeen", "lastWasPending"), columns)
        migratedDb.close()
    }

    @Test
    fun migrate2To3_keepsTripsReadingsAndLogsWrittenBeforeTheUpdate() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL("INSERT INTO sessions (id, startTime, endTime, vehicleVin) VALUES (1, 1000, 2000, 'VIN123')")
            execSQL("INSERT INTO sensor_readings (id, sessionId, timestamp, pidId, value) VALUES (1, 1, 1500, '010C', 1200.0)")
            execSQL("INSERT INTO app_log (id, timestamp, level, message) VALUES (1, 1234, 'INFO', 'before the update')")
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        migratedDb.query("SELECT vehicleVin FROM sessions WHERE id = 1").use {
            assertEquals(1, it.count); it.moveToFirst(); assertEquals("VIN123", it.getString(0))
        }
        migratedDb.query("SELECT value FROM sensor_readings WHERE sessionId = 1").use {
            assertEquals(1, it.count); it.moveToFirst(); assertEquals(1200.0f, it.getFloat(0), 0.001f)
        }
        migratedDb.query("SELECT message FROM app_log").use {
            assertEquals(1, it.count); it.moveToFirst(); assertEquals("before the update", it.getString(0))
        }
        migratedDb.close()
    }

    @Test
    fun migrate1To3_theWholeChainWorks() {
        helper.createDatabase(TEST_DB, 1).close()

        helper.runMigrationsAndValidate(TEST_DB, APP_DATABASE_VERSION, true, *ALL_MIGRATIONS).close()
    }

    @Test
    fun migrate2To3_dtcHistoryIsUsableThroughTheDao() {
        helper.createDatabase(TEST_DB, 2).close()
        helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3).close()

        val db = appDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, TEST_DB).build()
        helper.closeWhenFinished(db)

        runBlocking {
            db.dtcHistoryDao().recordSeen(code = "P0301", now = 5_000L, pending = false)
            val rows = db.dtcHistoryDao().observeAll().first()
            assertEquals(1, rows.size)
            assertEquals("P0301", rows[0].code)
            assertEquals(5_000L, rows[0].firstSeen)
        }
    }
}
