package de.adv990de.obd2.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.sqlite.db.SupportSQLiteQuery
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.SessionDao
import de.adv990de.obd2.data.db.SensorReadingDao
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.obd.model.ValueSource
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val sessionDao = mockk<SessionDao> {
        // SessionRepository eagerly calls sessionDao().getAll() in its property initializer,
        // so this stub must be present before the repository is constructed.
        every { getAll() } returns emptyFlow()
    }
    private val sensorReadingDao = mockk<SensorReadingDao>()
    private val db = mockk<AppDatabase> {
        every { sessionDao() } returns sessionDao
        every { sensorReadingDao() } returns sensorReadingDao
    }
    // Named (rather than inlined into the constructor call below) so exportToCsv tests can stub
    // cacheDir/packageName on it directly.
    private val context = mockk<Context>()
    private val repo = SessionRepository(context, db)

    @After
    fun tearDown() {
        clearAllMocks()
        // No-op if a given test never called mockkStatic(FileProvider::class) itself.
        unmockkStatic(FileProvider::class)
    }

    // Builds a Cursor stub over the given (timestamp, pidId, value) rows, matching the column
    // names and iteration pattern exportToCsv() actually reads (getColumnIndexOrThrow once per
    // column, then getLong/getString/getFloat per row while moveToNext() is true).
    /** Eine Zeile des Exports: Zeit, PID, Wert und die Herkunft (`null` = eine Zeile aus der Zeit vor 1.25.0). */
    private data class Row(val timestamp: Long, val pidId: String, val value: Float, val source: String?)

    private fun makeCursor(rows: List<Triple<Long, String, Float>>): Cursor =
        makeSourceCursor(rows.map { Row(it.first, it.second, it.third, source = null) })

    private fun makeSourceCursor(rows: List<Row>): Cursor {
        var index = -1
        return mockk<Cursor> {
            every { getColumnIndexOrThrow("timestamp") } returns 0
            every { getColumnIndexOrThrow("pidId") } returns 1
            every { getColumnIndexOrThrow("value") } returns 2
            every { getColumnIndexOrThrow("source") } returns 3
            every { moveToNext() } answers { index++; index < rows.size }
            every { getLong(0) } answers { rows[index].timestamp }
            every { getString(1) } answers { rows[index].pidId }
            every { getFloat(2) } answers { rows[index].value }
            every { getString(3) } answers { rows[index].source }
            every { close() } just Runs
        }
    }

    // labelRes = 0: dieser Test rendert nichts, der Anzeigename wird nie aufgeloest.
    private fun makePid(id: String) = PidDefinition(
        id = id, name = "Test", labelRes = 0, unit = "", minValue = 0f, maxValue = 100f, formula = { 0f }
    )

    // ── startSession ──────────────────────────────────────────────────────────

    @Test
    fun `startSession inserts entity and returns id`() = runTest {
        val inserted = slot<SessionEntity>()
        coEvery { sessionDao.insert(capture(inserted)) } returns 42L

        val id = repo.startSession()

        assertEquals(42L, id)
        coVerify(exactly = 1) { sessionDao.insert(any()) }
        assertNull("vehicleVin should be null when no VIN is passed", inserted.captured.vehicleVin)
    }

    @Test
    fun `startSession with VIN inserts entity with VIN`() = runTest {
        val inserted = slot<SessionEntity>()
        coEvery { sessionDao.insert(capture(inserted)) } returns 1L

        repo.startSession(vin = "1HGBH41JXMN109186")

        assertEquals("1HGBH41JXMN109186", inserted.captured.vehicleVin)
    }

    // Die Fehlercodes gehen in DEMSELBEN Insert mit hinein (kein zweiter Schritt, den ein Prozess-Kill trennen koennte), in den drei Zustaenden.

    @Test
    fun `startSession stores the fault codes in the same insert, pending ones marked`() = runTest {
        val inserted = slot<SessionEntity>()
        coEvery { sessionDao.insert(capture(inserted)) } returns 1L

        repo.startSession(vin = null, dtcCodes = listOf(DtcEntry("P0301", false), DtcEntry("P0420", true)))

        assertEquals("P0301,P0420?", inserted.captured.dtcCodes)
        coVerify(exactly = 1) { sessionDao.insert(any()) }
    }

    @Test
    fun `startSession stores an empty text when the read found no codes`() = runTest {
        val inserted = slot<SessionEntity>()
        coEvery { sessionDao.insert(capture(inserted)) } returns 1L

        repo.startSession(vin = null, dtcCodes = emptyList())

        assertEquals("gelesen, nichts gefunden: leer, nicht null", "", inserted.captured.dtcCodes)
    }

    @Test
    fun `startSession stores null when the codes were not recorded`() = runTest {
        val inserted = slot<SessionEntity>()
        coEvery { sessionDao.insert(capture(inserted)) } returns 1L

        repo.startSession()

        assertNull("nicht erfasst: null", inserted.captured.dtcCodes)
    }

    // ── endSession ────────────────────────────────────────────────────────────

    @Test
    fun `endSession sets endTime on existing session`() = runTest {
        val updated = slot<SessionEntity>()
        coEvery { sessionDao.getById(1L) } returns SessionEntity(id = 1L, startTime = 1000L)
        coEvery { sessionDao.update(capture(updated)) } just Runs

        repo.endSession(1L)

        assertNotNull("endTime should be set after endSession", updated.captured.endTime)
    }

    @Test
    fun `endSession is no-op when session not found`() = runTest {
        coEvery { sessionDao.getById(99L) } returns null

        repo.endSession(99L)

        coVerify(exactly = 0) { sessionDao.update(any()) }
    }

    @Test
    fun `endSession still completes when the calling coroutine is already cancelled`() = runTest {
        // ReadingsPipeline ends the trip in a finally block — and a trip normally ends BY cancellation
        // (disconnect, slot change, app closing). withContext() throws at once in a cancelled caller,
        // so without NonCancellable the trip never got its endTime.
        coEvery { sessionDao.getById(1L) } returns SessionEntity(id = 1L, startTime = 1000L)
        coEvery { sessionDao.update(any()) } just Runs

        val caller = launch {
            try {
                awaitCancellation()
            } finally {
                repo.endSession(1L)
            }
        }
        runCurrent()
        caller.cancelAndJoin()

        coVerify(exactly = 1) { sessionDao.update(any()) }
    }

    // ── writeReadings ─────────────────────────────────────────────────────────

    @Test
    fun `writeReadings inserts correctly mapped entities`() = runTest {
        val pid1 = makePid("010C")
        val pid2 = makePid("010D")
        val sv1 = SensorValue(pid = pid1, value = 1200f, timestamp = 1_000L)
        val sv2 = SensorValue(pid = pid2, value = 80f,   timestamp = 2_000L)

        val inserted = slot<List<SensorReadingEntity>>()
        coEvery { sensorReadingDao.insertAll(capture(inserted)) } just Runs

        repo.writeReadings(sessionId = 5L, values = listOf(sv1, sv2))

        val entities = inserted.captured
        assertEquals(2, entities.size)

        assertEquals(5L,     entities[0].sessionId)
        assertEquals("010C", entities[0].pidId)
        assertEquals(1200f,  entities[0].value)
        assertEquals(1_000L, entities[0].timestamp)

        assertEquals(5L,     entities[1].sessionId)
        assertEquals("010D", entities[1].pidId)
        assertEquals(80f,    entities[1].value)
        assertEquals(2_000L, entities[1].timestamp)
    }

    @Test
    fun `writeReadings stores where each value came from`() = runTest {
        // Die GPS-Geschwindigkeit hat dieselbe PID-ID wie die des Fahrzeugs; die Spalte `source` haelt sie auseinander.
        val ecu = SensorValue(pid = makePid("010D"), value = 50f, timestamp = 1_000L)
        val gps = SensorValue(pid = makePid("010D"), value = 49f, timestamp = 2_000L, source = ValueSource.GPS)
        val inserted = slot<List<SensorReadingEntity>>()
        coEvery { sensorReadingDao.insertAll(capture(inserted)) } just Runs

        repo.writeReadings(sessionId = 5L, values = listOf(ecu, gps))

        assertEquals(listOf("ECU", "GPS"), inserted.captured.map { it.source })
    }

    // ── active trip ───────────────────────────────────────────────────────────

    @Test
    fun `no trip is active before the first session starts`() {
        assertNull(repo.activeSessionId.value)
    }

    @Test
    fun `startSession publishes the new id as the active trip`() = runTest {
        coEvery { sessionDao.insert(any()) } returns 42L

        repo.startSession()

        assertEquals(42L, repo.activeSessionId.value)
    }

    @Test
    fun `endSession clears the active trip`() = runTest {
        coEvery { sessionDao.insert(any()) } returns 5L
        coEvery { sessionDao.getById(5L) } returns SessionEntity(id = 5L, startTime = 1000L)
        coEvery { sessionDao.update(any()) } just Runs
        repo.startSession()

        repo.endSession(5L)

        assertNull(repo.activeSessionId.value)
    }

    @Test
    fun `endSession clears the active trip even when the update fails`() = runTest {
        coEvery { sessionDao.insert(any()) } returns 5L
        coEvery { sessionDao.getById(5L) } returns SessionEntity(id = 5L, startTime = 1000L)
        coEvery { sessionDao.update(any()) } throws IllegalStateException("disk full")
        repo.startSession()

        runCatching { repo.endSession(5L) }

        // A stale "active" id would make the finished trip undeletable until the next recording.
        assertNull(repo.activeSessionId.value)
    }

    @Test
    fun `ending another trip leaves the active trip alone`() = runTest {
        coEvery { sessionDao.insert(any()) } returns 8L
        coEvery { sessionDao.getById(3L) } returns SessionEntity(id = 3L, startTime = 1000L)
        coEvery { sessionDao.update(any()) } just Runs
        repo.startSession()

        repo.endSession(3L)

        assertEquals(8L, repo.activeSessionId.value)
    }

    // ── startSession is atomic with respect to cancellation ────────────────

    @Test
    fun `startSession in an already cancelled coroutine creates no trip`() = runTest {
        coEvery { sessionDao.insert(any()) } returns 6L

        var refused = false
        val caller = launch {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                try { repo.startSession("VIN") } catch (_: CancellationException) { refused = true }
                throw e
            }
        }
        runCurrent()
        caller.cancelAndJoin()

        assertTrue("a cancelled caller must not start a trip", refused)
        coVerify(exactly = 0) { sessionDao.insert(any()) }
        assertNull(repo.activeSessionId.value)
    }

    @Test
    fun `a caller cancelled while the trip is being inserted does not leave an orphan trip behind`() = runTest {
        // withContext verwirft das Ergebnis eines abgebrochenen Aufrufers: die Pipeline bekaeme die Id nie
        // und koennte die Fahrt nie beenden — eine leere Zeile, fuer immer als "aktiv" geschuetzt.
        val insertStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { sessionDao.insert(any()) } coAnswers { insertStarted.complete(Unit); release.await(); 6L }
        coEvery { sensorReadingDao.deleteBySessions(any()) } just Runs
        coEvery { sessionDao.deleteByIds(any()) } just Runs

        val caller = launch { repo.startSession("VIN") }
        insertStarted.await()
        caller.cancel()
        release.complete(Unit)
        caller.join()

        assertNull("the empty trip must not stay active", repo.activeSessionId.value)
        coVerify(exactly = 1) { sessionDao.deleteByIds(listOf(6L)) }
    }

    // ── discardSession (the pipeline drops a trip without DTCs) ──────────────

    @Test
    fun `discardSession clears the active trip and deletes its readings and the session`() = runTest {
        coEvery { sessionDao.insert(any()) } returns 6L
        coEvery { sensorReadingDao.deleteBySessions(any()) } just Runs
        coEvery { sessionDao.deleteByIds(any()) } just Runs
        repo.startSession()

        repo.discardSession(6L)

        assertNull(repo.activeSessionId.value)
        coVerify(exactly = 1) { sensorReadingDao.deleteBySessions(listOf(6L)) }
        coVerify(exactly = 1) { sessionDao.deleteByIds(listOf(6L)) }
    }

    @Test
    fun `discardSession still completes when the calling coroutine is already cancelled`() = runTest {
        coEvery { sensorReadingDao.deleteBySessions(any()) } just Runs
        coEvery { sessionDao.deleteByIds(any()) } just Runs

        val caller = launch {
            try {
                awaitCancellation()
            } finally {
                repo.discardSession(6L)
            }
        }
        runCurrent()
        caller.cancelAndJoin()

        coVerify(exactly = 1) { sessionDao.deleteByIds(listOf(6L)) }
    }

    // ── deleteSessions (the user deletes trips from the history) ─────────────

    @Test
    fun `deleteSessions deletes the readings and the sessions of the given ids`() = runTest {
        coEvery { sensorReadingDao.deleteBySessions(any()) } just Runs
        coEvery { sessionDao.deleteByIds(any()) } just Runs

        repo.deleteSessions(listOf(3L, 4L))

        coVerify(exactly = 1) { sensorReadingDao.deleteBySessions(listOf(3L, 4L)) }
        coVerify(exactly = 1) { sessionDao.deleteByIds(listOf(3L, 4L)) }
    }

    @Test
    fun `deleteSessions never deletes the trip that is being recorded`() = runTest {
        coEvery { sessionDao.insert(any()) } returns 9L
        coEvery { sensorReadingDao.deleteBySessions(any()) } just Runs
        coEvery { sessionDao.deleteByIds(any()) } just Runs
        repo.startSession()

        repo.deleteSessions(listOf(3L, 9L, 4L))

        coVerify(exactly = 1) { sessionDao.deleteByIds(listOf(3L, 4L)) }
        coVerify(exactly = 0) { sessionDao.deleteByIds(match { 9L in it }) }
        coVerify(exactly = 0) { sensorReadingDao.deleteBySessions(match { 9L in it }) }
    }

    @Test
    fun `deleteSessions of only the active trip touches nothing`() = runTest {
        coEvery { sessionDao.insert(any()) } returns 9L
        repo.startSession()

        repo.deleteSessions(listOf(9L))

        coVerify(exactly = 0) { sessionDao.deleteByIds(any()) }
        coVerify(exactly = 0) { sensorReadingDao.deleteBySessions(any()) }
    }

    @Test
    fun `deleteSessions with an empty collection touches nothing`() = runTest {
        repo.deleteSessions(emptyList())

        coVerify(exactly = 0) { sessionDao.deleteByIds(any()) }
        coVerify(exactly = 0) { sensorReadingDao.deleteBySessions(any()) }
    }

    @Test
    fun `deleteSessions ignores duplicate ids`() = runTest {
        coEvery { sensorReadingDao.deleteBySessions(any()) } just Runs
        coEvery { sessionDao.deleteByIds(any()) } just Runs

        repo.deleteSessions(listOf(3L, 3L, 4L))

        coVerify(exactly = 1) { sessionDao.deleteByIds(listOf(3L, 4L)) }
    }

    @Test
    fun `deleteSessions works in chunks so no statement exceeds the SQLite variable limit`() = runTest {
        coEvery { sensorReadingDao.deleteBySessions(any()) } just Runs
        coEvery { sessionDao.deleteByIds(any()) } just Runs
        val ids = (1L..120L).toList()

        repo.deleteSessions(ids)

        // 120 ids -> chunks of 50, 50 and 20; the readings go first within each chunk.
        coVerifyOrder {
            sensorReadingDao.deleteBySessions(ids.subList(0, 50))
            sessionDao.deleteByIds(ids.subList(0, 50))
            sensorReadingDao.deleteBySessions(ids.subList(50, 100))
            sessionDao.deleteByIds(ids.subList(50, 100))
            sensorReadingDao.deleteBySessions(ids.subList(100, 120))
            sessionDao.deleteByIds(ids.subList(100, 120))
        }
    }

    // ── deleteOldSessions ─────────────────────────────────────────────────────

    @Test
    fun `deleteOldSessions asks for the trips older than the cutoff calculated from maxAgeMs`() = runTest {
        val captor = slot<Long>()
        coEvery { sessionDao.idsOlderThan(capture(captor)) } returns emptyList()

        val maxAgeMs = 1_000L
        val before = System.currentTimeMillis()
        repo.deleteOldSessions(maxAgeMs)
        val after = System.currentTimeMillis()

        val cutoff = captor.captured
        assertTrue("cutoff should be within [before-maxAgeMs, after-maxAgeMs]",
            cutoff in (before - maxAgeMs)..(after - maxAgeMs))
    }

    @Test
    fun `deleteOldSessions deletes in chunks of 50 so the recorder is never blocked for long`() = runTest {
        val ids = (1L..120L).toList()
        coEvery { sessionDao.idsOlderThan(any()) } returns ids
        coEvery { sensorReadingDao.deleteBySessions(any()) } just Runs
        coEvery { sessionDao.deleteByIds(any()) } just Runs

        repo.deleteOldSessions(1_000L)

        coVerifyOrder {
            sensorReadingDao.deleteBySessions(ids.subList(0, 50)); sessionDao.deleteByIds(ids.subList(0, 50))
            sensorReadingDao.deleteBySessions(ids.subList(50, 100)); sessionDao.deleteByIds(ids.subList(50, 100))
            sensorReadingDao.deleteBySessions(ids.subList(100, 120)); sessionDao.deleteByIds(ids.subList(100, 120))
        }
    }

    @Test
    fun `deleteOldSessions never touches the trip that is being recorded`() = runTest {
        coEvery { sessionDao.insert(any()) } returns 6L
        coEvery { sessionDao.idsOlderThan(any()) } returns listOf(5L, 6L, 7L)
        coEvery { sensorReadingDao.deleteBySessions(any()) } just Runs
        coEvery { sessionDao.deleteByIds(any()) } just Runs
        repo.startSession()

        repo.deleteOldSessions(1_000L)

        coVerify(exactly = 1) { sessionDao.deleteByIds(listOf(5L, 7L)) }
        coVerify(exactly = 0) { sessionDao.deleteByIds(match { 6L in it }) }
    }

    @Test
    fun `deleteOldSessions with nothing old deletes nothing`() = runTest {
        coEvery { sessionDao.idsOlderThan(any()) } returns emptyList()

        repo.deleteOldSessions(1_000L)

        coVerify(exactly = 0) { sessionDao.deleteByIds(any()) }
        coVerify(exactly = 0) { sensorReadingDao.deleteBySessions(any()) }
    }

    // ── exportToCsv ───────────────────────────────────────────────────────────
    //
    // These tests let exportToCsv()'s real body run against real files under tempFolder — only
    // the sensorReadingDao cursor and the static FileProvider.getUriForFile(...) call (which pulls
    // in PackageManager/ContentProvider resolution that isn't available in a plain JVM unit test)
    // are stubbed. Everything else (directory creation, CSV writing, column order, formatting) is
    // real and verified by reading the file back from disk.

    /** The one file the export wrote (its name carries the export time). */
    private fun exportedFiles(): List<File> =
        File(tempFolder.root, "exports").listFiles()!!.filter { it.isFile }.sortedBy { it.name }

    private fun stubExportEnvironment() {
        every { context.cacheDir } returns tempFolder.root
        every { context.packageName } returns "de.adv990de.obd2"
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns mockk<Uri>()
    }

    @Test
    fun `exportToCsv writes header and rows to a real CSV file in query order`() = runTest {
        stubExportEnvironment()
        val queryCaptor = slot<SupportSQLiteQuery>()
        every { sensorReadingDao.getBySessionCursor(capture(queryCaptor)) } returns makeCursor(
            listOf(
                Triple(1_000L, "010C", 1200f),
                Triple(2_000L, "010D", 80.5f)
            )
        )

        val uri = repo.exportToCsv(sessionId = 7L)

        assertNotNull("exportToCsv should return a non-null shareable Uri", uri)
        verify(exactly = 1) { sensorReadingDao.getBySessionCursor(any()) }
        assertTrue(
            "query should filter sensor_readings by the given sessionId",
            queryCaptor.captured.sql.contains("WHERE sessionId = ?")
        )
        assertEquals("the export uses the shared query constant", EXPORT_QUERY_SQL, queryCaptor.captured.sql)

        val files = exportedFiles()
        assertEquals(1, files.size)
        assertTrue("name carries the trip id: ${files[0].name}", files[0].name.startsWith("session_7_"))
        assertEquals(
            "timestamp,pid,value,name,source\n1000,010C,1200.0,RPM,\n2000,010D,80.5,Speed,\n",
            files[0].readText()
        )
    }

    @Test
    fun `the export carries the source column - an old row without one stays empty, not guessed`() = runTest {
        stubExportEnvironment()
        every { sensorReadingDao.getBySessionCursor(any()) } returns makeSourceCursor(
            listOf(
                Row(1_000L, "010D", 50f, "ECU"),
                Row(2_000L, "010D", 49f, "GPS"),
                Row(3_000L, "010D", 48f, null), // aufgezeichnet vor 1.25.0: die Herkunft kennt niemand
            )
        )

        repo.exportToCsv(sessionId = 7L)

        assertEquals(
            "timestamp,pid,value,name,source\n1000,010D,50.0,Speed,ECU\n2000,010D,49.0,Speed,GPS\n3000,010D,48.0,Speed,\n",
            exportedFiles().single().readText(),
        )
    }

    @Test
    fun `exportToCsv writes only the header row when the session has no readings`() = runTest {
        stubExportEnvironment()
        every { sensorReadingDao.getBySessionCursor(any()) } returns makeCursor(emptyList())

        repo.exportToCsv(sessionId = 99L)

        assertEquals("timestamp,pid,value,name,source\n", exportedFiles().single().readText())
    }

    @Test
    fun `a PID the registry does not know gets an empty name, not a crash`() = runTest {
        stubExportEnvironment()
        every { sensorReadingDao.getBySessionCursor(any()) } returns makeCursor(listOf(Triple(5L, "01FF", 1f)))

        repo.exportToCsv(sessionId = 1L)

        assertEquals("timestamp,pid,value,name,source\n5,01FF,1.0,,\n", exportedFiles().single().readText())
    }

    @Test
    fun `two exports of the same trip are two files - the second never deletes what the first share is reading (DAT-06)`() = runTest {
        stubExportEnvironment()
        every { sensorReadingDao.getBySessionCursor(any()) } answers { makeCursor(listOf(Triple(1L, "010C", 1f))) }

        repo.exportToCsv(sessionId = 7L)
        repo.exportToCsv(sessionId = 7L)

        assertEquals(2, exportedFiles().size)
    }

    @Test
    fun `an export removes exports older than a day first (DAT-07)`() = runTest {
        stubExportEnvironment()
        every { sensorReadingDao.getBySessionCursor(any()) } returns makeCursor(emptyList())
        val dir = File(tempFolder.root, "exports").also { it.mkdirs() }
        val stale = File(dir, "session_1_20200101T000000000Z.csv").also {
            it.writeText("old"); it.setLastModified(System.currentTimeMillis() - EXPORT_MAX_AGE_MS - 60_000)
        }
        val fresh = File(dir, "session_2_20261005T000000000Z.csv").also { it.writeText("fresh") }

        repo.exportToCsv(sessionId = 3L)

        assertTrue("alter Export weg", !stale.exists())
        assertTrue("junger Export bleibt", fresh.exists())
    }

    @Test
    fun `a failing export leaves no half-written file behind`() = runTest {
        stubExportEnvironment()
        every { sensorReadingDao.getBySessionCursor(any()) } throws IllegalStateException("database is locked")

        try { repo.exportToCsv(sessionId = 7L) } catch (_: IllegalStateException) { }

        assertTrue("keine Reste im Export-Ordner", exportedFiles().isEmpty())
    }

    @Test
    fun `purgeStaleExports removes only the old exports and reports how many`() = runTest {
        every { context.cacheDir } returns tempFolder.root
        val dir = File(tempFolder.root, "exports").also { it.mkdirs() }
        File(dir, "session_1_a.csv").also { it.writeText("x"); it.setLastModified(System.currentTimeMillis() - EXPORT_MAX_AGE_MS - 60_000) }
        File(dir, "session_2_b.csv").writeText("y")

        assertEquals(1, repo.purgeStaleExports())
        assertEquals(listOf("session_2_b.csv"), exportedFiles().map { it.name })
    }
}
