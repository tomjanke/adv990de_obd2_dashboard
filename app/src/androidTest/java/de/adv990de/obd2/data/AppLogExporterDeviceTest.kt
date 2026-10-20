package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.entity.AppLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [AppLogExporter] gegen eine echte (In-Memory-)Room-Datenbank und den echten FileProvider: was die JVM-Tests mit gestelltem Cursor nicht zeigen —
 * die Abfrage liest wirklich die Zeilen nach der Id in der Reihenfolge der Aufzeichnung, ohne Sortierlauf, und die Datei laesst sich ueber die
 * [Uri] des FileProviders lesen. Die Systemdialoge (Dateidialog, Teilen-Menue) lassen sich im Test nicht bedienen; eine Datei-Uri steht fuer
 * das Ergebnis des Dateidialogs.
 */
@RunWith(AndroidJUnit4::class)
class AppLogExporterDeviceTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var exporter: AppLogExporter
    private val exportDir get() = File(ctx.cacheDir, EXPORT_CACHE_DIR)

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        exporter = AppLogExporter(ctx, db.appLogDao())
        exportDir.deleteRecursively()
    }

    @After
    fun teardown() {
        db.close()
        exportDir.deleteRecursively()
    }

    private fun insert(timestamp: Long, level: String, message: String): Long = runBlocking {
        db.appLogDao().insert(AppLogEntity(timestamp = timestamp, level = level, message = message))
        db.openHelper.readableDatabase.query("SELECT MAX(id) FROM app_log").use { it.moveToFirst(); it.getLong(0) }
    }

    @Test
    fun exportQuery_needsNoSortStep_soMemoryStaysConstant() {
        // ORDER BY id ist die Reihenfolge des Primaerschluessels (rowid): SQLite liest ab der Id aufsteigend, ohne temporaeren Sortierbaum.
        val plan = db.openHelper.readableDatabase
            .query("EXPLAIN QUERY PLAN $APP_LOG_EXPORT_QUERY_SQL", arrayOf<Any>(0L))
            .use { c ->
                val detail = c.getColumnIndexOrThrow("detail")
                generateSequence { if (c.moveToNext()) c.getString(detail) else null }.toList()
            }
        val text = plan.joinToString(" | ")

        assertEquals("kein Sortierlauf: $text", false, text.contains("TEMP B-TREE", ignoreCase = true))
        assertEquals("sucht ueber den Primaerschluessel: $text", true, text.contains("PRIMARY KEY", ignoreCase = true))
    }

    @Test
    fun exportToCsv_writesTheRealRowsOldestFirst_andTheFileProviderUriReadsThemBack() = runBlocking<Unit> {
        // Absichtlich NICHT in Zeitreihenfolge eingefuegt: gilt ist die Reihenfolge der Aufzeichnung (id), nicht die der Zeitstempel.
        insert(2_000L, "ERROR", "Connection error: read failed, socket might closed or timeout, read ret: -1")
        insert(1_000L, "INFO", "Connecting to MWT_OBD2…")
        insert(3_000L, "WARN", "say \"hi\"\nsecond line")

        val uri: Uri = exporter.exportToCsv()

        val expected = "timestamp,level,message\n" +
            "2000,ERROR,\"Connection error: read failed, socket might closed or timeout, read ret: -1\"\n" +
            "1000,INFO,Connecting to MWT_OBD2…\n" +
            "3000,WARN,\"say \"\"hi\"\"\nsecond line\"\n"
        val file = exportDir.listFiles()!!.single()
        assertTrue("name: ${file.name}", file.name.startsWith("applog_") && file.name.endsWith(".csv"))
        assertEquals(expected, file.readText())
        assertEquals("ueber den FileProvider der App", "${ctx.packageName}.fileprovider", uri.authority)
        assertEquals("der Empfaenger liest dieselben Bytes", expected, ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    @Test
    fun exportToCsv_takesOnlyTheRowsAfterTheGivenId() = runBlocking<Unit> {
        insert(1L, "INFO", "one")
        val hidden = insert(2L, "INFO", "two")
        insert(3L, "INFO", "three")

        exporter.exportToCsv(afterId = hidden)

        assertEquals("timestamp,level,message\n3,INFO,three\n", exportDir.listFiles()!!.single().readText())
    }

    @Test
    fun exportToCsv_ofAnEmptyLog_writesOnlyTheHeader() = runBlocking<Unit> {
        exporter.exportToCsv()

        assertEquals("timestamp,level,message\n", exportDir.listFiles()!!.single().readText())
    }

    @Test
    fun saveCsv_writesTheSameCsvIntoTheFileTheUserChose_andLeavesNothingInTheCache() = runBlocking<Unit> {
        val target = File(ctx.cacheDir, "saved-by-the-user.csv").also { it.delete() }
        insert(100L, "INFO", "a")
        insert(200L, "WARN", "b,c")

        exporter.saveCsv(Uri.fromFile(target))

        assertEquals("timestamp,level,message\n100,INFO,a\n200,WARN,\"b,c\"\n", target.readText())
        assertEquals("die Zwischendatei im Cache ist weg", emptyList<String>(), exportDir.listFiles().orEmpty().map { it.name })
        target.delete()
    }
}
