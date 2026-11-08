package de.adv990de.obd2.data

import android.content.Context
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.fuel.FuelRepository
import de.adv990de.obd2.idleObdRepository
import de.adv990de.obd2.logbook.GpsOdometer
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.ExportFormat
import de.adv990de.obd2.transfer.FileKind
import de.adv990de.obd2.transfer.OdometerDecision
import de.adv990de.obd2.transfer.PackageManifest
import de.adv990de.obd2.transfer.PartOutcome
import de.adv990de.obd2.transfer.PartPlan
import de.adv990de.obd2.transfer.hasNews
import de.adv990de.obd2.transfer.toJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/**
 * Daten sichern und wiederherstellen als Ganzes gegen die echte (In-Memory-)Datenbank und echte Dateien: Paket schreiben, Daten loeschen, Paket einlesen — dieselben Fahrten in derselben
 * Reihenfolge und dieselben Logbucheintraege; die CSV je Art ist **byte-gleich** der des bisherigen Exports und wieder einlesbar; ein zweiter Import derselben Datei aendert nichts; ein
 * Fehler mitten im Schreiben eines Teils laesst diesen Teil unberuehrt und die anderen stehen; alte CSV-Dateien und das Paket einer neueren App werden gelesen. Der Systemdialog bleibt
 * aussen vor (echte `file:`-Uris).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TransferRoundTripDeviceTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @BindValue @JvmField val obdRepository: ObdRepository = idleObdRepository()

    @Inject lateinit var db: AppDatabase
    @Inject lateinit var sessions: SessionRepository
    @Inject lateinit var logbook: VehicleLogRepository
    @Inject lateinit var logbookExporter: LogbookExporter
    @Inject lateinit var appLogExporter: AppLogExporter
    @Inject lateinit var appLog: AppLogRepository
    @Inject lateinit var exporter: TransferExporter
    @Inject lateinit var importer: TransferImporter
    @Inject lateinit var prefs: AppPreferences
    @Inject lateinit var fuel: FuelRepository
    @Inject lateinit var odometer: LogbookOdometer

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val files = ArrayList<File>()
    private val now = System.currentTimeMillis()
    private val today = LocalDate.now()

    // Zwei Tage zurueck: innerhalb der 90 Tage der Aufbewahrung und weit genug von „jetzt“.
    private val start = now - 2L * 24 * 3_600_000

    @Before
    fun setup() {
        hiltRule.inject()
        File(ctx.cacheDir, "exports").deleteRecursively()
        File(ctx.cacheDir, TRANSFER_IMPORT_CACHE_DIR).deleteRecursively()
    }

    @After
    fun teardown() {
        files.forEach { it.delete() }
    }

    private fun tempFile(name: String) = File(ctx.cacheDir, name).also { it.delete(); files += it }

    private fun countOf(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun recordedTrip(vin: String?, vararg rows: Array<Any?>, dtcCodes: String? = null): Long {
        val times = rows.map { it[0] as Long }
        val id = db.sessionDao().insert(SessionEntity(startTime = times.min() - 1_000, endTime = times.max() + 1_000, vehicleVin = vin, dtcCodes = dtcCodes))
        for (row in rows) {
            db.sensorReadingDao().insert(
                SensorReadingEntity(sessionId = id, timestamp = row[0] as Long, pidId = row[1] as String, value = (row[2] as Number).toFloat(), source = row[3] as String?),
            )
        }
        return id
    }

    /** Alle Messwerte aller Fahrten in Fahrten-Reihenfolge (Id), je Fahrt in der Aufzeichnungsreihenfolge, ohne Ids. */
    private fun allReadings(): List<List<Any?>> = db.openHelper.readableDatabase
        .query("SELECT r.timestamp, r.pidId, r.value, r.source FROM sensor_readings r JOIN sessions s ON s.id = r.sessionId ORDER BY s.id, r.id")
        .use { c -> generateSequence { if (c.moveToNext()) listOf(c.getLong(0), c.getString(1), c.getFloat(2), if (c.isNull(3)) null else c.getString(3)) else null }.toList() }

    private fun entry(type: LogType, km: Int, daysAgo: Long, amountMl: Int? = null, full: Boolean? = null, cost: Int? = null, brand: String? = null) =
        LogEntry(type = type, date = today.minusDays(daysAgo), odometerKm = km, amountMl = amountMl, fullTank = full, costCents = cost, note = null, brand = brand)

    private suspend fun seed() {
        recordedTrip(
            "TESTVIN0000000001",
            arrayOf(start, "010C", 1612.5f, "ECU"), arrayOf(start + 1_000, "010D", 52.25f, "GPS"), arrayOf(start + 2_000, "GEAR", 3, "DERIVED"),
            arrayOf(start + 500, "0105", 79, null), // ein Zeitsprung rueckwaerts und eine Zeile ohne Herkunft: Reihenfolge und fehlende Herkunft bleiben
        )
        recordedTrip(null, arrayOf(start + 3_600_000, "0142", 13.7f, "ECU"), arrayOf(start + 3_601_000, "VALUES_STATE", -1, "DERIVED"))
        logbook.save(entry(LogType.FUEL, 1000, 30, amountMl = 12_345, full = true, cost = 2_150))
        logbook.save(entry(LogType.FUEL, 1400, 20, amountMl = 6_000, full = false))
        logbook.save(entry(LogType.OIL, 1450, 10, amountMl = 250))
        logbook.save(entry(LogType.TIRE_CHANGE, 1500, 5, brand = "Muster Tour"))
        appLog.log(LogLevel.INFO, "test line, with a comma")
    }

    private suspend fun wipe() {
        sessions.deleteSessions(sessions.exportableSessionIds())
        logbook.delete(logbook.all().map { it.id })
    }

    private suspend fun saveRequest(parts: Set<DataPart>, format: ExportFormat, name: String): File {
        val file = tempFile(name)
        exporter.save(Uri.fromFile(file), ExportRequest(parts, format))
        return file
    }

    private fun logbookKey(e: LogEntry) = e.copy(id = 0)

    private fun packageFile(name: String, manifestFormat: Int = 1, vararg entries: Pair<String, String>): Uri {
        val f = tempFile(name)
        ZipOutputStream(FileOutputStream(f)).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(PackageManifest(manifestFormat, "de.adv990de.obd2", "9.9", now, emptyMap()).toJson().toByteArray())
            z.closeEntry()
            for ((n, t) in entries) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
        }
        return Uri.fromFile(f)
    }

    // ── 1. Runde hin und zurueck ──────────────────────────────────────────────

    @Test
    fun aPackageWrittenAndReadBackRestoresTheSameTripsInTheSameOrderAndTheSameLogbook() = runBlocking {
        seed()
        val readingsBefore = allReadings()
        val logbookBefore = logbook.all().map(::logbookKey)
        val file = saveRequest(setOf(DataPart.TRIPS, DataPart.LOGBOOK, DataPart.APP_LOG), ExportFormat.PACKAGE, "round-trip.zip")
        wipe()
        assertEquals(0, countOf("sessions"))
        assertEquals(0, countOf("vehicle_log"))

        val preview = importer.inspect(Uri.fromFile(file), now, today)
        assertEquals(FileKind.PACKAGE, preview.kind)
        assertEquals("the app log is in the package and ignored", true, preview.ignoredAppLog)
        assertEquals(listOf(DataPart.LOGBOOK, DataPart.TRIPS), preview.parts.keys.toList())
        val result = importer.apply(preview, setOf(DataPart.LOGBOOK, DataPart.TRIPS))

        assertEquals(PartOutcome.Done(4), result.outcomes.getValue(DataPart.LOGBOOK))
        assertEquals(PartOutcome.Done(2), result.outcomes.getValue(DataPart.TRIPS))
        assertEquals("same readings, same order, missing sources stay missing", readingsBefore, allReadings())
        assertEquals(logbookBefore.sortedBy { it.odometerKm }, logbook.all().map(::logbookKey).sortedBy { it.odometerKm })
        assertEquals("the app log is not touched by an import", 1, countOf("app_log"))
    }

    // ── 2. CSV je Art: byte-gleich dem bisherigen Export, wieder einlesbar ────

    @Test
    fun theCsvPerKindIsByteIdenticalToTheFileOfTheOldExportAndReadsBack() = runBlocking {
        seed()
        val ids = sessions.exportableSessionIds()

        val tripsNew = saveRequest(setOf(DataPart.TRIPS), ExportFormat.CSV, "trips-new.csv")
        val tripsOld = tempFile("trips-old.csv").also { sessions.saveSessionsCsv(ids, Uri.fromFile(it)) }
        assertArrayEquals(tripsOld.readBytes(), tripsNew.readBytes())

        val logbookNew = saveRequest(setOf(DataPart.LOGBOOK), ExportFormat.CSV, "logbook-new.csv")
        val logbookOld = tempFile("logbook-old.csv").also { logbookExporter.saveCsv(Uri.fromFile(it)) }
        assertArrayEquals(logbookOld.readBytes(), logbookNew.readBytes())

        val logNew = saveRequest(setOf(DataPart.APP_LOG), ExportFormat.CSV, "applog-new.csv")
        val logOld = tempFile("applog-old.csv").also { appLogExporter.saveCsv(Uri.fromFile(it)) }
        assertArrayEquals(logOld.readBytes(), logNew.readBytes())

        wipe()
        val tripsPreview = importer.inspect(Uri.fromFile(tripsNew), now, today)
        assertEquals(FileKind.TRIPS_CSV, tripsPreview.kind)
        assertEquals(2, (tripsPreview.parts.getValue(DataPart.TRIPS) as PartPlan.Trips).plan.trips.size)
        val logbookPreview = importer.inspect(Uri.fromFile(logbookNew), now, today)
        assertEquals(FileKind.LOGBOOK_CSV, logbookPreview.kind)
        assertEquals(4, (logbookPreview.parts.getValue(DataPart.LOGBOOK) as PartPlan.Logbook).plan.toInsert.size)
    }

    // ── 3. Zweiter Import derselben Datei aendert nichts ──────────────────────

    @Test
    fun aSecondImportOfTheSameFileChangesNothing() = runBlocking {
        seed()
        val file = saveRequest(setOf(DataPart.TRIPS, DataPart.LOGBOOK), ExportFormat.PACKAGE, "twice.zip")
        wipe()
        val parts = setOf(DataPart.LOGBOOK, DataPart.TRIPS)
        importer.apply(importer.inspect(Uri.fromFile(file), now, today), parts)
        val sessionsAfterFirst = countOf("sessions")
        val readingsAfterFirst = countOf("sensor_readings")
        val logbookAfterFirst = countOf("vehicle_log")

        val second = importer.inspect(Uri.fromFile(file), now, today)

        assertTrue((second.parts.getValue(DataPart.TRIPS) as PartPlan.Trips).plan.trips.isEmpty())
        assertEquals(2, (second.parts.getValue(DataPart.TRIPS) as PartPlan.Trips).plan.duplicateTrips)
        assertTrue((second.parts.getValue(DataPart.LOGBOOK) as PartPlan.Logbook).plan.toInsert.isEmpty())
        importer.apply(second, parts)
        assertEquals(sessionsAfterFirst, countOf("sessions"))
        assertEquals(readingsAfterFirst, countOf("sensor_readings"))
        assertEquals(logbookAfterFirst, countOf("vehicle_log"))
    }

    // ── 4. Fehler mitten im Schreiben eines Teils ─────────────────────────────

    @Test
    fun aFailureInTheMiddleOfWritingOnePartLeavesThatPartUntouchedAndTheOtherPartStands() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_on_boom BEFORE INSERT ON sensor_readings WHEN NEW.pidId = 'BOOM' BEGIN SELECT RAISE(ABORT, 'boom'); END")
        val untouched = recordedTrip(null, arrayOf(start - 100_000_000, "010C", 1f, "ECU"))
        val tripsCsv = "timestamp,pid,value,name,source,session\n" +
            "$start,010C,1.0,RPM,ECU,1\n${start + 1_000},010C,2.0,RPM,ECU,1\n" +
            "${start + 5_000_000},010C,3.0,RPM,ECU,2\n${start + 5_001_000},BOOM,4.0,RPM,ECU,2\n${start + 5_002_000},010C,5.0,RPM,ECU,2\n"
        val logbookCsv = "date,type,odometer_km,amount_ml,full_tank,cost_eur,note,brand\n${today.minusDays(3)},OIL,2000,300,,,,\n"
        val uri = packageFile("boom.zip", 1, "trips.csv" to tripsCsv, "logbook.csv" to logbookCsv)

        val preview = importer.inspect(uri, now, today)
        val result = importer.apply(preview, setOf(DataPart.LOGBOOK, DataPart.TRIPS))

        assertEquals(PartOutcome.Done(1), result.outcomes.getValue(DataPart.LOGBOOK))
        val failed = result.outcomes.getValue(DataPart.TRIPS)
        assertTrue("trips failed, got $failed", failed is PartOutcome.Failed && failed.cause is SQLiteException)
        assertEquals("the logbook part stands", 1, countOf("vehicle_log"))
        assertEquals("the trips part is rolled back as a whole", listOf(untouched), db.openHelper.readableDatabase.query("SELECT id FROM sessions").use { c -> generateSequence { if (c.moveToNext()) c.getLong(0) else null }.toList() })
        assertEquals(1, countOf("sensor_readings"))
    }

    // ── 5. Alte Fahrten- und Logbuch-CSV ──────────────────────────────────────

    @Test
    fun oldCsvFilesAreStillRead_theTripsFileBeforeSourceAndSession_theLogbookFileWithSevenColumns() = runBlocking {
        val oldTrips = tempFile("old-trips.csv").also { it.writeText("timestamp,pid,value\n$start,010C,1500.0\n${start + 1_000},010C,1600.0\n") }
        val oldLogbook = tempFile("old-logbook.csv").also {
            it.writeText("date,type,odometer_km,amount_ml,full_tank,cost_eur,note\n${today.minusDays(4)},FUEL,1000,12000,1,25.50,\n")
        }

        val tripsPlan = importer.inspect(Uri.fromFile(oldTrips), now, today)
        val logbookPlan = importer.inspect(Uri.fromFile(oldLogbook), now, today)
        val result = importer.apply(tripsPlan, setOf(DataPart.TRIPS))
        importer.apply(logbookPlan, setOf(DataPart.LOGBOOK))

        assertEquals(FileKind.TRIPS_CSV, tripsPlan.kind)
        assertEquals(FileKind.LOGBOOK_CSV, logbookPlan.kind)
        assertEquals(PartOutcome.Done(1), result.outcomes.getValue(DataPart.TRIPS))
        assertEquals(listOf(listOf(start, "010C", 1500f, null), listOf(start + 1_000, "010C", 1600f, null)), allReadings())
        val entry = logbook.all().single()
        assertEquals(LogType.FUEL, entry.type)
        assertEquals(2_550, entry.costCents)
        assertEquals(null, entry.brand)
    }

    // ── 6. Paket einer neueren App ────────────────────────────────────────────

    @Test
    fun aPackageOfANewerAppIsReadForTheKnownPartsAndTheUnknownEntryIsIgnored() = runBlocking {
        val logbookCsv = "date,type,odometer_km,amount_ml,full_tank,cost_eur,note,brand\n${today.minusDays(3)},OIL,2000,300,,,,\n"
        val uri = packageFile("newer.zip", 2, "logbook.csv" to logbookCsv, "future_things.json" to "{}")

        val preview = importer.inspect(uri, now, today)

        assertTrue(preview.newerFormat)
        // settings.json ist seit Phase 3 ein bekannter Teil; nur der unbekannte Eintrag future_things.json wird uebergangen
        assertEquals("only what this version can read: the logbook", listOf(DataPart.LOGBOOK), preview.parts.keys.toList())
        importer.apply(preview, setOf(DataPart.LOGBOOK))
        assertEquals(1, countOf("vehicle_log"))
    }

    // ── 7. FIN und Fehlercodes je Fahrt (trips_meta.csv) ──────────────────────

    /** Die Fahrten in Zeitreihenfolge als (FIN, Fehlercodes). */
    private fun tripDetails(): List<Pair<String?, String?>> = db.openHelper.readableDatabase
        .query("SELECT vehicleVin, dtcCodes FROM sessions ORDER BY startTime")
        .use { c -> generateSequence { if (c.moveToNext()) Pair(if (c.isNull(0)) null else c.getString(0), if (c.isNull(1)) null else c.getString(1)) else null }.toList() }

    private suspend fun seedWithDetails() {
        recordedTrip("TESTVIN0000000001", arrayOf(start, "010C", 1f, "ECU"), dtcCodes = "P0301,P0420?")
        recordedTrip(null, arrayOf(start + 3_600_000, "010C", 2f, "ECU"), dtcCodes = "")
        recordedTrip(null, arrayOf(start + 7_200_000, "010C", 3f, "ECU"), dtcCodes = null)
        recordedTrip("TESTVIN0000000002", arrayOf(start + 10_800_000, "010C", 4f, "ECU"), dtcCodes = null)
    }

    @Test
    fun theVinAndTheCodesSurviveTheRound_inAllThreeStates() = runBlocking {
        seedWithDetails()
        val before = tripDetails()
        val file = saveRequest(setOf(DataPart.TRIPS), ExportFormat.PACKAGE, "details.zip")
        assertTrue("the package carries trips_meta.csv", java.util.zip.ZipFile(file).use { z -> z.getEntry("trips_meta.csv") != null })
        wipe()

        val preview = importer.inspect(Uri.fromFile(file), now, today)
        val trips = preview.parts.getValue(DataPart.TRIPS) as PartPlan.Trips
        assertEquals(2, trips.meta.withVin)
        assertEquals(2, trips.meta.withCodesRecorded)
        importer.apply(preview, setOf(DataPart.TRIPS))

        assertEquals(listOf("TESTVIN0000000001" to "P0301,P0420?", null to "", null to null, "TESTVIN0000000002" to null), before)
        assertEquals(before, tripDetails())
    }

    @Test
    fun aSecondRoundOfTheSameFileChangesNoDetail_andTheTripsAlreadyThereKeepTheirs() = runBlocking {
        seedWithDetails()
        val file = saveRequest(setOf(DataPart.TRIPS), ExportFormat.PACKAGE, "details-twice.zip")
        // The trips are still in the app, with a different VIN and no codes: the import must not touch them.
        db.openHelper.writableDatabase.execSQL("UPDATE sessions SET vehicleVin = 'TESTVIN0000000009', dtcCodes = NULL")
        val changed = tripDetails()

        val preview = importer.inspect(Uri.fromFile(file), now, today)
        val trips = preview.parts.getValue(DataPart.TRIPS) as PartPlan.Trips
        assertTrue(trips.plan.trips.isEmpty())
        assertEquals(4, trips.plan.duplicateTrips)
        assertEquals("the details of trips that are already there are dropped (three of the four trips have details)", 3, trips.meta.orphanRows)
        importer.apply(preview, setOf(DataPart.TRIPS))

        assertEquals(changed, tripDetails())
        assertEquals(4, countOf("sessions"))
    }

    @Test
    fun noDetailNoMetaFile() = runBlocking {
        recordedTrip(null, arrayOf(start, "010C", 1f, "ECU"))
        val file = saveRequest(setOf(DataPart.TRIPS), ExportFormat.PACKAGE, "no-details.zip")
        assertFalse(java.util.zip.ZipFile(file).use { z -> z.getEntry("trips_meta.csv") != null })
    }

    // ── Fehlerhistorie ──

    @Inject lateinit var dtcHistory: DtcHistoryRepository

    private fun historyEntry(code: String, firstAgo: Long, lastAgo: Long, pending: Boolean) =
        DtcHistoryEntry(code, now - firstAgo * 3_600_000, now - lastAgo * 3_600_000, pending)

    private fun clearHistory() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM dtc_history")
    }

    @Test
    fun theFaultHistoryReadsBackWithCodesTimesAndFlag_andASecondRoundChangesNothing() = runBlocking {
        val entries = listOf(historyEntry("P0301", 50, 10, false), historyEntry("P0420", 20, 20, true), historyEntry("U0100", 70, 5, false))
        dtcHistory.mergeFrom(entries)
        val before = dtcHistory.all()
        val file = saveRequest(setOf(DataPart.DTC_HISTORY), ExportFormat.PACKAGE, "history.zip")
        assertEquals(listOf("manifest.json", "dtc_history.csv"), java.util.zip.ZipFile(file).use { z -> z.entries().toList().map { it.name } })
        clearHistory()

        val preview = importer.inspect(Uri.fromFile(file), now, today)
        val plan = (preview.parts.getValue(DataPart.DTC_HISTORY) as PartPlan.DtcHistory).plan
        assertEquals(3, plan.newCodes)
        val result = importer.apply(preview, setOf(DataPart.DTC_HISTORY))

        assertEquals(PartOutcome.Done(3), result.outcomes[DataPart.DTC_HISTORY])
        assertEquals(before, dtcHistory.all())

        val second = importer.inspect(Uri.fromFile(file), now, today)
        val secondPlan = (second.parts.getValue(DataPart.DTC_HISTORY) as PartPlan.DtcHistory).plan
        assertEquals(0, secondPlan.newCodes + secondPlan.updatedCodes)
        assertEquals(3, secondPlan.unchangedCodes)
        assertFalse(second.parts.getValue(DataPart.DTC_HISTORY).hasNews)
        importer.apply(second, setOf(DataPart.DTC_HISTORY))
        assertEquals(before, dtcHistory.all())
    }

    @Test
    fun anExistingNewerHistoryStaysNewer_theImportMergesAndDoesNotOverwrite() = runBlocking {
        dtcHistory.mergeFrom(listOf(historyEntry("P0301", 50, 10, false), historyEntry("P0420", 20, 20, true)))
        val file = saveRequest(setOf(DataPart.DTC_HISTORY), ExportFormat.PACKAGE, "older.zip")
        // Since the export the vehicle reported P0301 again (newer), and the history learnt a code the file does not know.
        clearHistory()
        dtcHistory.mergeFrom(listOf(historyEntry("P0301", 30, 1, true), historyEntry("P0500", 3, 3, false)))

        val preview = importer.inspect(Uri.fromFile(file), now, today)
        importer.apply(preview, setOf(DataPart.DTC_HISTORY))

        val byCode = dtcHistory.all().associateBy { it.code }
        assertEquals("the newer last sighting stays, and so does its flag", now - 1 * 3_600_000, byCode.getValue("P0301").lastSeen)
        assertTrue(byCode.getValue("P0301").lastWasPending)
        assertEquals("the earlier first sighting of the file wins", now - 50 * 3_600_000, byCode.getValue("P0301").firstSeen)
        assertTrue("a code only the file knows is added", "P0420" in byCode)
        assertTrue("a code only the app knows stays", "P0500" in byCode)
    }

    @Test
    fun aFailureWritingTheFaultHistoryLeavesItUntouched_andTheLogbookStands() = runBlocking {
        dtcHistory.mergeFrom(listOf(historyEntry("P0301", 50, 10, false)))
        val before = dtcHistory.all()
        logbook.save(entry(LogType.FUEL, 1000, 30, amountMl = 12_345, full = true))
        val file = saveRequest(setOf(DataPart.DTC_HISTORY, DataPart.LOGBOOK), ExportFormat.PACKAGE, "both.zip")
        // The package holds P0420 and P9999 as well, but the table refuses P9999.
        val extra = tempFile("extra.zip")
        ZipOutputStream(FileOutputStream(extra)).use { z ->
            java.util.zip.ZipFile(file).use { src -> src.entries().toList().filter { it.name != "dtc_history.csv" }.forEach { e -> z.putNextEntry(ZipEntry(e.name)); z.write(src.getInputStream(e).readBytes()); z.closeEntry() } }
            z.putNextEntry(ZipEntry("dtc_history.csv"))
            z.write("code,first_seen,last_seen,last_was_pending\nP0420,${now - 1_000},${now - 500},0\nP9999,${now - 1_000},${now - 500},0\n".toByteArray())
            z.closeEntry()
        }
        wipe()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_on_p9999 BEFORE INSERT ON dtc_history WHEN NEW.code = 'P9999' BEGIN SELECT RAISE(ABORT, 'boom'); END")

        val preview = importer.inspect(Uri.fromFile(extra), now, today)
        val result = importer.apply(preview, setOf(DataPart.DTC_HISTORY, DataPart.LOGBOOK))

        assertTrue(result.outcomes.getValue(DataPart.DTC_HISTORY) is PartOutcome.Failed)
        assertEquals("no half history", before, dtcHistory.all())
        assertTrue(result.outcomes.getValue(DataPart.LOGBOOK) is PartOutcome.Done)
        assertEquals(1, logbook.all().size)
    }

    // ── Einstellungen und Zaehler (Phase 3) ───────────────────────────────────

    /** Wartet (mit Zeitgrenze) bis [read] den erwarteten Wert liefert: der Zaehler schreibt gedrosselt, und das Logbuch meldet seinen hoechsten Stand asynchron. */
    private suspend fun <T> eventually(expected: T, read: suspend () -> T) {
        try {
            withTimeout(5_000) { while (read() != expected) delay(25) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            assertEquals("did not settle", expected, read())
        }
    }

    private suspend fun changeSettings() {
        prefs.setTheme("amber")
        prefs.setUseImperial(true)
        prefs.setSlotPid(0, "0105")
        prefs.setSlotPid(2, "TERRAIN")
        prefs.setAlarmCoolantWarningC(110)
        prefs.setWheelCircumferenceMm(2060f)
        prefs.setMaintenanceInterval(LogType.CHAIN_SMALL, 700)
        prefs.setFuelRangeFullKm(280)
        prefs.setFuelTankCapacityDl(205)
    }

    private suspend fun backToDefaults() {
        prefs.setTheme("system")
        prefs.setUseImperial(false)
        prefs.setSlotPid(0, "010C")
        prefs.setSlotPid(2, "")
        prefs.setAlarmCoolantWarningC(100)
        prefs.setWheelCircumferenceMm(2096f)
        prefs.setMaintenanceInterval(LogType.CHAIN_SMALL, 0)
        prefs.setFuelRangeFullKm(0)
        prefs.setFuelTankCapacityDl(0)
    }

    @Test
    fun settingsAndCountersComeBackAfterEverythingWasResetAndTheTankCounterShowsAtOnce() = runBlocking {
        seed()
        changeSettings()
        val settingsBefore = SettingsTransfer(prefs).snapshot()
        fuel.restore(12.5f)
        odometer.restore(GpsOdometer(1500, 4.25f))
        eventually(GpsOdometer(1500, 4.25f)) { odometer.current() }
        val file = saveRequest(setOf(DataPart.LOGBOOK, DataPart.SETTINGS, DataPart.COUNTERS), ExportFormat.PACKAGE, "settings-counters.zip")

        backToDefaults()
        fuel.restore(0f)
        wipe()
        eventually(null) { odometer.current() }
        assertTrue("the reset really changed something", SettingsTransfer(prefs).snapshot() != settingsBefore)

        val preview = importer.inspect(Uri.fromFile(file), now, today)
        assertEquals(listOf(DataPart.LOGBOOK, DataPart.SETTINGS, DataPart.COUNTERS), preview.parts.keys.toList())
        assertEquals(OdometerDecision.APPLY, (preview.parts.getValue(DataPart.COUNTERS) as PartPlan.Counters).decision)
        val result = importer.apply(preview, setOf(DataPart.LOGBOOK, DataPart.SETTINGS, DataPart.COUNTERS))

        assertTrue(result.outcomes.values.all { it is PartOutcome.Done })
        assertEquals("settings equal the state before", settingsBefore, SettingsTransfer(prefs).snapshot())
        assertEquals("the state in memory shows the counter at once", 12.5f, fuel.currentKm())
        eventually(GpsOdometer(1500, 4.25f)) { odometer.current() }
        eventually(12.5f) { prefs.fuelKmSinceFill.first() }
        eventually(GpsOdometer(1500, 4.25f)) { prefs.gpsOdometer.first() }
    }

    @Test
    fun anAnchorThatDoesNotMatchTheLogbookSkipsTheEstimateButTheTankCounterComes() = runBlocking {
        seed()
        fuel.restore(0f)
        val counters = """{"format":1,"fuel_km_since_fill":7.5,"logbook_gps_anchor_km":40000,"logbook_gps_km":3.0}"""
        val file = packageFile("anchor.zip", 1, "logbook.csv" to logbookExporterCsv(), "counters.json" to counters)
        wipe()
        eventually(null) { odometer.current() }

        val preview = importer.inspect(file, now, today)
        val result = importer.apply(preview, setOf(DataPart.LOGBOOK, DataPart.COUNTERS))

        assertEquals(PartOutcome.Done(count = 1, skipped = 1), result.outcomes.getValue(DataPart.COUNTERS))
        assertEquals(7.5f, fuel.currentKm())
        eventually(false) { odometer.current()?.anchorKm == 40_000 }
        eventually(7.5f) { prefs.fuelKmSinceFill.first() }
    }

    private suspend fun logbookExporterCsv(): String {
        val f = tempFile("logbook-for-anchor.csv")
        logbookExporter.saveCsv(Uri.fromFile(f))
        return f.readText()
    }
}
