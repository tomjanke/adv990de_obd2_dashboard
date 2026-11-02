package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Writer

/**
 * Der gemeinsame Rahmen der CSV-Exporte (Fahrten, App-Log, Logbuch): [SessionRepository], [AppLogExporter] und [LogbookExporter] liefern nur, WAS in die Datei kommt
 * (der Name und die Zeilen), dieser Baustein schreibt sie sicher und gibt sie heraus.
 *
 * Die Regeln stehen hier einmal: jeder Export in einer eigenen neuen Datei im Cache ([createExportFile]), Exporte aelter als einen Tag werden vorab entfernt
 * ([deleteStaleExports]), ein Fehler — auch ein Abbruch — hinterlaesst keine halbe Datei, und erst bei Erfolg geht die Datei hinaus: als teilbare [Uri] des FileProviders
 * ([share]) oder in die Datei, die der Nutzer mit dem Dateidialog gewaehlt hat ([save], ueber [copyToTarget]; die Cache-Datei wird danach immer entfernt).
 * `write` darf abbrechen (`ensureActive` alle [CANCELLATION_CHECK_ROWS] Zeilen) und werfen.
 */
internal class CsvExportFiles(private val context: Context) {

    /** Schreibt die Datei in den Cache und liefert eine [Uri], die das Teilen-Menue weiterreichen kann (der FileProvider gibt Leserecht). */
    suspend fun share(fileName: (nowMillis: Long) -> String, write: suspend (Writer) -> Unit): Uri = withContext(Dispatchers.IO) {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", writeToCache(fileName, write))
    }

    /**
     * Schreibt dieselbe Datei in [target] (von `CreateDocument`): erst in den Cache und erst bei Erfolg kopiert, ein Fehler beim Schreiben fasst das Ziel nie an; ein Ziel,
     * das sich nicht zum Schreiben oeffnen laesst, ist eine [java.io.IOException].
     */
    suspend fun save(target: Uri, fileName: (nowMillis: Long) -> String, write: suspend (Writer) -> Unit) = withContext(Dispatchers.IO) {
        val file = writeToCache(fileName, write)
        try {
            copyToTarget(context.contentResolver, file, target)
        } finally {
            file.delete()
        }
    }

    private suspend fun writeToCache(fileName: (Long) -> String, write: suspend (Writer) -> Unit): File {
        val exportDir = File(context.cacheDir, EXPORT_CACHE_DIR)
        val now = System.currentTimeMillis()
        deleteStaleExports(exportDir, now)
        val file = createExportFile(exportDir, fileName(now))
        try {
            file.bufferedWriter().use { write(it) }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return file
    }
}
