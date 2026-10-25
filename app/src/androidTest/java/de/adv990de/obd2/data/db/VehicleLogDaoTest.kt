package de.adv990de.obd2.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.data.db.entity.VehicleLogEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [VehicleLogDao] an einer echten Datenbank: Reihenfolge, Speichern, alles-oder-nichts beim Import, Loeschen, letzter Stand. */
@RunWith(AndroidJUnit4::class)
class VehicleLogDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: VehicleLogDao

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.vehicleLogDao()
    }

    @After
    fun teardown() = db.close()

    private fun row(type: String = "FUEL", epochDay: Long = 20_000, km: Int = 1000, id: Long = 0) = VehicleLogEntity(
        id = id, type = type, epochDay = epochDay, odometerKm = km,
        amountMl = null, fullTank = null, costCents = null, note = null, brand = null,
    )

    private fun newestFirst() = runBlocking { dao.observeAll().first() }

    @Test
    fun theListIsNewestFirst_byDay_thenReading_thenCreationOrder() = runBlocking {
        val a = dao.upsert(row(epochDay = 20_000, km = 1000))
        val b = dao.upsert(row(epochDay = 20_002, km = 900))
        val c = dao.upsert(row(epochDay = 20_002, km = 950))
        val d = dao.upsert(row(epochDay = 20_002, km = 950))   // gleicher Tag und Stand: die spaetere Anlage zuerst

        assertEquals(listOf(d, c, b, a), newestFirst().map { it.id })
        assertEquals("der Export liest die umgekehrte Reihenfolge", listOf(a, b, c, d), dao.getAll().map { it.id })
    }

    @Test
    fun saving_createsANewRowForIdZero_andReplacesARowWithAnExistingId() = runBlocking {
        val id = dao.upsert(row(km = 1000))
        assertTrue(id > 0)

        val again = dao.upsert(row(km = 1500, id = id).copy(note = "korrigiert"))

        assertEquals(id, again)
        val rows = newestFirst()
        assertEquals("keine zweite Zeile", 1, rows.size)
        assertEquals(1500, rows.single().odometerKm)
        assertEquals("korrigiert", rows.single().note)
    }

    @Test
    fun everyFieldSurvivesTheRoundTrip_andTheThreeStatesOfFullTankStayApart() = runBlocking {
        val full = dao.upsert(row().copy(amountMl = 14_370, fullTank = true, costCents = 2_743, note = "Shell, \"Autobahn\"\nzweite Zeile"))
        val partial = dao.upsert(row(km = 1001).copy(amountMl = 5_000, fullTank = false))
        val unsaid = dao.upsert(row(type = "TIRE_CHANGE", km = 1002).copy(brand = "Michelin Anakee Adventure 150/70-18"))

        val a = dao.getById(full)!!
        assertEquals(14_370, a.amountMl); assertEquals(true, a.fullTank); assertEquals(2_743, a.costCents)
        assertEquals("Shell, \"Autobahn\"\nzweite Zeile", a.note)
        assertEquals(false, dao.getById(partial)!!.fullTank)
        val c = dao.getById(unsaid)!!
        assertNull("nicht gesagt ist nicht Teiltankung", c.fullTank)
        assertNull(c.amountMl); assertNull(c.costCents); assertNull(c.note)
        assertEquals("Michelin Anakee Adventure 150/70-18", c.brand)
    }

    @Test
    fun anImportWritesEveryRowAsANewRow() = runBlocking {
        dao.insertAll(listOf(row(km = 1), row(km = 2), row(km = 3)))

        val ids = dao.getAll().map { it.id }
        assertEquals(3, ids.size)
        assertEquals("die Datenbank vergibt verschiedene Ids", 3, ids.toSet().size)
    }

    @Test
    fun anImportThatFailsInTheMiddleLeavesTheTableUnchanged() = runBlocking {
        dao.upsert(row(km = 100))
        val before = dao.getAll()

        // zwei Zeilen mit derselben ausdruecklichen Id: die zweite verletzt den Primaerschluessel
        val failure = runCatching { dao.insertAll(listOf(row(km = 1, id = 50), row(km = 2, id = 51), row(km = 3, id = 50))) }.exceptionOrNull()

        assertTrue("der Fehler wird nicht geschluckt: $failure", failure != null)
        assertEquals("keine der ersten beiden Zeilen blieb stehen", before, dao.getAll())
    }

    @Test
    fun deletingRemovesOnlyTheGivenIds() = runBlocking {
        val a = dao.upsert(row(km = 1)); val b = dao.upsert(row(km = 2)); val c = dao.upsert(row(km = 3))

        dao.deleteByIds(listOf(a, c))

        assertEquals(listOf(b), dao.getAll().map { it.id })
    }

    @Test
    fun theLatestReadingIsTheHighestStand_andOnATieTheNewerDay() = runBlocking {
        assertNull("ohne Eintraege kein Stand", dao.latestByOdometer())
        dao.upsert(row(epochDay = 20_005, km = 900))
        val older = dao.upsert(row(epochDay = 20_001, km = 1200))
        assertEquals(older, dao.latestByOdometer()!!.id)

        val newer = dao.upsert(row(epochDay = 20_003, km = 1200))

        assertEquals(newer, dao.latestByOdometer()!!.id)
    }
}
