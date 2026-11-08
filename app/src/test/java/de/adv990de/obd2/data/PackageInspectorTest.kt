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
import de.adv990de.obd2.transfer.TripMeta
import de.adv990de.obd2.transfer.TripMetaSummary
import de.adv990de.obd2.transfer.tripMetaCsvHeader
import de.adv990de.obd2.transfer.tripMetaCsvLine
import de.adv990de.obd2.tripimport.SessionSpan
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
    private val dtcRepo = mockk<DtcHistoryRepository>()
    private val inspector = PackageInspector(TripImporter(mockk<Context>(), sessionRepo), LogbookImporter(mockk<Context>(), logRepo), dtcRepo)

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
        coEvery { dtcRepo.all() } returns emptyList()
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
        val preview = inspector.inspect(zip("manifest.json" to manifest(), "settings.json" to "{}", "counters.json" to "{}"), nowMs, today)
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

    // --- trips_meta.csv ---

    private val vin = "TESTVIN0000000001"

    /** Zwei Fahrten (Nummern 1 und 2, weit auseinander) in einer `trips.csv`. */
    private val twoTripsCsv = tripsCsv + "${nowMs - 500_000},010C,1700.0,Engine RPM,ECU,2\n${nowMs - 499_000},010C,1800.0,Engine RPM,ECU,2\n"

    private fun tripsOf(preview: de.adv990de.obd2.transfer.TransferPreview) = preview.parts.getValue(DataPart.TRIPS) as PartPlan.Trips

    @Test
    fun `the details of a trip are attached by the trip number and not by the order`() = runTest {
        stubRepos()
        // The meta file lists the trips in the opposite order.
        val meta = tripMetaCsvHeader() + tripMetaCsvLine("2", TripMeta(null, "")) + tripMetaCsvLine("1", TripMeta(vin, "P0301,P0420?"))
        val trips = tripsOf(inspector.inspect(zip("manifest.json" to manifest(), "trips.csv" to twoTripsCsv, "trips_meta.csv" to meta), nowMs, today))

        assertEquals(listOf("1", "2"), trips.plan.trips.map { it.sourceId })
        assertEquals(vin, trips.plan.trips[0].vin)
        assertEquals("P0301,P0420?", trips.plan.trips[0].dtcCodes)
        assertEquals(null, trips.plan.trips[1].vin)
        assertEquals("", trips.plan.trips[1].dtcCodes)
        assertEquals(TripMetaSummary(withVin = 1, withCodesRecorded = 2, invalidVins = 0, orphanRows = 0, unreadable = false), trips.meta)
    }

    @Test
    fun `without a meta file the trips have no details and nothing is flagged`() = runTest {
        stubRepos()
        val trips = tripsOf(inspector.inspect(zip("manifest.json" to manifest(), "trips.csv" to tripsCsv), nowMs, today))

        assertEquals(null, trips.plan.trips.single().vin)
        assertEquals(null, trips.plan.trips.single().dtcCodes)
        assertEquals(TripMetaSummary(0, 0, 0, 0, false), trips.meta)
    }

    @Test
    fun `a row for a trip that is not in trips csv is an orphan`() = runTest {
        stubRepos()
        val meta = tripMetaCsvHeader() + tripMetaCsvLine("1", TripMeta(vin, null)) + tripMetaCsvLine("99", TripMeta(vin, "P0301"))
        val trips = tripsOf(inspector.inspect(zip("manifest.json" to manifest(), "trips.csv" to tripsCsv, "trips_meta.csv" to meta), nowMs, today))

        assertEquals(1, trips.meta.orphanRows)
        assertEquals(1, trips.meta.withVin)
        assertEquals(0, trips.meta.withCodesRecorded)
    }

    @Test
    fun `the details of a trip that is already in the app are an orphan and are not applied`() = runTest {
        // Trip 1 touches an existing trip (same time), trip 2 does not.
        coEvery { sessionRepo.sessionSpans() } returns listOf(SessionSpan(nowMs - 6_000, nowMs - 3_000))
        coEvery { logRepo.all() } returns emptyList()
        val meta = tripMetaCsvHeader() + tripMetaCsvLine("1", TripMeta(vin, "P0301")) + tripMetaCsvLine("2", TripMeta(null, ""))
        val trips = tripsOf(inspector.inspect(zip("manifest.json" to manifest(), "trips.csv" to twoTripsCsv, "trips_meta.csv" to meta), nowMs, today))

        assertEquals(listOf("2"), trips.plan.trips.map { it.sourceId })
        assertEquals(1, trips.plan.duplicateTrips)
        assertEquals(1, trips.meta.orphanRows)
        assertEquals(0, trips.meta.withVin)
        assertEquals(1, trips.meta.withCodesRecorded)
        assertEquals("", trips.plan.trips.single().dtcCodes)
    }

    @Test
    fun `an invalid VIN is counted and the trip is kept without it`() = runTest {
        stubRepos()
        val meta = "session,vin,dtcs_recorded,dtc_codes\n1,TESTVIN000000001,1,P0301\n"
        val trips = tripsOf(inspector.inspect(zip("manifest.json" to manifest(), "trips.csv" to tripsCsv, "trips_meta.csv" to meta), nowMs, today))

        assertEquals(1, trips.plan.trips.size)
        assertEquals(null, trips.plan.trips.single().vin)
        assertEquals("P0301", trips.plan.trips.single().dtcCodes)
        assertEquals(1, trips.meta.invalidVins)
        assertEquals(0, trips.meta.withVin)
    }

    @Test
    fun `a meta file without the session column is unreadable and the trips are still planned`() = runTest {
        stubRepos()
        val trips = tripsOf(inspector.inspect(zip("manifest.json" to manifest(), "trips.csv" to tripsCsv, "trips_meta.csv" to "vin,dtcs_recorded\n$vin,1\n"), nowMs, today))

        assertEquals(1, trips.plan.trips.size)
        assertEquals(null, trips.plan.trips.single().vin)
        assertEquals(null, trips.plan.trips.single().dtcCodes)
        assertTrue(trips.meta.unreadable)
    }

    @Test
    fun `a meta file above its limit is unreadable and the trips are still planned`() = runTest {
        stubRepos()
        val file = tmp.newFile()
        ZipOutputStream(FileOutputStream(file)).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest().toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("trips.csv")); z.write(tripsCsv.toByteArray()); z.closeEntry()
            val meta = tripMetaCsvHeader() + tripMetaCsvLine("1", TripMeta(vin, "P0301"))
            z.putNextEntry(ZipEntry("trips_meta.csv")); z.write(meta.toByteArray()); z.closeEntry()
        }
        val reader = PackageReader.open(file, limitOf = { if (it == "trips_meta.csv") 10L else 1_000_000L })
        val trips = tripsOf(inspector.inspect(reader, nowMs, today))

        assertEquals(1, trips.plan.trips.size)
        assertTrue(trips.meta.unreadable)
        assertEquals(null, trips.plan.trips.single().vin)
    }

    @Test
    fun `a meta file without trips csv is not a part of its own`() = runTest {
        stubRepos()
        val meta = tripMetaCsvHeader() + tripMetaCsvLine("1", TripMeta(vin, "P0301"))
        val preview = inspector.inspect(zip("manifest.json" to manifest(), "trips_meta.csv" to meta), nowMs, today)
        assertTrue(preview.parts.isEmpty())
    }

    @Test
    fun `broken trips stay unreadable whatever the meta file says`() = runTest {
        stubRepos()
        val meta = tripMetaCsvHeader() + tripMetaCsvLine("1", TripMeta(vin, "P0301"))
        val preview = inspector.inspect(zip("manifest.json" to manifest(), "trips.csv" to "a,b,c\n1,2,3\n", "trips_meta.csv" to meta), nowMs, today)
        assertEquals(PartPlan.Unreadable(UnreadableReason.FORMAT), preview.parts.getValue(DataPart.TRIPS))
    }

    // --- Fehlerhistorie ---

    private val dtcCsv = "code,first_seen,last_seen,last_was_pending\nP0301,${nowMs - 9_000},${nowMs - 3_000},0\nP0420,${nowMs - 8_000},${nowMs - 2_000},1\nbad,1,2,0\n"

    @Test
    fun `the fault history is planned against the current history and sits between trips and settings in import order`() = runTest {
        stubRepos()
        coEvery { dtcRepo.all() } returns listOf(DtcHistoryEntry("P0301", nowMs - 9_000, nowMs - 3_000, false))
        val preview = inspector.inspect(zip("manifest.json" to manifest(), "dtc_history.csv" to dtcCsv, "trips.csv" to tripsCsv, "logbook.csv" to logbookCsv), nowMs, today)

        assertEquals(listOf(DataPart.LOGBOOK, DataPart.TRIPS, DataPart.DTC_HISTORY), preview.parts.keys.toList())
        val plan = (preview.parts.getValue(DataPart.DTC_HISTORY) as PartPlan.DtcHistory).plan
        assertEquals(1, plan.newCodes)
        assertEquals(0, plan.updatedCodes)
        assertEquals(1, plan.unchangedCodes)
        assertEquals(1, plan.rejected)
        assertEquals(listOf("P0420"), plan.entries.map { it.code })
        assertTrue(preview.parts.getValue(DataPart.DTC_HISTORY).hasNews)
    }

    @Test
    fun `a fault history that changes nothing has no news`() = runTest {
        stubRepos()
        coEvery { dtcRepo.all() } returns listOf(DtcHistoryEntry("P0301", nowMs - 9_000, nowMs - 3_000, false), DtcHistoryEntry("P0420", nowMs - 8_000, nowMs - 2_000, true))
        val preview = inspector.inspect(zip("manifest.json" to manifest(), "dtc_history.csv" to dtcCsv), nowMs, today)

        assertFalse(preview.parts.getValue(DataPart.DTC_HISTORY).hasNews)
    }

    @Test
    fun `a fault history without the code column is unreadable and leaves the other parts`() = runTest {
        stubRepos()
        val preview = inspector.inspect(zip("manifest.json" to manifest(), "dtc_history.csv" to "first_seen,last_seen\n1,2\n", "logbook.csv" to logbookCsv), nowMs, today)

        assertEquals(PartPlan.Unreadable(UnreadableReason.FORMAT), preview.parts.getValue(DataPart.DTC_HISTORY))
        assertTrue(preview.parts.getValue(DataPart.LOGBOOK) is PartPlan.Logbook)
    }

    @Test
    fun `a failing read of the current history makes only the fault history unreadable`() = runTest {
        stubRepos()
        coEvery { dtcRepo.all() } throws java.io.IOException("disk")
        val preview = inspector.inspect(zip("manifest.json" to manifest(), "dtc_history.csv" to dtcCsv, "logbook.csv" to logbookCsv), nowMs, today)

        assertEquals(PartPlan.Unreadable(UnreadableReason.UNREADABLE), preview.parts.getValue(DataPart.DTC_HISTORY))
        assertTrue(preview.parts.getValue(DataPart.LOGBOOK) is PartPlan.Logbook)
    }

    @Test
    fun `a package without the fault history entry has no such part`() = runTest {
        stubRepos()
        val preview = inspector.inspect(zip("manifest.json" to manifest(), "logbook.csv" to logbookCsv), nowMs, today)

        assertFalse(DataPart.DTC_HISTORY in preview.parts)
    }
}
