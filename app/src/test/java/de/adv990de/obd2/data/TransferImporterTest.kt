package de.adv990de.obd2.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import de.adv990de.obd2.fuel.FuelRepository
import de.adv990de.obd2.logbook.GpsOdometer
import de.adv990de.obd2.logbook.ImportPlan
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.logbookCsvHeader
import de.adv990de.obd2.logbook.logbookCsvLine
import de.adv990de.obd2.transfer.CounterValues
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.OdometerDecision
import de.adv990de.obd2.transfer.SettingChange
import de.adv990de.obd2.transfer.SettingsCatalog
import de.adv990de.obd2.transfer.SettingsPlan
import de.adv990de.obd2.transfer.FileKind
import de.adv990de.obd2.transfer.PackageManifest
import de.adv990de.obd2.transfer.PartOutcome
import de.adv990de.obd2.transfer.PartPlan
import de.adv990de.obd2.transfer.TransferFileException
import de.adv990de.obd2.transfer.TransferPreview
import de.adv990de.obd2.transfer.toJson
import de.adv990de.obd2.tripimport.TripImportPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TransferImporterTest {

    @get:Rule val tmp = TemporaryFolder()

    private val nowMs = 1_790_000_000_000L
    private val today = LocalDate.of(2026, 9, 21)

    // --- apply (Importer gestellt) ---

    private val trips = mockk<TripImporter>()
    private val logbook = mockk<LogbookImporter>()
    private val context = mockk<Context>()
    private val dtcHistory = mockk<DtcHistoryRepository>()
    private val settingsTransfer = mockk<SettingsTransfer>()
    private val fuel = mockk<FuelRepository>()
    private val odometer = mockk<LogbookOdometer>()
    private val logEntries = mockk<VehicleLogRepository>()
    private val importer = TransferImporter(context, trips, logbook, dtcHistory, settingsTransfer, fuel, odometer, logEntries)

    private val tripPlan = TripImportPlan(emptyList(), 0, 0, emptyList(), 0)
    private val logPlan = ImportPlan(emptyList(), 0, emptyList(), 0)
    private val both = TransferPreview(FileKind.PACKAGE, false, false, linkedMapOf(DataPart.LOGBOOK to PartPlan.Logbook(logPlan), DataPart.TRIPS to PartPlan.Trips(tripPlan)))

    @Test
    fun `apply writes the logbook before the trips`() = runTest {
        coEvery { logbook.apply(any()) } returns 2
        coEvery { trips.apply(any()) } returns 3
        val result = importer.apply(both, setOf(DataPart.TRIPS, DataPart.LOGBOOK))

        coVerifyOrder { logbook.apply(any()); trips.apply(any()) }
        assertEquals(listOf(DataPart.LOGBOOK, DataPart.TRIPS), result.outcomes.keys.toList())
        assertEquals(PartOutcome.Done(2), result.outcomes[DataPart.LOGBOOK])
        assertEquals(PartOutcome.Done(3), result.outcomes[DataPart.TRIPS])
    }

    private val dtcPlan = de.adv990de.obd2.transfer.DtcHistoryPlan(
        listOf(DtcHistoryEntry("P0301", 1_000L, 3_000L, true), DtcHistoryEntry("P0420", 2_000L, 2_000L, false)),
        newCodes = 1, updatedCodes = 1, unchangedCodes = 4, droppedByCap = 0, rejected = 0,
    )
    private val all = TransferPreview(
        FileKind.PACKAGE, false, false,
        linkedMapOf(DataPart.LOGBOOK to PartPlan.Logbook(logPlan), DataPart.TRIPS to PartPlan.Trips(tripPlan), DataPart.DTC_HISTORY to PartPlan.DtcHistory(dtcPlan)),
    )

    @Test
    fun `the fault history is merged after the trips with the merged entries and counts new plus updated`() = runTest {
        coEvery { logbook.apply(any()) } returns 2
        coEvery { trips.apply(any()) } returns 3
        coEvery { dtcHistory.mergeFrom(any()) } returns Unit
        val result = importer.apply(all, setOf(DataPart.DTC_HISTORY, DataPart.TRIPS, DataPart.LOGBOOK))

        coVerifyOrder { logbook.apply(any()); trips.apply(any()); dtcHistory.mergeFrom(dtcPlan.entries) }
        assertEquals(listOf(DataPart.LOGBOOK, DataPart.TRIPS, DataPart.DTC_HISTORY), result.outcomes.keys.toList())
        assertEquals(PartOutcome.Done(2), result.outcomes[DataPart.DTC_HISTORY])
    }

    @Test
    fun `a failing fault history is reported as failed and the other parts stay done`() = runTest {
        val boom = IllegalStateException("disk full")
        coEvery { trips.apply(any()) } returns 3
        coEvery { dtcHistory.mergeFrom(any()) } throws boom
        val result = importer.apply(all, setOf(DataPart.DTC_HISTORY, DataPart.TRIPS))

        assertEquals(PartOutcome.Done(3), result.outcomes[DataPart.TRIPS])
        assertEquals(PartOutcome.Failed(boom), result.outcomes[DataPart.DTC_HISTORY])
    }

    @Test
    fun `an unchosen fault history is not merged`() = runTest {
        coEvery { trips.apply(any()) } returns 3
        importer.apply(all, setOf(DataPart.TRIPS))

        coVerify(exactly = 0) { dtcHistory.mergeFrom(any()) }
    }

    @Test
    fun `a failing part does not stop the others`() = runTest {
        val boom = IllegalStateException("boom")
        coEvery { logbook.apply(any()) } returns 2
        coEvery { trips.apply(any()) } throws boom
        val result = importer.apply(both, setOf(DataPart.TRIPS, DataPart.LOGBOOK))
        assertEquals(PartOutcome.Done(2), result.outcomes[DataPart.LOGBOOK])
        assertEquals(PartOutcome.Failed(boom), result.outcomes[DataPart.TRIPS])

        // und umgekehrt: der erste Teil scheitert, der zweite laeuft
        coEvery { logbook.apply(any()) } throws boom
        coEvery { trips.apply(any()) } returns 3
        val second = importer.apply(both, setOf(DataPart.TRIPS, DataPart.LOGBOOK))
        assertEquals(PartOutcome.Failed(boom), second.outcomes[DataPart.LOGBOOK])
        assertEquals(PartOutcome.Done(3), second.outcomes[DataPart.TRIPS])
    }

    @Test
    fun `only the chosen parts are written`() = runTest {
        coEvery { trips.apply(any()) } returns 1
        val result = importer.apply(both, setOf(DataPart.TRIPS))
        assertEquals(listOf(DataPart.TRIPS), result.outcomes.keys.toList())
        coVerify(exactly = 0) { logbook.apply(any()) }
    }

    @Test
    fun `an unreadable part is never applied`() = runTest {
        val preview = TransferPreview(FileKind.PACKAGE, false, false, mapOf(DataPart.TRIPS to PartPlan.Unreadable(de.adv990de.obd2.transfer.UnreadableReason.FORMAT)))
        val result = importer.apply(preview, setOf(DataPart.TRIPS))
        assertTrue(result.outcomes.isEmpty())
        coVerify(exactly = 0) { trips.apply(any()) }
    }

    @Test
    fun `a chosen part that is not in the plan is skipped`() = runTest {
        val preview = TransferPreview(FileKind.PACKAGE, false, false, mapOf(DataPart.TRIPS to PartPlan.Trips(tripPlan)))
        coEvery { trips.apply(any()) } returns 0
        val result = importer.apply(preview, setOf(DataPart.TRIPS, DataPart.LOGBOOK, DataPart.SETTINGS))
        assertEquals(listOf(DataPart.TRIPS), result.outcomes.keys.toList())
    }

    @Test
    fun `a cancellation ends everything and the later part does not run`() = runTest {
        coEvery { logbook.apply(any()) } throws CancellationException("cancelled")
        try {
            importer.apply(both, setOf(DataPart.TRIPS, DataPart.LOGBOOK))
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        coVerify(exactly = 0) { trips.apply(any()) }
    }

    @Test
    fun `an empty selection gives an empty result`() = runTest {
        assertTrue(importer.apply(both, emptySet()).outcomes.isEmpty())
    }

    // --- inspect (echte Importer, ContentResolver gestellt) ---

    private val sessionRepo = mockk<SessionRepository>()
    private val logRepo = mockk<VehicleLogRepository>()
    private val realContext = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val real = TransferImporter(realContext, TripImporter(realContext, sessionRepo), LogbookImporter(realContext, logRepo), mockk<DtcHistoryRepository>(), mockk<SettingsTransfer>(), mockk<FuelRepository>(), mockk<LogbookOdometer>(), logRepo)

    private val tripsCsv = "timestamp,pid,value,name,source,session\n${nowMs - 5_000},010C,1500.0,Engine RPM,ECU,1\n"
    private val logbookCsv = logbookCsvHeader() + logbookCsvLine(
        LogEntry(type = LogType.FUEL, date = LocalDate.of(2026, 9, 1), odometerKm = 1000, amountMl = 12_000, fullTank = true, costCents = null, note = null, brand = null),
    )

    private fun serve(bytes: ByteArray, oneByteAtATime: Boolean = false): Uri {
        val uri = mockk<Uri>()
        every { realContext.contentResolver } returns resolver
        every { realContext.cacheDir } returns tmp.root
        every { resolver.openInputStream(uri) } answers {
            val base = ByteArrayInputStream(bytes)
            if (oneByteAtATime) object : FilterInputStream(base) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
            } else base
        }
        coEvery { sessionRepo.sessionSpans() } returns emptyList()
        coEvery { logRepo.all() } returns emptyList()
        return uri
    }

    private fun packageBytes(manifest: String? = PackageManifest(1, "de.adv990de.obd2", "1.0", nowMs, emptyMap()).toJson(), vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            if (manifest != null) { z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toByteArray()); z.closeEntry() }
            for ((name, text) in entries) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun copies(): List<File> = File(tmp.root, TRANSFER_IMPORT_CACHE_DIR).listFiles().orEmpty().toList()

    @Test
    fun `each csv kind is read the way it is today`() = runTest {
        val t = real.inspect(serve(tripsCsv.toByteArray()), nowMs, today)
        assertEquals(FileKind.TRIPS_CSV, t.kind)
        assertEquals(1, (t.parts.getValue(DataPart.TRIPS) as PartPlan.Trips).plan.trips.size)
        val l = real.inspect(serve(logbookCsv.toByteArray()), nowMs, today)
        assertEquals(FileKind.LOGBOOK_CSV, l.kind)
        assertEquals(1, (l.parts.getValue(DataPart.LOGBOOK) as PartPlan.Logbook).plan.toInsert.size)
    }

    @Test
    fun `binary garbage and an app log csv are refused with their own reason`() = runTest {
        try {
            real.inspect(serve(ByteArray(300) { (it * 7).toByte() }), nowMs, today)
            fail("expected TransferFileException")
        } catch (e: TransferFileException) {
            assertEquals(TransferFileException.Kind.UNRECOGNIZED, e.kind)
        }
        try {
            real.inspect(serve("timestamp,level,message\n1,INFO,x\n".toByteArray()), nowMs, today)
            fail("expected TransferFileException")
        } catch (e: TransferFileException) {
            assertEquals(TransferFileException.Kind.APP_LOG_NOT_IMPORTABLE, e.kind)
        }
    }

    @Test
    fun `a package is read, even from a stream that gives one byte per read, and the cache copy is gone`() = runTest {
        val bytes = packageBytes(entries = arrayOf("logbook.csv" to logbookCsv, "trips.csv" to tripsCsv))
        val preview = real.inspect(serve(bytes, oneByteAtATime = true), nowMs, today)
        assertEquals(FileKind.PACKAGE, preview.kind)
        assertEquals(listOf(DataPart.LOGBOOK, DataPart.TRIPS), preview.parts.keys.toList())
        assertTrue(copies().isEmpty())
    }

    @Test
    fun `the cache copy is gone after a failure too`() = runTest {
        val bytes = packageBytes(manifest = null, entries = arrayOf("trips.csv" to tripsCsv))
        try {
            real.inspect(serve(bytes), nowMs, today)
            fail("expected TransferFileException")
        } catch (e: TransferFileException) {
            assertEquals(TransferFileException.Kind.NOT_A_BACKUP, e.kind)
        }
        assertTrue(copies().isEmpty())
        // ein beschaedigtes ZIP (Kopf stimmt, Rest fehlt)
        try {
            real.inspect(serve(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4)), nowMs, today)
            fail("expected TransferFileException")
        } catch (e: TransferFileException) {
            assertEquals(TransferFileException.Kind.NOT_A_BACKUP, e.kind)
        }
        assertTrue(copies().isEmpty())
    }

    @Test
    fun `a file above the package limit is refused as too large and no copy stays`() = runTest {
        real.maxPackageBytes = 100
        val uri = serve(packageBytes(entries = arrayOf("logbook.csv" to logbookCsv.repeat(20))))
        try {
            real.inspect(uri, nowMs, today)
            fail("expected FileTooLargeException")
        } catch (_: FileTooLargeException) {
        }
        assertTrue(copies().isEmpty())
    }

    @Test
    fun `the copy is never put under exports, the folder the file provider shares`() = runTest {
        val dirs = mutableListOf<List<String>>()
        val bytes = packageBytes(entries = arrayOf("logbook.csv" to logbookCsv))
        val uri = serve(bytes)
        every { resolver.openInputStream(uri) } answers {
            dirs += File(tmp.root, "exports").list().orEmpty().toList()
            ByteArrayInputStream(bytes)
        }
        real.inspect(uri, nowMs, today)
        assertTrue(dirs.all { it.isEmpty() })
        assertTrue(File(tmp.root, "exports").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a cancellation in the middle of the copy leaves no copy behind`() = runTest {
        val bytes = packageBytes(entries = arrayOf("logbook.csv" to logbookCsv))
        val uri = serve(bytes)
        var opened = 0
        every { resolver.openInputStream(uri) } answers {
            // first open = the head, second open = the copy: it delivers some bytes and is then cancelled
            if (++opened == 1) ByteArrayInputStream(bytes) else object : java.io.InputStream() {
                var n = 0
                override fun read(): Int = throw CancellationException("cancelled")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (n++ == 0) { System.arraycopy(bytes, 0, b, off, minOf(10, len)); return minOf(10, len) }
                    throw CancellationException("cancelled")
                }
            }
        }
        try {
            real.inspect(uri, nowMs, today)
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertTrue(copies().isEmpty())
    }

    @Test
    fun `a stale copy of a killed process is removed`() = runTest {
        val dir = File(tmp.root, TRANSFER_IMPORT_CACHE_DIR).apply { mkdirs() }
        val stale = File(dir, "package_old.zip").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000) }
        val fresh = File(dir, "package_new.zip").apply { writeText("x") }
        real.inspect(serve(packageBytes(entries = arrayOf("logbook.csv" to logbookCsv))), nowMs, today)
        assertTrue(!stale.exists())
        assertTrue(fresh.exists())
    }

    // --- Einstellungen und Zaehler (Phase 3) ---

    private fun change(key: String, to: Any) = SettingChange(SettingsCatalog.spec(key)!!, null, to)
    private val settingsPlan = SettingsPlan(listOf(change("theme", "amber"), change("use_imperial", true)), unchanged = 3, rejected = emptyList(), unknownKeys = listOf("x"))
    private val counters = CounterValues(12.5f, 1000, 4.25f)

    private fun counterPlan(decision: OdometerDecision, values: CounterValues = counters, rejected: List<String> = emptyList()) =
        PartPlan.Counters(values, rejected, decision, predictedMaxKm = 1000)

    private fun everything(counterPlan: PartPlan.Counters = counterPlan(OdometerDecision.APPLY)) = TransferPreview(
        FileKind.PACKAGE, false, false,
        linkedMapOf(
            DataPart.LOGBOOK to PartPlan.Logbook(logPlan), DataPart.TRIPS to PartPlan.Trips(tripPlan), DataPart.DTC_HISTORY to PartPlan.DtcHistory(dtcPlan),
            DataPart.SETTINGS to PartPlan.Settings(settingsPlan), DataPart.COUNTERS to counterPlan,
        ),
    )

    private val allParts = setOf(DataPart.LOGBOOK, DataPart.TRIPS, DataPart.DTC_HISTORY, DataPart.SETTINGS, DataPart.COUNTERS)

    private fun stubAllApply(highest: Int? = 1000) {
        coEvery { logbook.apply(any()) } returns 2
        coEvery { trips.apply(any()) } returns 3
        coEvery { dtcHistory.mergeFrom(any()) } returns Unit
        coEvery { settingsTransfer.apply(any()) } returns Unit
        every { logEntries.maxOdometerKm } returns flowOf(highest)
        every { fuel.restore(any()) } returns Unit
        every { odometer.restore(any()) } returns Unit
    }

    @Test
    fun `the parts are written in the order logbook, trips, fault history, settings, counters`() = runTest {
        stubAllApply()
        val result = importer.apply(everything(), allParts)

        coVerifyOrder {
            logbook.apply(any()); trips.apply(any()); dtcHistory.mergeFrom(any()); settingsTransfer.apply(any())
            logEntries.maxOdometerKm; fuel.restore(12.5f); odometer.restore(GpsOdometer(1000, 4.25f))
        }
        assertEquals(DataPart.IMPORT_ORDER, result.outcomes.keys.toList())
    }

    @Test
    fun `settings apply only the changed values by key and report the count and the skipped ones`() = runTest {
        stubAllApply()
        val applied = slot<Map<String, Any>>()
        coEvery { settingsTransfer.apply(capture(applied)) } returns Unit
        val result = importer.apply(everything(), setOf(DataPart.SETTINGS))

        assertEquals(mapOf<String, Any>("theme" to "amber", "use_imperial" to true), applied.captured)
        assertEquals(PartOutcome.Done(count = 2, skipped = 1), result.outcomes[DataPart.SETTINGS])
    }

    @Test
    fun `a failing settings write is reported as failed and the other parts still run`() = runTest {
        stubAllApply()
        val boom = IllegalStateException("disk")
        coEvery { settingsTransfer.apply(any()) } throws boom
        val result = importer.apply(everything(), allParts)

        assertEquals(PartOutcome.Failed(boom), result.outcomes[DataPart.SETTINGS])
        assertEquals(PartOutcome.Done(3), result.outcomes[DataPart.TRIPS])
        assertEquals(PartOutcome.Done(count = 2), result.outcomes[DataPart.COUNTERS])
    }

    @Test
    fun `the anchor rule is decided at apply time against the logbook after its import`() = runTest {
        stubAllApply(highest = 1234)   // the preview predicted 1000 (APPLY), but the highest stored mileage is now 1234
        val result = importer.apply(everything(), allParts)

        verify(exactly = 1) { fuel.restore(12.5f) }
        verify(exactly = 0) { odometer.restore(any()) }
        assertEquals(PartOutcome.Done(count = 1, skipped = 1), result.outcomes[DataPart.COUNTERS])
    }

    @Test
    fun `a matching anchor takes the estimate over and counts both counters`() = runTest {
        stubAllApply(highest = 1000)
        val result = importer.apply(everything(counterPlan(OdometerDecision.SKIP_ANCHOR_MISMATCH)), setOf(DataPart.COUNTERS))

        verify(exactly = 1) { odometer.restore(GpsOdometer(1000, 4.25f)) }
        assertEquals(PartOutcome.Done(count = 2, skipped = 0), result.outcomes[DataPart.COUNTERS])
    }

    @Test
    fun `without any logbook entry the estimate is skipped, the fuel counter still comes`() = runTest {
        stubAllApply(highest = null)
        val result = importer.apply(everything(), setOf(DataPart.COUNTERS))

        verify(exactly = 1) { fuel.restore(12.5f) }
        verify(exactly = 0) { odometer.restore(any()) }
        assertEquals(PartOutcome.Done(count = 1, skipped = 1), result.outcomes[DataPart.COUNTERS])
    }

    @Test
    fun `a file without an estimate restores only the fuel counter and skips nothing`() = runTest {
        stubAllApply()
        val onlyFuel = counterPlan(OdometerDecision.ABSENT, CounterValues(7f, null, null))
        val result = importer.apply(everything(onlyFuel), setOf(DataPart.COUNTERS))

        verify(exactly = 1) { fuel.restore(7f) }
        verify(exactly = 0) { odometer.restore(any()) }
        assertEquals(PartOutcome.Done(count = 1, skipped = 0), result.outcomes[DataPart.COUNTERS])
    }

    @Test
    fun `counters of a file with a rejected pair count the rejection as skipped`() = runTest {
        stubAllApply()
        val rejected = counterPlan(OdometerDecision.ABSENT, CounterValues(7f, null, null), rejected = listOf("logbook_gps_anchor_km"))
        val result = importer.apply(everything(rejected), setOf(DataPart.COUNTERS))

        assertEquals(PartOutcome.Done(count = 1, skipped = 1), result.outcomes[DataPart.COUNTERS])
    }

    @Test
    fun `unchosen settings and counters are not written`() = runTest {
        stubAllApply()
        importer.apply(everything(), setOf(DataPart.TRIPS))

        coVerify(exactly = 0) { settingsTransfer.apply(any()) }
        verify(exactly = 0) { fuel.restore(any()) }
        verify(exactly = 0) { odometer.restore(any()) }
    }

    @Test
    fun `a failing fuel restore marks the counters as failed`() = runTest {
        stubAllApply()
        val boom = IllegalStateException("x")
        every { fuel.restore(any()) } throws boom
        val result = importer.apply(everything(), setOf(DataPart.COUNTERS))

        assertEquals(PartOutcome.Failed(boom), result.outcomes[DataPart.COUNTERS])
    }
}
