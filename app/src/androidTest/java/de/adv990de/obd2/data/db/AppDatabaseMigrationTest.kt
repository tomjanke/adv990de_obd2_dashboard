package de.adv990de.obd2.data.db

import androidx.room.Room
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
 * Covers [MIGRATION_1_2], which hand-writes the `CREATE TABLE app_log` statement instead of
 * relying on Room's generated migration — a hand-written migration has no compile-time guarantee
 * of matching the v2 schema, so this asserts both the resulting table shape and that the DAO can
 * actually read/write through it post-migration.
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
        val db = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            TEST_DB
        ).addMigrations(MIGRATION_1_2).build()

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
}
