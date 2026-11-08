package de.adv990de.obd2.data

import android.content.Context
import androidx.room.withTransaction
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.SensorReadingDao
import de.adv990de.obd2.data.db.SessionDao
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.transfer.TripMeta
import de.adv990de.obd2.tripimport.ParsedTrip
import de.adv990de.obd2.tripimport.SessionSpan
import de.adv990de.obd2.tripimport.readTripCsv
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.StringReader

/**
 * [SessionRepository.importTrips] und [SessionRepository.sessionSpans] gegen Attrappen der DAOs: was geschrieben wird und in welcher Form. Dass die Transaktion wirklich alles oder nichts
 * ist, zeigt `SessionRepositoryDeviceTest` an einer echten Datenbank.
 */
class SessionRepositoryImportTest {

    private val sessionDao = mockk<SessionDao>()
    private val readingDao = mockk<SensorReadingDao>()
    private val db = mockk<AppDatabase> {
        every { sessionDao() } returns sessionDao
        every { sensorReadingDao() } returns readingDao
    }
    private val repo = SessionRepository(mockk<Context>(), db)

    private val inserted = ArrayList<SessionEntity>()
    private val written = ArrayList<List<SensorReadingEntity>>()
    private var inTransaction = false
    private var nextId = 100L

    @Before
    fun setup() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { db.withTransaction(capture(block)) } coAnswers {
            inTransaction = true
            try { block.captured.invoke() } finally { inTransaction = false }
        }
        coEvery { sessionDao.insert(any()) } coAnswers {
            assertTrue("a trip is inserted inside the transaction", inTransaction)
            inserted += firstArg<SessionEntity>()
            nextId++
        }
        coEvery { readingDao.insertAll(any()) } coAnswers {
            assertTrue("the readings are inserted inside the transaction", inTransaction)
            written += firstArg<List<SensorReadingEntity>>()
        }
    }

    @After
    fun teardown() = unmockkStatic("androidx.room.RoomDatabaseKt")

    /** Eine Fahrt der Datei: [rows] Zeilen ab [start], abwechselnd Drehzahl (ECU) und Geschwindigkeit (GPS). */
    private fun trips(vararg specs: Triple<String, Long, Int>): List<ParsedTrip> {
        val text = "timestamp,pid,value,name,source,session\n" + specs.joinToString("") { (id, start, rows) ->
            (0 until rows).joinToString("") { i ->
                val pid = if (i % 2 == 0) "010C" else "010D"
                val source = if (i % 2 == 0) "ECU" else "GPS"
                "${start + i * 1_000L},$pid,$i.5,n,$source,$id\n"
            }
        }
        return readTripCsv(StringReader(text), nowMs = 1_800_000_000_000L).trips
    }

    private val t0 = 1_790_000_000_000L

    @Test
    fun `every trip becomes a new session with its first and last timestamp and without VIN and fault codes`() = runTest {
        repo.importTrips(trips(Triple("7", t0, 5), Triple("9", t0 + 100_000, 3)))

        assertEquals(
            listOf(
                SessionEntity(startTime = t0, endTime = t0 + 4_000, vehicleVin = null, dtcCodes = null),
                SessionEntity(startTime = t0 + 100_000, endTime = t0 + 102_000, vehicleVin = null, dtcCodes = null),
            ),
            inserted,
        )
    }

    @Test
    fun `the readings carry the id of their new session and their original values`() = runTest {
        repo.importTrips(trips(Triple("7", t0, 3), Triple("9", t0 + 100_000, 2)))

        assertEquals(2, written.size)
        assertEquals(
            listOf(
                SensorReadingEntity(sessionId = 100, timestamp = t0, pidId = "010C", value = 0.5f, source = "ECU"),
                SensorReadingEntity(sessionId = 100, timestamp = t0 + 1_000, pidId = "010D", value = 1.5f, source = "GPS"),
                SensorReadingEntity(sessionId = 100, timestamp = t0 + 2_000, pidId = "010C", value = 2.5f, source = "ECU"),
            ),
            written[0],
        )
        assertEquals(listOf(101L, 101L), written[1].map { it.sessionId })
    }

    @Test
    fun `the VIN and the fault codes of a trip go into the same insert as the trip`() = runTest {
        val metas = listOf(TripMeta("TESTVIN0000000001", "P0301,P0420?"), TripMeta(null, ""), TripMeta(null, null))
        val parsed = trips(Triple("7", t0, 2), Triple("8", t0 + 100_000, 2), Triple("9", t0 + 200_000, 2)).mapIndexed { i, t -> t.withMeta(metas[i]) }

        repo.importTrips(parsed)

        assertEquals(listOf("TESTVIN0000000001", null, null), inserted.map { it.vehicleVin })
        assertEquals(listOf<String?>("P0301,P0420?", "", null), inserted.map { it.dtcCodes })
    }

    @Test
    fun `a trip without meta stays not recorded - never none found`() = runTest {
        repo.importTrips(trips(Triple("7", t0, 2)).map { it.withMeta(null) })

        assertEquals(null, inserted.single().dtcCodes)
        assertEquals(null, inserted.single().vehicleVin)
    }

    @Test
    fun `a reading without a known source is stored without one - not guessed as ECU`() = runTest {
        val text = "timestamp,pid,value\n$t0,010C,1612.0\n"
        repo.importTrips(readTripCsv(StringReader(text), 1_800_000_000_000L).trips)

        assertEquals(null, written.single().single().source)
    }

    @Test
    fun `many readings go to the database in chunks of a thousand, in file order`() = runTest {
        repo.importTrips(trips(Triple("7", t0, 2_500)))

        assertEquals(listOf(1_000, 1_000, 500), written.map { it.size })
        assertEquals((0 until 2_500).map { t0 + it * 1_000L }, written.flatten().map { it.timestamp })
    }

    @Test
    fun `a trip of exactly one chunk is one call, no empty one after it`() = runTest {
        repo.importTrips(trips(Triple("7", t0, 1_000)))

        assertEquals(listOf(1_000), written.map { it.size })
    }

    @Test
    fun `a failure while writing is passed on - the transaction around it rolls everything back`() = runTest {
        coEvery { readingDao.insertAll(any()) } throws IOException("disk full")

        assertThrows(IOException::class.java) { runBlocking { repo.importTrips(trips(Triple("7", t0, 5))) } }

        assertFalse("the transaction is over", inTransaction)
    }

    @Test
    fun `a cancelled caller stops between two chunks and the cancellation goes through the transaction`() = runBlocking {
        lateinit var caller: Job
        var calls = 0
        coEvery { readingDao.insertAll(any()) } coAnswers { calls++; caller.cancel() }
        val trips = trips(Triple("7", t0, 3_000))

        caller = launch { repo.importTrips(trips) }
        caller.join()

        assertTrue(caller.isCancelled)
        assertEquals("no chunk after the cancellation", 1, calls)
    }

    @Test
    fun `sessionSpans asks the database for the begin and end of every trip`() = runTest {
        val spans = listOf(SessionSpan(1, 2), SessionSpan(3, 4))
        coEvery { sessionDao.spans() } returns spans

        assertEquals(spans, repo.sessionSpans())
        coVerify(exactly = 1) { sessionDao.spans() }
    }

    @Test
    fun `the retention the import honours is the one the app deletes by`() {
        assertEquals(90L * 24 * 60 * 60 * 1000, SESSION_RETENTION_MS)
    }
}
