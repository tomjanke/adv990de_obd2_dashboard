package de.adv990de.obd2.data

import android.content.Context
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.logbookCsvHeader
import de.adv990de.obd2.logbook.logbookCsvLine
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.FileKind
import de.adv990de.obd2.transfer.PackageManifest
import de.adv990de.obd2.transfer.PackageReader
import de.adv990de.obd2.transfer.PartPlan
import de.adv990de.obd2.transfer.TransferFileException
import de.adv990de.obd2.transfer.UnreadableReason
import de.adv990de.obd2.transfer.hasNews
import de.adv990de.obd2.transfer.toJson
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** [PackageInspector]: echte ZIP-Dateien, echte Importer (nur Repositories und Context gestellt). */
class PackageInspectorTest {

    @get:Rule val tmp = TemporaryFolder()

    private val nowMs = 1_790_000_000_000L
    private val today = LocalDate.of(2026, 9, 21)
    private val sessionRepo = mockk<SessionRepository>()
    private val logRepo = mockk<VehicleLogRepository>()
    private val inspector = PackageInspector(TripImporter(mockk<Context>(), sessionRepo), LogbookImporter(mockk<Context>(), logRepo))

    private fun manifest(format: Int = 1, appId: String = "de.adv990de.obd2") = PackageManifest(format, appId, "1.0", nowMs, emptyMap()).toJson()

    private val tripsCsv = "timestamp,pid,value,name,source,session\n${nowMs - 5_000},010C,1500.0,Engine RPM,ECU,1\n${nowMs - 4_000},010C,1600.0,Engine RPM,ECU,1\n"
    private val logbookCsv = logbookCsvHeader() + logbookCsvLine(
        LogEntry(type = LogType.FUEL, date = LocalDate.of(2026, 9, 1), odometerKm = 1000, amountMl = 12_000, fullTank = true, costCents = null, note = null, brand = null),
    )

    private fun zip(vararg entries: Pair<String, String>): PackageReader {
        val file = tmp.newFile()
        ZipOutputStream(FileOutputStream(file)).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return PackageReader.open(file)
    }

    private fun stubRepos() {
        coEvery { sessionRepo.sessionSpans() } returns emptyList()
        coEvery { logRepo.all() } returns emptyList()
    }

    @Test
    fun `a package with trips and logbook gives both plans in import order`() = runTest {
        stubRepos()
        val preview = inspector.inspect(zip("manifest.json" to manifest(), "trips.csv" to tripsCsv, "logbook.csv" to logbookCsv), nowMs, today)

        assertEquals(FileKind.PACKAGE, preview.kind)
        assertEquals(listOf(DataPart.LOGBOOK, DataPart.TRIPS), preview.parts.keys.toList())
        val trips = preview.parts.getValue(DataPart.TRIPS) as PartPlan.Trips
        assertEquals(1, trips.plan.trips.size)
        assertEquals(2, trips.plan.readingCount)
        val log = preview.parts.getValue(DataPart.LOGBOOK) as PartPlan.Logbook
        assertEquals(1, log.plan.toInsert.size)
        assertTrue(preview.parts.values.all { it.hasNews })
        assertFalse(preview.newerFormat)
        assertFalse(preview.ignoredAppLog)
    }

    @Test
    fun `a zip without a manifest is not a backup`() = runTest {
        stubRepos()
        try {
            inspector.inspect(zip("trips.csv" to tripsCsv), nowMs, today)
            fail("expected TransferFileException")
        } catch (e: TransferFileException) {
            assertEquals(TransferFileException.Kind.NOT_A_BACKUP, e.kind)
        }
    }

    @Test
    fun `a manifest of a foreign app is not a backup`() = runTest {
        stubRepos()
        try {
            inspector.inspect(zip("manifest.json" to manifest(appId = "com.example.other"), "trips.csv" to tripsCsv), nowMs, today)
            fail("expected TransferFileException")
        } catch (e: TransferFileException) {
            assertEquals(TransferFileException.Kind.NOT_A_BACKUP, e.kind)
        }
    }

    @Test
    fun `a newer format is flagged and the known parts are still read`() = runTest {
        stubRepos()
        val preview = inspector.inspect(zip("manifest.json" to manifest(format = 2), "logbook.csv" to logbookCsv), nowMs, today)
        assertTrue(preview.newerFormat)
        assertTrue(preview.parts.getValue(DataPart.LOGBOOK) is PartPlan.Logbook)
    }

