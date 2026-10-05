package de.adv990de.obd2.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Die Datenbank, wie sie ausgeliefert wird: gebaut ueber [appDatabaseBuilder], dem Builder von `AppModule` — nicht
 * ueber einen nachgebauten `Room.databaseBuilder` mit eigenem Callback (B36). Der Test weist nach, was bis 1.24.4
 * nur angenommen wurde: Room setzt `PRAGMA foreign_keys = ON` selbst, der Callback in `AppModule` war redundant,
 * und `onDelete = CASCADE` greift in der echten Konfiguration.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseBootstrapTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        ctx.deleteDatabase(DB_NAME)
        db = appDatabaseBuilder(ctx, DB_NAME).allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        db.close()
        ctx.deleteDatabase(DB_NAME)
    }

    private fun pragmaForeignKeys(): Int = db.openHelper.writableDatabase
        .query("PRAGMA foreign_keys")
        .use { it.moveToFirst(); it.getInt(0) }

    private fun readingCount(sessionId: Long): Int = db.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM sensor_readings WHERE sessionId = $sessionId")
        .use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun theProductionBuilderEnablesForeignKeys() {
        assertEquals("PRAGMA foreign_keys", 1, pragmaForeignKeys())
    }

    @Test
    fun deletingASessionCascadesToItsReadings() = runBlocking {
        val id = db.sessionDao().insert(SessionEntity(startTime = 1L))
        db.sensorReadingDao().insert(SensorReadingEntity(sessionId = id, timestamp = 1L, pidId = "010C", value = 1f))
        assertEquals(1, readingCount(id))

        db.openHelper.writableDatabase.execSQL("DELETE FROM sessions WHERE id = $id")

        assertEquals("CASCADE entfernt die Messwerte", 0, readingCount(id))
    }

    @Test
    fun aReadingForAMissingSessionIsRejected() = runBlocking {
        val failure = runCatching {
            db.sensorReadingDao().insert(SensorReadingEntity(sessionId = 9_999L, timestamp = 1L, pidId = "010C", value = 1f))
        }.exceptionOrNull()

        assertTrue("Fremdschluessel verletzt: $failure", failure is SQLiteConstraintException)
    }

    private companion object {
        const val DB_NAME = "bootstrap_test_db"
    }
}
