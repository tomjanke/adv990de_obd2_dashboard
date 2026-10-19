package de.adv990de.obd2.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.sqlite.db.SupportSQLiteProgram
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

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
            "timestamp,pid,value,name,source,session\n1000,010C,1200.0,RPM,,7\n2000,010D,80.5,Speed,,7\n",
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
            "timestamp,pid,value,name,source,session\n1000,010D,50.0,Speed,ECU,7\n2000,010D,49.0,Speed,GPS,7\n3000,010D,48.0,Speed,,7\n",
            exportedFiles().single().readText(),
        )
    }

    @Test
    fun `exportToCsv writes only the header row when the session has no readings`() = runTest {
        stubExportEnvironment()
        every { sensorReadingDao.getBySessionCursor(any()) } returns makeCursor(emptyList())

        repo.exportToCsv(sessionId = 99L)

        assertEquals("timestamp,pid,value,name,source,session\n", exportedFiles().single().readText())
    }

    @Test
    fun `a PID the registry does not know gets an empty name, not a crash`() = runTest {
        stubExportEnvironment()
        every { sensorReadingDao.getBySessionCursor(any()) } returns makeCursor(listOf(Triple(5L, "01FF", 1f)))

        repo.exportToCsv(sessionId = 1L)

        assertEquals("timestamp,pid,value,name,source,session\n5,01FF,1.0,,,1\n", exportedFiles().single().readText())
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

    // ── Export mehrerer Fahrten in EINE Datei, und Speichern auf dem Geraet ──────────────

    /** Welche Fahrt die Abfrage meint: liest das gebundene Argument ueber ein Attrappen-Programm aus. */
    private fun boundTripId(query: SupportSQLiteQuery): Long {
        val bound = slot<Long>()
        val program = mockk<SupportSQLiteProgram>(relaxed = true) { every { bindLong(1, capture(bound)) } just Runs }
        query.bindTo(program)
        return bound.captured
    }

    /** Je Fahrt ein eigener Cursor, gefragt wird nach der gebundenen Fahrt-ID. */
    private fun stubTrips(rowsByTrip: Map<Long, List<Triple<Long, String, Float>>>) {
        every { sensorReadingDao.getBySessionCursor(any()) } answers { makeCursor(rowsByTrip[boundTripId(firstArg())].orEmpty()) }
    }

    @Test
    fun `exportSessionsToCsv writes one header and the trips one after the other, every row ending with its trip id`() = runTest {
        stubExportEnvironment()
        stubTrips(
            mapOf(
                3L to listOf(Triple(1_000L, "010C", 900f)),
                5L to listOf(Triple(2_000L, "0105", 85f), Triple(2_100L, "010C", 1_400f)),
            ),
        )

        repo.exportSessionsToCsv(listOf(3L, 5L))

        val files = exportedFiles()
        assertEquals(1, files.size)
        assertTrue("der gemeinsame Name: ${files[0].name}", files[0].name.startsWith("session_all_"))
        assertEquals(
            "timestamp,pid,value,name,source,session\n1000,010C,900.0,RPM,,3\n2000,0105,85.0,Coolant Temp,,5\n2100,010C,1400.0,RPM,,5\n",
            files[0].readText(),
        )
    }

    @Test
    fun `the trips are written in the order they are given`() = runTest {
        stubExportEnvironment()
        stubTrips(mapOf(3L to listOf(Triple(1L, "010C", 1f)), 5L to listOf(Triple(2L, "010C", 2f))))

        repo.exportSessionsToCsv(listOf(5L, 3L))

        assertEquals(
            "timestamp,pid,value,name,source,session\n2,010C,2.0,RPM,,5\n1,010C,1.0,RPM,,3\n",
            exportedFiles().single().readText(),
        )
    }

    @Test
    fun `a list with a single trip gives the file of that trip, named after it`() = runTest {
        stubExportEnvironment()
        stubTrips(mapOf(7L to listOf(Triple(1L, "010C", 1f))))

        repo.exportSessionsToCsv(listOf(7L))

        assertTrue(exportedFiles().single().name.startsWith("session_7_"))
    }

    @Test
    fun `an export without a trip is refused and writes nothing`() = runTest {
        stubExportEnvironment()

        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repo.exportSessionsToCsv(emptyList()) } }

        assertFalse("kein Export-Ordner, keine Datei", File(tempFolder.root, "exports").exists())
        verify(exactly = 0) { sensorReadingDao.getBySessionCursor(any()) }
    }

    @Test
    fun `a failure while reading a later trip leaves no half-written file`() = runTest {
        stubExportEnvironment()
        every { sensorReadingDao.getBySessionCursor(any()) } answers {
            if (boundTripId(firstArg()) == 3L) makeCursor(listOf(Triple(1L, "010C", 1f))) else throw IllegalStateException("database is locked")
        }

        try { repo.exportSessionsToCsv(listOf(3L, 5L)) } catch (_: IllegalStateException) { }

        assertTrue("keine Reste im Export-Ordner", exportedFiles().isEmpty())
    }

    @Test
    fun `saveSessionsCsv writes the same CSV into the chosen target and leaves nothing in the cache`() = runTest {
        stubExportEnvironment()
        stubTrips(mapOf(3L to listOf(Triple(1_000L, "010C", 900f)), 5L to listOf(Triple(2_000L, "010C", 950f))))
        val target = mockk<Uri>()
        val out = ByteArrayOutputStream()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(target, "w") } returns out

        repo.saveSessionsCsv(listOf(3L, 5L), target)

        assertEquals(
            "timestamp,pid,value,name,source,session\n1000,010C,900.0,RPM,,3\n2000,010C,950.0,RPM,,5\n",
            out.toString(Charsets.UTF_8.name()),
        )
        assertTrue("die Zwischendatei im Cache ist weg", exportedFiles().isEmpty())
    }

    @Test
    fun `saveSessionsCsv reports a target that cannot be opened, and leaves nothing in the cache`() = runTest {
        stubExportEnvironment()
        stubTrips(mapOf(3L to listOf(Triple(1L, "010C", 1f))))
        val target = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(target, "w") } returns null

        try {
            repo.saveSessionsCsv(listOf(3L), target)
            org.junit.Assert.fail("ein Ziel ohne Ausgabestrom muss einen Fehler melden")
        } catch (_: IOException) { }

        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `a failing read never touches the chosen target`() = runTest {
        stubExportEnvironment()
        every { sensorReadingDao.getBySessionCursor(any()) } throws IllegalStateException("database is locked")
        val target = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver

        try { repo.saveSessionsCsv(listOf(3L), target) } catch (_: IllegalStateException) { }

        verify(exactly = 0) { resolver.openOutputStream(any<Uri>(), any<String>()) }
    }

    // ── sessionStats (F11: Min/Max je Fahrt) ──────────────────────────────────

    @Test
    fun `sessionStats reads the three values of the trip with the shared query and folds them`() = runTest {
        val queryCaptor = slot<SupportSQLiteQuery>()
        val cursor = makeCursor(
            listOf(
                Triple(0L, "010C", 1_200f), Triple(0L, "0105", 18f),
                Triple(1_000L, "010C", 1_300f), Triple(1_000L, "0105", 20f),
            ) + (2..20).flatMap { s ->
                listOf(Triple(s * 1_000L, "010C", 1_300f), Triple(s * 1_000L, "0105", 20f + s)) +
                    if (s == 20) listOf(Triple(s * 1_000L, "0142", 13.9f)) else emptyList()
            }
        )
        every { sensorReadingDao.getBySessionCursor(capture(queryCaptor)) } returns cursor

        val stats = repo.sessionStats(sessionId = 7L)

        assertEquals("the shared constant, not a second copy", SESSION_STATS_QUERY_SQL, queryCaptor.captured.sql)
        assertEquals("one bound value: the trip id", 1, queryCaptor.captured.argCount)
        assertEquals(MinMax(18f, 40f), stats.coolant)
        assertEquals(MinMax(13.9f, 13.9f), stats.voltage)
        verify(exactly = 1) { cursor.close() }
    }

    @Test
    fun `sessionStats of a trip without readings has no values`() = runTest {
        every { sensorReadingDao.getBySessionCursor(any()) } returns makeCursor(emptyList())

        assertEquals(SessionStats(coolant = null, voltage = null), repo.sessionStats(sessionId = 3L))
    }

    @Test
    fun `sessionStats closes the cursor when reading fails and passes the failure on`() = runTest {
        val cursor = mockk<Cursor> {
            every { getColumnIndexOrThrow(any()) } returns 0
            every { moveToNext() } throws IOException("disk gone")
            every { close() } just Runs
        }
        every { sensorReadingDao.getBySessionCursor(any()) } returns cursor

        assertThrows(IOException::class.java) { runBlocking { repo.sessionStats(sessionId = 1L) } }

        verify(exactly = 1) { cursor.close() }
    }

    @Test
    fun `sessionStats stops reading when the caller is cancelled - it does not run through a long trip`() = runTest {
        val rows = (0 until 20_000).map { Row(it * 1_000L, "0105", 80f, source = null) }
        var index = -1
        lateinit var job: Job
        val cursor = mockk<Cursor> {
            every { getColumnIndexOrThrow(any()) } returns 0
            every { moveToNext() } answers {
                index++
                if (index == 10) job.cancel()
                index < rows.size
            }
            every { getLong(any()) } answers { rows[index].timestamp }
            every { getString(any()) } answers { rows[index].pidId }
            every { getFloat(any()) } answers { rows[index].value }
            every { close() } just Runs
        }
        every { sensorReadingDao.getBySessionCursor(any()) } returns cursor

        job = launch { repo.sessionStats(sessionId = 1L) }
        job.join()

        assertTrue("cancelled", job.isCancelled)
        assertTrue("stopped long before the end, read $index of ${rows.size}", index < 5_000)
        verify(exactly = 1) { cursor.close() }
    }
}
