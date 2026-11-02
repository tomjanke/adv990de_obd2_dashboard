package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SimpleSQLiteQuery
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.data.db.AppLogDao
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Writer
import javax.inject.Inject

/**
 * Exportiert den App-Log als CSV (`timestamp,level,message`, s. [APP_LOG_EXPORT_COLUMNS]), analog zum Export der Fahrten: entweder als teilbare
 * [Uri] ([exportToCsv], fuer das Teilen-Menue) oder in eine Datei, die der Nutzer mit dem Dateidialog gewaehlt hat ([saveCsv]).
 *
 * Nicht im [AppLogRepository]: dessen Konstruktor kennt nur das DAO (viele Tests bauen ihn so), und der Export braucht Cache-Ordner und
 * `ContentResolver`. Wie beim Export der Fahrten: Zeile fuer Zeile per Cursor (konstanter Speicher), jeder Export in einer eigenen neuen Datei,
 * Exporte aelter als einen Tag werden vorab entfernt ([deleteStaleExports]), ein Fehler hinterlaesst keine halbe Datei, und der Aufrufer darf
 * abbrechen.
 *
 * Es werden die Zeilen **nach** `afterId` exportiert — das ist, was der Nutzer noch sieht (Zeilen im Rueckgaengig-Fenster nach „Alle loeschen“
 * sind ausgeblendet, aber noch in der Datenbank). Mit `0` ist es der ganze Bestand. Es gibt keine obere Grenze: auch Zeilen, die waehrend des
 * Exports entstehen, koennen noch hineinkommen.
 */
class AppLogExporter @Inject constructor(
    @ApplicationContext context: Context,
    private val dao: AppLogDao,
) {
    private val files = CsvExportFiles(context)

    /** Writes the log into a new file in the cache and returns a [Uri] the share chooser can hand on (the file provider grants read access). */
    suspend fun exportToCsv(afterId: Long = 0L): Uri = files.share(::appLogExportFileName) { writeRows(it, afterId) }

    /**
     * Writes the same CSV into the file the user chose with the system file dialog ([target], from `CreateDocument`). The CSV goes to the cache
     * first and is copied only when it is complete ([copyToTarget]); the cache file is removed afterwards, whatever happened.
     */
    suspend fun saveCsv(target: Uri, afterId: Long = 0L) = files.save(target, ::appLogExportFileName) { writeRows(it, afterId) }

    private suspend fun writeRows(writer: Writer, afterId: Long) {
        writer.write(csvRow(*APP_LOG_EXPORT_COLUMNS))
        currentCoroutineContext().ensureActive()
        dao.getExportCursor(SimpleSQLiteQuery(APP_LOG_EXPORT_QUERY_SQL, arrayOf(afterId))).use { cursor ->
            val colTimestamp = cursor.getColumnIndexOrThrow("timestamp")
            val colLevel = cursor.getColumnIndexOrThrow("level")
            val colMessage = cursor.getColumnIndexOrThrow("message")
            var written = 0
            while (cursor.moveToNext()) {
                writer.write(
                    csvRow(
                        cursor.getLong(colTimestamp).toString(),
                        cursor.getString(colLevel).orEmpty(),
                        cursor.getString(colMessage).orEmpty(),
                    ),
                )
                if (++written % CANCELLATION_CHECK_ROWS == 0) currentCoroutineContext().ensureActive()
            }
        }
    }
}
