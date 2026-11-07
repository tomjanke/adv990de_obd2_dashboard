package de.adv990de.obd2.data

import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.FileKind
import de.adv990de.obd2.transfer.PackageFormat
import de.adv990de.obd2.transfer.PackageReader
import de.adv990de.obd2.transfer.PartPlan
import de.adv990de.obd2.transfer.TransferFileException
import de.adv990de.obd2.transfer.TransferPreview
import de.adv990de.obd2.transfer.UnreadableReason
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.LocalDate

/**
 * Liest die Teile eines geoeffneten Pakets zu Plaenen. Jeder Teil ist unabhaengig (D9): ein Fehler macht nur ihn [PartPlan.Unreadable], die anderen bleiben. Unbekannte Eintraege und
 * Teile, die diese Version noch nicht kennt ([DataPart.AVAILABLE]), bleiben unbeachtet.
 */
internal class PackageInspector(private val trips: TripImporter, private val logbook: LogbookImporter) {

    suspend fun inspect(reader: PackageReader, nowMs: Long, today: LocalDate): TransferPreview {
        val manifest = reader.manifest() ?: throw TransferFileException(TransferFileException.Kind.NOT_A_BACKUP, "No valid manifest: not a backup of this app")
        val parts = LinkedHashMap<DataPart, PartPlan>()
        for (part in DataPart.IMPORT_ORDER) {
            if (part !in DataPart.AVAILABLE || !reader.has(part.packageEntry)) continue
            parts[part] = readPart(reader, part, nowMs, today)
        }
        return TransferPreview(
            kind = FileKind.PACKAGE,
            newerFormat = manifest.format > PackageFormat.VERSION,
            ignoredAppLog = reader.has(DataPart.APP_LOG.packageEntry),
            parts = parts,
        )
    }

    private suspend fun readPart(reader: PackageReader, part: DataPart, nowMs: Long, today: LocalDate): PartPlan = try {
        val stream = reader.openEntry(part.packageEntry) ?: throw IOException("entry ${part.packageEntry} vanished")
        stream.use {
            when (part) {
                DataPart.TRIPS -> PartPlan.Trips(trips.plan(it, nowMs))
                DataPart.LOGBOOK -> PartPlan.Logbook(logbook.plan(it, today))
                else -> throw IllegalStateException("$part is not read in this version")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: FileTooLargeException) {
        PartPlan.Unreadable(UnreadableReason.TOO_LARGE)
    } catch (e: IllegalArgumentException) {
        PartPlan.Unreadable(UnreadableReason.FORMAT)
    } catch (e: IOException) {
        PartPlan.Unreadable(UnreadableReason.UNREADABLE)
    } catch (e: SecurityException) {
        PartPlan.Unreadable(UnreadableReason.UNREADABLE)
    } catch (e: Exception) {
        // A parser that fails in an unexpected way must not take the other parts with it (D9). Errors (OutOfMemoryError) go through on purpose.
        PartPlan.Unreadable(UnreadableReason.UNREADABLE)
    }
}
