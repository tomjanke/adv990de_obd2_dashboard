package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * [LogbookExporter] und [LogbookImporter] gegen echte (In-Memory-)Datenbanken und den echten FileProvider: der ganze Weg Eintrag -> Datenbank -> CSV ->
 * zweite, leere Datenbank -> Import -> Eintrag, **ohne** die Systemdialoge (die lassen sich im Test nicht bedienen; eine Datei-`Uri` steht fuer das Ergebnis des
 * Dateidialogs bzw. der Dateiauswahl). Das belegt die Wiederherstellung des Logbuchs nach dem Loeschen der App-Daten — nicht den echten Weg durch die Dialoge.
 */
@RunWith(AndroidJUnit4::class)
class LogbookExporterDeviceTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var freshDb: AppDatabase
    private lateinit var repository: VehicleLogRepository
    private lateinit var freshRepository: VehicleLogRepository
    private val exportDir get() = File(ctx.cacheDir, EXPORT_CACHE_DIR)
    private val today = LocalDate.of(2026, 10, 25)

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        freshDb = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = VehicleLogRepository(db.vehicleLogDao())
        freshRepository = VehicleLogRepository(freshDb.vehicleLogDao())
        exportDir.deleteRecursively()
    }

    @After
    fun teardown() {
        db.close()
        freshDb.close()
        exportDir.deleteRecursively()
    }

    private fun day(d: Int) = LocalDate.of(2026, 10, d)

    /** Alle neun Arten, bewusst nicht in Datumsreihenfolge angelegt, mit allem, was in einem Feld stehen kann. */
    private fun sample() = listOf(
        LogEntry(type = LogType.FUEL, date = day(2), odometerKm = 40_362, amountMl = 14_370, fullTank = true, costCents = 2_743, note = "Shell, \"Autobahn\"\nzweite Zeile", brand = null),
        LogEntry(type = LogType.FUEL, date = day(1), odometerKm = 40_100, amountMl = 5_000, fullTank = false, costCents = null, note = null, brand = null),
        LogEntry(type = LogType.OIL, date = day(1), odometerKm = 40_100, amountMl = 150, fullTank = null, costCents = null, note = null, brand = null),
        LogEntry(type = LogType.COOLANT, date = day(1), odometerKm = 40_100, amountMl = 50, fullTank = null, costCents = null, note = "bis Max", brand = null),
        LogEntry(type = LogType.CHAIN_SMALL, date = day(1), odometerKm = 40_000, amountMl = null, fullTank = null, costCents = null, note = null, brand = null),
        LogEntry(type = LogType.CHAIN_LARGE, date = day(1), odometerKm = 39_900, amountMl = null, fullTank = null, costCents = null, note = null, brand = null),
        LogEntry(type = LogType.SERVICE_SMALL, date = day(1), odometerKm = 39_000, amountMl = null, fullTank = null, costCents = null, note = null, brand = null),
        LogEntry(type = LogType.SERVICE_LARGE, date = day(1), odometerKm = 38_000, amountMl = null, fullTank = null, costCents = null, note = "Ventile", brand = null),
        LogEntry(type = LogType.TIRE_CHANGE, date = day(1), odometerKm = 37_000, amountMl = null, fullTank = null, costCents = null, note = null, brand = "Michelin Anakee Adventure 150/70-18"),
        LogEntry(type = LogType.CHAIN_KIT_CHANGE, date = day(1), odometerKm = 36_000, amountMl = null, fullTank = null, costCents = null, note = null, brand = null),
    )

    private fun withoutIds(entries: List<LogEntry>) = entries.map { it.copy(id = 0) }

    private fun fileUri(name: String): Uri = Uri.fromFile(File(ctx.cacheDir, name).also { it.delete() })

    @Test
    fun exportToCsv_writesTheRealEntriesOldestFirst_andTheFileProviderUriReadsThemBack() = runBlocking {
        sample().forEach { repository.save(it) }

        val uri = LogbookExporter(ctx, repository).exportToCsv()

        val lines = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }.let { it.trimEnd('\n').split("\n") }
        assertEquals("date,type,odometer_km,amount_ml,full_tank,cost_eur,note,brand", lines.first())
        // die Notiz mit Zeilenumbruch ist EIN Datensatz, aber zwei Textzeilen: 10 Eintraege + Kopf + 1 Umbruch
        assertEquals(12, lines.size)
        assertTrue("aeltester zuerst: ${lines[1]}", lines[1].startsWith("2026-10-01,CHAIN_KIT_CHANGE,36000"))
        assertTrue("juengster zuletzt: ${lines.takeLast(2)}", lines.last().startsWith("zweite Zeile\""))
        assertTrue(exportDir.listFiles().orEmpty().single().name.startsWith("logbook_"))
    }

    @Test
    fun theWholeWay_saveToAFile_thenImportIntoAnEmptyDatabase_restoresEveryEntryOfEveryType() = runBlocking {
        sample().forEach { repository.save(it) }
        val target = fileUri("restore.csv")
        LogbookExporter(ctx, repository).saveCsv(target)
        assertTrue("der Cache ist aufgeraeumt", exportDir.listFiles().orEmpty().isEmpty())

        val importer = LogbookImporter(ctx, freshRepository)
        val plan = importer.read(target, today)

        assertEquals("nichts abgelehnt: ${plan.rejected}", 0, plan.rejected.size)
        assertEquals(0, plan.duplicates)
        assertEquals(sample().size, plan.toInsert.size)
        assertEquals("die Vorschau schreibt nichts", 0, freshRepository.all().size)

        assertEquals(sample().size, importer.apply(plan))

        val restored = freshRepository.all()
        assertEquals("jeder Eintrag, jede Art, jedes Feld", withoutIds(repository.all()), withoutIds(restored))
        assertEquals("die Reihenfolge der Datei ist aelteste zuerst, die der Datenbank nach Datum und Stand", restored.sortedWith(compareBy({ it.date }, { it.odometerKm })), restored)
    }

    @Test
    fun importingTheSameFileTwice_changesNothingTheSecondTime() = runBlocking {
        sample().forEach { repository.save(it) }
        val target = fileUri("twice.csv")
        LogbookExporter(ctx, repository).saveCsv(target)
        val importer = LogbookImporter(ctx, freshRepository)
        importer.apply(importer.read(target, today))

        val second = importer.read(target, today)

        assertEquals(0, second.toInsert.size)
        assertEquals(sample().size, second.duplicates)
        assertEquals(sample().size, freshRepository.all().size)
    }

    @Test
    fun anExportOfAnEmptyLogbook_isJustTheHeader_andImportsAsNothing() = runBlocking {
        val target = fileUri("empty.csv")
        LogbookExporter(ctx, repository).saveCsv(target)

        val plan = LogbookImporter(ctx, freshRepository).read(target, today)

        assertEquals(0, plan.toInsert.size)
        assertEquals(0, plan.rejected.size)
    }
}
