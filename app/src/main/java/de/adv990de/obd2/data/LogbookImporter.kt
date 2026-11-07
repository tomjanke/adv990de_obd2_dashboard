package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.logbook.ImportPlan
import de.adv990de.obd2.logbook.parseLogbookCsv
import de.adv990de.obd2.logbook.planImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import javax.inject.Inject

/** Die groesste Datei, die der Import liest (5 MiB): ein Logbuch mit tausenden Eintraegen hat einige hundert KiB, alles darueber ist die falsche Datei. */
internal const val MAX_IMPORT_BYTES = 5 * 1024 * 1024

private const val READ_BUFFER_BYTES = 8 * 1024

/** Die Datei ist groesser als [MAX_IMPORT_BYTES] — ein eigener Typ, damit die Oberflaeche den Grund nennen kann (sonst wuerde sie die Meldung raten). */
class FileTooLargeException(message: String) : IOException(message)

/**
 * Liest eine Logbuch-CSV (das **eigene** Format, s. [LogbookExporter]) und macht daraus in zwei Schritten einen Import: [read] liest und prueft die ganze Datei und
 * liefert den [ImportPlan] (die Vorschau, **es wird nichts geschrieben**), [apply] schreibt ihn erst auf die Bestaetigung des Nutzers, in einer Transaktion
 * (alles oder nichts, s. [VehicleLogRepository.importEntries]).
 *
 * Fehler: eine Quelle, die sich nicht oeffnen laesst, ist eine [IOException], eine Datei ueber [MAX_IMPORT_BYTES] eine [FileTooLargeException] (eine [IOException]; gelesen wird dann
 * hoechstens die Grenze plus ein Byte, nicht die ganze Datei); eine Datei, die kein Logbuch ist (Pflichtspalte fehlt, leer, offenes Anfuehrungszeichen, zu viele Zeilen), eine
 * [IllegalArgumentException]; alles Weitere geht unveraendert an den Aufrufer. Der Plan wird gegen den Bestand **zum Zeitpunkt des Lesens** gebildet: Eintraege, die der
 * Nutzer zwischen Vorschau und Bestaetigung selbst anlegt, sind nicht beruecksichtigt (ein Duplikat waere dann ein zweiter Eintrag).
 */
class LogbookImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: VehicleLogRepository,
) {

    suspend fun read(source: Uri, today: LocalDate): ImportPlan = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(source) ?: throw IOException("The chosen file could not be opened for reading")
        input.use { plan(it, today) }
    }

    /** Wie [read], aber aus einem schon geoeffneten Strom (ein Eintrag eines Pakets); der Aufrufer schliesst ihn. Die Grenze [MAX_IMPORT_BYTES] gilt auch hier. */
    suspend fun plan(stream: InputStream, today: LocalDate): ImportPlan = withContext(Dispatchers.IO) {
        val text = readText(stream)
        val existing = repository.all()
        planImport(parseLogbookCsv(text, today), existing)
    }

    /** Schreibt die neuen Eintraege des Plans; liefert ihre Zahl. Ein Fehler laesst das Logbuch unveraendert. */
    suspend fun apply(plan: ImportPlan): Int {
        if (plan.toInsert.isEmpty()) return 0
        repository.importEntries(plan.toInsert)
        return plan.toInsert.size
    }

    private suspend fun readText(stream: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = stream.read(buffer)
            if (read < 0) break
            bytes.write(buffer, 0, read)
            if (bytes.size() > MAX_IMPORT_BYTES) throw FileTooLargeException("The file is larger than ${MAX_IMPORT_BYTES / (1024 * 1024)} MiB")
        }
        return bytes.toString(Charsets.UTF_8.name())
    }
}
