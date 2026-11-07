package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.FileKind
import de.adv990de.obd2.transfer.NotAPackageException
import de.adv990de.obd2.transfer.PackageFormat
import de.adv990de.obd2.transfer.PackageReader
import de.adv990de.obd2.transfer.PartOutcome
import de.adv990de.obd2.transfer.PartPlan
import de.adv990de.obd2.transfer.TransferFileException
import de.adv990de.obd2.transfer.TransferPreview
import de.adv990de.obd2.transfer.TransferResult
import de.adv990de.obd2.transfer.detectFileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import javax.inject.Inject

/** Der Cache-Ordner der Kopie eines gewaehlten Pakets: **nicht** `exports/` (den gibt der FileProvider frei). */
internal const val TRANSFER_IMPORT_CACHE_DIR = "transfer_import"

private const val HEAD_BYTES = 8 * 1024
private const val COPY_BUFFER_BYTES = 8 * 1024
private const val STALE_COPY_MS = 60L * 60 * 1000

/**
 * Der Import von „Daten wiederherstellen“ in zwei Schritten: [inspect] erkennt die Datei (D7), liest sie zu Plaenen und schreibt **nichts**, [apply] schreibt die gewaehlten Teile
 * erst auf die Bestaetigung, je Teil in einer eigenen Transaktion (D9). Eine einzelne CSV geht den Weg von heute ([TripImporter.read], [LogbookImporter.read]); ein Paket wird in
 * den Cache kopiert (hoechstens [PackageFormat.MAX_TOTAL_BYTES]), gelesen und die Kopie **immer** wieder geloescht — die Plaene liegen danach komplett im Speicher.
 */
class TransferImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trips: TripImporter,
    private val logbook: LogbookImporter,
) {
    private val inspector = PackageInspector(trips, logbook)

    suspend fun inspect(source: Uri, nowMs: Long, today: LocalDate): TransferPreview = withContext(Dispatchers.IO) {
        val head = readHead(source)
        when (val kind = detectFileKind(head, head.size)) {
            FileKind.TRIPS_CSV -> single(kind, DataPart.TRIPS, PartPlan.Trips(trips.read(source, nowMs)))
            FileKind.LOGBOOK_CSV -> single(kind, DataPart.LOGBOOK, PartPlan.Logbook(logbook.read(source, today)))
            FileKind.APP_LOG_CSV -> throw TransferFileException(TransferFileException.Kind.APP_LOG_NOT_IMPORTABLE, "The app log can be exported, not imported")
            FileKind.UNKNOWN -> throw TransferFileException(TransferFileException.Kind.UNRECOGNIZED, "The file is neither a backup nor a csv file of this app")
            FileKind.PACKAGE -> inspectPackage(source, nowMs, today)
        }
    }

    private fun single(kind: FileKind, part: DataPart, plan: PartPlan) =
        TransferPreview(kind = kind, newerFormat = false, ignoredAppLog = false, parts = mapOf(part to plan))

    /** Die ersten Bytes per Schleife auf `read`: ein Strom darf weniger liefern, als verlangt (und `readNBytes` gibt es erst ab API 33). */
    private fun readHead(source: Uri): ByteArray {
        val input = open(source)
        input.use {
            val buffer = ByteArray(HEAD_BYTES)
            var filled = 0
            while (filled < buffer.size) {
                val n = it.read(buffer, filled, buffer.size - filled)
                if (n < 0) break
                filled += n
            }
            return buffer.copyOf(filled)
        }
    }

    private fun open(source: Uri): InputStream = context.contentResolver.openInputStream(source) ?: throw IOException("The chosen file could not be opened for reading")

    private suspend fun inspectPackage(source: Uri, nowMs: Long, today: LocalDate): TransferPreview {
        val dir = File(context.cacheDir, TRANSFER_IMPORT_CACHE_DIR).apply { mkdirs() }
        deleteStaleCopies(dir)
        val copy = File.createTempFile("package_", ".zip", dir)
        try {
            open(source).use { stream -> copyLimited(LimitedInputStream(stream, PackageFormat.MAX_TOTAL_BYTES), copy) }
            val reader = try {
                PackageReader.open(copy)
            } catch (e: NotAPackageException) {
                throw TransferFileException(TransferFileException.Kind.NOT_A_BACKUP, e.message ?: "not a backup")
            }
            return reader.use { inspector.inspect(it, nowMs, today) }
        } finally {
            copy.delete()
        }
    }

    private suspend fun copyLimited(input: InputStream, target: File) {
        target.outputStream().buffered().use { out ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }
    }

    /** Eine Kopie, die ein getoeteter Prozess hinterliess. */
    private fun deleteStaleCopies(dir: File) {
        val cutoff = System.currentTimeMillis() - STALE_COPY_MS
        dir.listFiles().orEmpty().filter { it.isFile && it.lastModified() < cutoff }.forEach { it.delete() }
    }

    /**
     * Schreibt die gewaehlten Teile in der Reihenfolge [DataPart.IMPORT_ORDER]. Ein Teil, der scheitert, ist [PartOutcome.Failed] und die uebrigen laufen weiter; die Abbruch-Ausnahme
     * beendet alles. Nicht lesbare Teile werden nie angewandt.
     */
    suspend fun apply(preview: TransferPreview, selected: Set<DataPart>): TransferResult {
        val outcomes = LinkedHashMap<DataPart, PartOutcome>()
        for (part in DataPart.IMPORT_ORDER) {
            if (part !in selected) continue
            val plan = preview.parts[part] ?: continue
            if (plan is PartPlan.Unreadable) continue
            outcomes[part] = try {
                PartOutcome.Done(
                    when (plan) {
                        is PartPlan.Trips -> trips.apply(plan.plan)
                        is PartPlan.Logbook -> logbook.apply(plan.plan)
                        is PartPlan.Unreadable -> 0
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PartOutcome.Failed(e)
            }
        }
        return TransferResult(outcomes)
    }
}
