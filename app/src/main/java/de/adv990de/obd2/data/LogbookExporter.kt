package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.logbook.logbookCsvHeader
import de.adv990de.obd2.logbook.logbookCsvLine
import de.adv990de.obd2.logbook.logbookFileName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Writer
import javax.inject.Inject

/**
 * Exportiert das Logbuch als CSV (`date,type,odometer_km,amount_ml,full_tank,cost_eur,note,brand`, s. `LOGBOOK_COLUMNS`), **aelteste Eintraege zuerst**: entweder als
 * teilbare [Uri] ([exportToCsv], fuer das Teilen-Menue) oder in eine Datei, die der Nutzer mit dem Dateidialog gewaehlt hat ([saveCsv]). Der Export ist die
 * einzige Sicherung des Logbuchs (`allowBackup="false"`: Loeschen der App-Daten oder Deinstallieren vernichtet es) — [LogbookImporter] liest die Datei wieder ein.
 *
 * Wie beim Export der Fahrten und des App-Logs: erst in den Cache schreiben und erst bei Erfolg ins Ziel kopieren ([copyToTarget]; ein Lesefehler fasst das Ziel
 * nie an), jeder Export in einer eigenen neuen Datei, Exporte aelter als einen Tag werden vorab entfernt ([deleteStaleExports]), ein Fehler hinterlaesst keine
 * halbe Datei, und der Aufrufer darf abbrechen. Anders als das Log wird das Logbuch nicht per Cursor gelesen, sondern als Liste: es hat hoechstens einige tausend
 * Eintraege (der Altbestand des Nutzers 102).
 *
 * [excluding] sind Ids, die die Oberflaeche ausblendet (Eintraege im Rueckgaengig-Fenster nach dem Loeschen): die Sicherung soll zeigen, was der Nutzer sieht.
 */
class LogbookExporter @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: VehicleLogRepository,
) {
    private val files = CsvExportFiles(context)

    /** Writes the logbook into a new file in the cache and returns a [Uri] the share chooser can hand on (the file provider grants read access). */
    suspend fun exportToCsv(excluding: Set<Long> = emptySet()): Uri = files.share(::logbookFileName) { writeRows(it, excluding) }

    /**
     * Writes the same CSV into the file the user chose with the system file dialog ([target], from `CreateDocument`). The CSV goes to the cache
     * first and is copied only when it is complete ([copyToTarget]); the cache file is removed afterwards, whatever happened.
     */
    suspend fun saveCsv(target: Uri, excluding: Set<Long> = emptySet()) = files.save(target, ::logbookFileName) { writeRows(it, excluding) }

    private suspend fun writeRows(writer: Writer, excluding: Set<Long>) {
        val entries = repository.all().filter { it.id !in excluding }
        currentCoroutineContext().ensureActive()
        writer.write(logbookCsvHeader())
        entries.forEachIndexed { index, entry ->
            writer.write(logbookCsvLine(entry))
            if ((index + 1) % CANCELLATION_CHECK_ROWS == 0) currentCoroutineContext().ensureActive()
        }
    }
}