    @Test
    fun `the current format is not flagged as newer`() = runTest {
        stubRepos()
        assertFalse(inspector.inspect(zip("manifest.json" to manifest(format = 1), "logbook.csv" to logbookCsv), nowMs, today).newerFormat)
    }

    @Test
    fun `a broken trips file makes only the trips unreadable`() = runTest {
        stubRepos()
        val preview = inspector.inspect(zip("manifest.json" to manifest(), "trips.csv" to "a,b,c\n1,2,3\n", "logbook.csv" to logbookCsv), nowMs, today)
        assertEquals(PartPlan.Unreadable(UnreadableReason.FORMAT), preview.parts.getValue(DataPart.TRIPS))
        assertTrue(preview.parts.getValue(DataPart.LOGBOOK) is PartPlan.Logbook)
        assertFalse(preview.parts.getValue(DataPart.TRIPS).hasNews)
    }

    @Test
    fun `a file above its limit is unreadable because it is too large`() = runTest {
        stubRepos()
        val file = tmp.newFile()
        ZipOutputStream(FileOutputStream(file)).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest().toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("logbook.csv")); z.write(logbookCsv.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("trips.csv")); z.write(tripsCsv.toByteArray()); z.closeEntry()
        }
        val reader = PackageReader.open(file, limitOf = { if (it == "logbook.csv") 10L else 1_000_000L })
        val preview = inspector.inspect(reader, nowMs, today)
        assertEquals(PartPlan.Unreadable(UnreadableReason.TOO_LARGE), preview.parts.getValue(DataPart.LOGBOOK))
        assertTrue(preview.parts.getValue(DataPart.TRIPS) is PartPlan.Trips)
    }

    @Test
    fun `a package with only an app log has no parts and says so`() = runTest {
        stubRepos()
        val preview = inspector.inspect(zip("manifest.json" to manifest(), "applog.csv" to "timestamp,level,message\n1,INFO,x\n"), nowMs, today)
        assertTrue(preview.parts.isEmpty())
        assertTrue(preview.ignoredAppLog)
    }

    @Test
    fun `unknown entries and path tricks are ignored`() = runTest {
        stubRepos()
        val preview = inspector.inspect(zip("manifest.json" to manifest(), "../trips.csv" to tripsCsv, "evil/logbook.csv" to logbookCsv, "notes.txt" to "x"), nowMs, today)
        assertTrue(preview.parts.isEmpty())
    }

    @Test
    fun `parts this version does not know are not read`() = runTest {
        stubRepos()
        val preview = inspector.inspect(zip("manifest.json" to manifest(), "settings.json" to "{}", "counters.json" to "{}", "dtc_history.csv" to "code\n"), nowMs, today)
        assertTrue(preview.parts.isEmpty())
    }

    @Test
    fun `a parser that throws an unexpected exception only makes its own part unreadable`() = runTest {
        stubRepos()
        coEvery { sessionRepo.sessionSpans() } throws IllegalStateException("boom")
        val preview = inspector.inspect(zip("manifest.json" to manifest(), "trips.csv" to tripsCsv, "logbook.csv" to logbookCsv), nowMs, today)
        assertEquals(PartPlan.Unreadable(UnreadableReason.UNREADABLE), preview.parts.getValue(DataPart.TRIPS))
        assertTrue(preview.parts.getValue(DataPart.LOGBOOK) is PartPlan.Logbook)
    }

    @Test
    fun `an empty zip is not a backup`() = runTest {
        stubRepos()
        try {
            inspector.inspect(zip(), nowMs, today)
            fail("expected TransferFileException")
        } catch (e: TransferFileException) {
            assertEquals(TransferFileException.Kind.NOT_A_BACKUP, e.kind)
        }
    }

    @Test
    fun `a manifest whose format is not a whole number from 1 is not a backup`() = runTest {
        stubRepos()
        val base = """{"app_id":"de.adv990de.obd2","app_version":"1","created_ms":1,"parts":{}"""
        for (bad in listOf("\"1\"", "1.5", "0", "-1", "null", "true")) {
            try {
                inspector.inspect(zip("manifest.json" to "$base,\"format\":$bad}", "logbook.csv" to logbookCsv), nowMs, today)
                fail("expected TransferFileException for format=$bad")
            } catch (e: TransferFileException) {
                assertEquals("format=$bad", TransferFileException.Kind.NOT_A_BACKUP, e.kind)
            }
        }
    }
}
