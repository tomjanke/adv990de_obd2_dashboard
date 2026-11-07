package de.adv990de.obd2.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.core.content.FileProvider
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.AppLogDao
import de.adv990de.obd2.data.db.SensorReadingDao
import de.adv990de.obd2.data.db.SessionDao
import de.adv990de.obd2.data.db.entity.AppLogEntity
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.ExportFormat
import de.adv990de.obd2.transfer.PackageFormat
import de.adv990de.obd2.transfer.parseManifest
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.zip.ZipFile

/**
 * [TransferExporter]: die CSV-Wege muessen **dieselben Bytes** liefern wie die bisherigen Exporter, das Paket das Manifest zuerst und nur nicht leere Teile. Die drei Exporter und das
 * Fahrten-Repository sind echt (mit gestellten DAOs), nur der FileProvider und der `ContentResolver` sind gestellt.
 */
class TransferExporterTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val context = mockk<Context>()
    private val sessionDao = mockk<SessionDao>()
    private val sensorReadingDao = mockk<SensorReadingDao>()
    private val db = mockk<AppDatabase> {
        every { sessionDao() } returns sessionDao
        every { sensorReadingDao() } returns sensorReadingDao
    }
    private val sessions = SessionRepository(context, db)
    private val logbook = mockk<VehicleLogRepository>()
    private val appLogDao = mockk<AppLogDao>()
    private val appLogs = mockk<AppLogRepository>()
    private val exporter = TransferExporter(context, sessions, logbook, LogbookExporter(context, logbook), appLogs, AppLogExporter(context, appLogDao)).apply {
        appVersion = "9.9.9"
        clockMs = { 1_700_000_000_000L }
    }

    private val entries = listOf(
        LogEntry(type = LogType.FUEL, date = LocalDate.of(2026, 10, 1), odometerKm = 40_000, amountMl = 14_370, fullTank = true, costCents = 2_743, note = null, brand = null),
        LogEntry(type = LogType.TIRE_CHANGE, date = LocalDate.of(2026, 9, 20), odometerKm = 39_800, amountMl = null, fullTank = null, costCents = null, note = null, brand = "Test Brand"),
    )
    private val logRows = listOf(Triple(1_000L, "INFO", "Connecting to Test Adapter"), Triple(2_000L, "ERROR", "boom, \"quoted\""))
    private val readings = listOf(Triple(10L, "010C", 1500f), Triple(11L, "0105", 80f))

    @After
    fun tearDown() {
        clearAllMocks()
        unmockkStatic(FileProvider::class)
    }

    private fun stubEnvironment(shared: Uri = mockk(), captured: CapturingSlot<File>? = null) {
        every { context.cacheDir } returns tempFolder.root
        every { context.packageName } returns "de.adv990de.obd2"
        mockkStatic(FileProvider::class)
        if (captured != null) every { FileProvider.getUriForFile(any(), any(), capture(captured)) } returns shared
        else every { FileProvider.getUriForFile(any(), any(), any()) } returns shared
    }

    private fun tripCursor(): Cursor {
        var index = -1
        return mockk {
            every { getColumnIndexOrThrow("timestamp") } returns 0
            every { getColumnIndexOrThrow("pidId") } returns 1
            every { getColumnIndexOrThrow("value") } returns 2
            every { getColumnIndexOrThrow("source") } returns 3
            every { moveToNext() } answers { index++; index < readings.size }
            every { getLong(0) } answers { readings[index].first }
            every { getString(1) } answers { readings[index].second }
            every { getFloat(2) } answers { readings[index].third }
            every { getString(3) } returns "ECU"
            every { close() } just Runs
        }
    }

    private fun logCursor(): Cursor {
        var index = -1
        return mockk {
            every { getColumnIndexOrThrow("timestamp") } returns 0
            every { getColumnIndexOrThrow("level") } returns 1
            every { getColumnIndexOrThrow("message") } returns 2
            every { moveToNext() } answers { index++; index < logRows.size }
            every { getLong(0) } answers { logRows[index].first }
            every { getString(1) } answers { logRows[index].second }
            every { getString(2) } answers { logRows[index].third }
            every { close() } just Runs
        }
    }

    private fun stubData(tripIds: List<Long> = listOf(1L, 2L), logEntries: List<LogEntry> = entries, logCount: Int = logRows.size) {
        every { sessionDao.observeIds() } returns flowOf(tripIds)
        coEvery { sessionDao.idsWithReadings() } returns tripIds
        every { sensorReadingDao.getBySessionCursor(any()) } answers { tripCursor() }
        coEvery { logbook.all() } returns logEntries
        every { appLogDao.getExportCursor(any()) } answers { logCursor() }
        coEvery { appLogDao.countAfter(0L) } returns logRows.size
        every { appLogs.logs } returns flowOf(List(logCount) { AppLogEntity(id = it + 1L, timestamp = 1L, level = "INFO", message = "m") })
    }

    private fun exportedFiles(): List<File> = File(tempFolder.root, "exports").listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }

    private fun request(vararg parts: DataPart, format: ExportFormat = ExportFormat.PACKAGE) = ExportRequest(parts.toSet(), format)

    private fun zipNames(file: File): List<String> = ZipFile(file).use { z -> z.entries().toList().map { it.name } }

    private fun zipBytes(file: File, name: String): ByteArray = ZipFile(file).use { z -> z.getInputStream(z.getEntry(name)).readBytes() }

    // --- CSV: dieselben Bytes wie der bisherige Weg ---

    @Test
    fun `csv of the trips is the same file as the trip export of today`() = runTest {
        stubData()
        stubEnvironment()
        exporter.share(request(DataPart.TRIPS, format = ExportFormat.CSV))
        val viaTransfer = exportedFiles().single()
        val direct = ByteArrayOutputStream().also { out -> out.writer().use { sessions.writeTripsCsv(it, listOf(1L, 2L)) } }.toByteArray()
        assertTrue(viaTransfer.name.startsWith("session_all_"))
        assertArrayEquals(direct, viaTransfer.readBytes())
        assertTrue(viaTransfer.readText().startsWith("timestamp,pid,value,name,source,session\n"))
    }

    @Test
    fun `csv of the logbook is the same file as the logbook export of today`() = runTest {
        stubData()
        stubEnvironment()
        exporter.share(request(DataPart.LOGBOOK, format = ExportFormat.CSV))
        val viaTransfer = exportedFiles().single()
        val direct = ByteArrayOutputStream().also { out -> out.writer().use { LogbookExporter(context, logbook).writeCsv(it) } }.toByteArray()
        assertTrue(viaTransfer.name.startsWith("logbook_"))
        assertArrayEquals(direct, viaTransfer.readBytes())
    }

    @Test
    fun `csv of the app log is the same file as the app log export of today`() = runTest {
        stubData()
        stubEnvironment()
        exporter.share(request(DataPart.APP_LOG, format = ExportFormat.CSV))
        val viaTransfer = exportedFiles().single()
        val direct = ByteArrayOutputStream().also { out -> out.writer().use { AppLogExporter(context, appLogDao).writeCsv(it) } }.toByteArray()
        assertTrue(viaTransfer.name.startsWith("applog_"))
        assertArrayEquals(direct, viaTransfer.readBytes())
        assertTrue(viaTransfer.readText().contains("\"boom, \"\"quoted\"\"\""))
    }

    @Test
    fun `csv with two parts is refused`() = runTest {
        stubData()
        stubEnvironment()
        try {
            exporter.share(request(DataPart.TRIPS, DataPart.LOGBOOK, format = ExportFormat.CSV))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `csv of a part that has no csv is refused`() = runTest {
        stubData()
        stubEnvironment()
        try {
            exporter.share(request(DataPart.SETTINGS, format = ExportFormat.CSV))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    // --- Paket ---

    @Test
    fun `the package has the manifest first, then trips, logbook and app log - and trips equal the csv bytes`() = runTest {
        stubData()
        val file = slot<File>()
        stubEnvironment(captured = file)
        exporter.share(request(DataPart.APP_LOG, DataPart.LOGBOOK, DataPart.TRIPS))

        val pkg = file.captured
        assertTrue(pkg.name.startsWith("obd-dashboard_") && pkg.name.endsWith(".zip"))
        assertEquals(listOf("manifest.json", "trips.csv", "logbook.csv", "applog.csv"), zipNames(pkg))
        val direct = ByteArrayOutputStream().also { out -> out.writer().use { sessions.writeTripsCsv(it, listOf(1L, 2L)) } }.toByteArray()
        assertArrayEquals(direct, zipBytes(pkg, "trips.csv"))
        val manifest = parseManifest(String(zipBytes(pkg, "manifest.json"), Charsets.UTF_8))
        assertNotNull(manifest)
        assertEquals(PackageFormat.VERSION, manifest!!.format)
        assertEquals("9.9.9", manifest.appVersion)
        assertEquals(1_700_000_000_000L, manifest.createdMs)
        assertEquals(mapOf(DataPart.TRIPS to 2, DataPart.LOGBOOK to 2, DataPart.APP_LOG to 2), manifest.parts)
    }

    @Test
    fun `empty parts are not written and not named in the manifest`() = runTest {
        stubData(tripIds = emptyList())
        val file = slot<File>()
        stubEnvironment(captured = file)
        exporter.share(request(DataPart.TRIPS, DataPart.LOGBOOK))

        assertEquals(listOf("manifest.json", "logbook.csv"), zipNames(file.captured))
        val manifest = parseManifest(String(zipBytes(file.captured, "manifest.json"), Charsets.UTF_8))!!
        assertEquals(mapOf(DataPart.LOGBOOK to 2), manifest.parts)
    }

    @Test
    fun `the manifest counts what this export wrote, not a later count`() = runTest {
        // Jede spaetere Abfrage des Logbuchs liefert nur noch einen Eintrag: Manifest und Datei beruhen auf derselben, ersten Abfrage.
        stubData()
        var call = 0
        coEvery { logbook.all() } answers { if (call++ < 1) entries else entries.take(1) }
        val file = slot<File>()
        stubEnvironment(captured = file)
        exporter.share(request(DataPart.LOGBOOK))
        val manifest = parseManifest(String(zipBytes(file.captured, "manifest.json"), Charsets.UTF_8))!!
        val written = zipBytes(file.captured, "logbook.csv").toString(Charsets.UTF_8).lines().count { it.isNotBlank() } - 1
        assertEquals(2, written)
        assertEquals(written, manifest.parts[DataPart.LOGBOOK])
    }

    @Test
    fun `a package with every chosen part empty is an error and leaves no file`() = runTest {
        stubData(tripIds = emptyList(), logEntries = emptyList())
        stubEnvironment()
        try {
            exporter.share(request(DataPart.TRIPS, DataPart.LOGBOOK))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Nothing to export", e.message)
        }
        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `parts this version does not know are not written`() = runTest {
        stubData()
        val file = slot<File>()
        stubEnvironment(captured = file)
        exporter.share(request(DataPart.LOGBOOK, DataPart.SETTINGS))
        assertEquals(listOf("manifest.json", "logbook.csv"), zipNames(file.captured))
    }

    // --- counts ---

    @Test
    fun `counts leave out the trip that is being recorded and count the visible app log`() = runTest {
        stubData(tripIds = listOf(1L, 2L, 3L), logCount = 5)
        coEvery { sessionDao.insert(any<SessionEntity>()) } returns 3L
        sessions.startSession()
        val counts = exporter.counts()
        assertEquals(2, counts.trips)
        assertEquals(2, counts.logbook)
        assertEquals(5, counts.appLog)
        assertEquals(2, counts.of(DataPart.TRIPS))
        assertEquals(5, counts.of(DataPart.APP_LOG))
        assertEquals(null, counts.of(DataPart.SETTINGS))
        assertEquals(null, counts.of(DataPart.COUNTERS))
        assertFalse(counts.isEmpty(DataPart.TRIPS))
    }

    @Test
    fun `a running trip is not part of a package`() = runTest {
        stubData(tripIds = listOf(1L, 2L, 3L))
        coEvery { sessionDao.insert(any<SessionEntity>()) } returns 3L
        sessions.startSession()
        val file = slot<File>()
        stubEnvironment(captured = file)
        exporter.share(request(DataPart.TRIPS))
        assertEquals(2, parseManifest(String(zipBytes(file.captured, "manifest.json"), Charsets.UTF_8))!!.parts[DataPart.TRIPS])
        assertEquals(listOf(1L, 2L), sessions.exportableSessionIds())
    }

    @Test
    fun `isEmpty is true only for a zero count`() {
        val counts = ExportCounts(trips = 0, logbook = 3, appLog = 0)
        assertTrue(counts.isEmpty(DataPart.TRIPS))
        assertFalse(counts.isEmpty(DataPart.LOGBOOK))
        assertFalse(counts.isEmpty(DataPart.SETTINGS))
    }

    // --- Dateinamen ---

    @Test
    fun `suggested names follow the old names for csv and the package name for a package`() {
        assertTrue(exporter.suggestedFileName(request(DataPart.TRIPS, format = ExportFormat.CSV)).startsWith("session_all_"))
        assertTrue(exporter.suggestedFileName(request(DataPart.LOGBOOK, format = ExportFormat.CSV)).startsWith("logbook_"))
        assertTrue(exporter.suggestedFileName(request(DataPart.APP_LOG, format = ExportFormat.CSV)).startsWith("applog_"))
        assertEquals(PackageFormat.fileName(1_700_000_000_000L), exporter.suggestedFileName(request(DataPart.TRIPS, DataPart.LOGBOOK)))
        assertEquals(PackageFormat.fileName(1_700_000_000_000L), exporter.suggestedFileName(request(DataPart.TRIPS)))
    }

    // --- save ---

    @Test
    fun `save writes the package into the target and removes the cache file`() = runTest {
        stubData()
        stubEnvironment()
        val target = mockk<Uri>()
        val out = ByteArrayOutputStream()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(target, "w") } returns out

        exporter.save(target, request(DataPart.LOGBOOK))

        val tmp = tempFolder.newFile("saved.zip").also { it.writeBytes(out.toByteArray()) }
        assertEquals(listOf("manifest.json", "logbook.csv"), zipNames(tmp))
        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `save with csv writes the old csv into the target`() = runTest {
        stubData()
        stubEnvironment()
        val target = mockk<Uri>()
        val out = ByteArrayOutputStream()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(target, "w") } returns out

        exporter.save(target, request(DataPart.LOGBOOK, format = ExportFormat.CSV))

        val direct = ByteArrayOutputStream().also { o -> o.writer().use { LogbookExporter(context, logbook).writeCsv(it) } }.toByteArray()
        assertArrayEquals(direct, out.toByteArray())
    }

    @Test
    fun `a failure while writing leaves the target untouched`() = runTest {
        stubData()
        stubEnvironment()
        coEvery { logbook.all() } throws IOException("db gone")
        val target = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(target, "w") } returns ByteArrayOutputStream()

        try {
            exporter.save(target, request(DataPart.LOGBOOK, DataPart.TRIPS))
            fail("expected IOException")
        } catch (_: IOException) {
        }
        io.mockk.verify(exactly = 0) { resolver.openOutputStream(any(), any()) }
        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `a cancellation is never turned into another error`() = runTest {
        stubData()
        stubEnvironment()
        coEvery { logbook.all() } throws CancellationException("cancelled")
        try {
            exporter.share(request(DataPart.LOGBOOK))
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertTrue(exportedFiles().isEmpty())
    }

    @Test
    fun `an empty request is refused`() {
        try {
            ExportRequest(emptySet(), ExportFormat.PACKAGE)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `the manifest counts the app log rows the file really has, not the 1000 the screen shows`() = runTest {
        stubData(logCount = 1000)
        val total = 1500
        coEvery { appLogDao.countAfter(0L) } returns total
        every { appLogDao.getExportCursor(any()) } answers {
            var index = -1
            mockk {
                every { getColumnIndexOrThrow("timestamp") } returns 0
                every { getColumnIndexOrThrow("level") } returns 1
                every { getColumnIndexOrThrow("message") } returns 2
                every { moveToNext() } answers { index++; index < total }
                every { getLong(0) } answers { index.toLong() }
                every { getString(1) } returns "INFO"
                every { getString(2) } returns "m"
                every { close() } just Runs
            }
        }
        val file = slot<File>()
        stubEnvironment(captured = file)
        exporter.share(request(DataPart.APP_LOG))
        val written = zipBytes(file.captured, "applog.csv").toString(Charsets.UTF_8).lines().count { it.isNotBlank() } - 1
        assertEquals(total, written)
        assertEquals(written, parseManifest(String(zipBytes(file.captured, "manifest.json"), Charsets.UTF_8))!!.parts[DataPart.APP_LOG])
    }

    @Test
    fun `the manifest counts only trips that have rows in the file`() = runTest {
        // trip 2 has no readings: it is not part of the export, the file has two trips
        stubData(tripIds = listOf(1L, 2L, 3L))
        coEvery { sessionDao.idsWithReadings() } returns listOf(1L, 3L)
        every { sensorReadingDao.getBySessionCursor(any()) } answers { tripCursor() }
        val file = slot<File>()
        stubEnvironment(captured = file)
        exporter.share(request(DataPart.TRIPS))
        val lines = zipBytes(file.captured, "trips.csv").toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.drop(1)
        val tripsInFile = lines.map { it.substringAfterLast(',') }.toSet()
        assertEquals(setOf("1", "3"), tripsInFile)
        assertEquals(tripsInFile.size, parseManifest(String(zipBytes(file.captured, "manifest.json"), Charsets.UTF_8))!!.parts[DataPart.TRIPS])
    }

    @Test
    fun `csv of an empty logbook or app log is refused like the package`() = runTest {
        stubData(logEntries = emptyList())
        coEvery { appLogDao.countAfter(0L) } returns 0
        stubEnvironment()
        for (part in listOf(DataPart.LOGBOOK, DataPart.APP_LOG)) {
            try {
                exporter.share(request(part, format = ExportFormat.CSV))
                fail("expected IllegalStateException for $part")
            } catch (e: IllegalStateException) {
                assertEquals("Nothing to export", e.message)
            }
            try {
                exporter.save(mockk<Uri>(), request(part, format = ExportFormat.CSV))
                fail("expected IllegalStateException for $part")
            } catch (e: IllegalStateException) {
                assertEquals("Nothing to export", e.message)
            }
        }
        assertTrue(exportedFiles().isEmpty())
    }
}
