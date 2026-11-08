package de.adv990de.obd2.data

import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.FileKind
import de.adv990de.obd2.transfer.PackageFormat
import de.adv990de.obd2.transfer.PackageReader
import de.adv990de.obd2.transfer.PartPlan
import de.adv990de.obd2.transfer.TransferFileException
import de.adv990de.obd2.transfer.TransferPreview
import de.adv990de.obd2.transfer.TripMetaSummary
import de.adv990de.obd2.transfer.PackageFormat.TRIPS_META
import de.adv990de.obd2.transfer.ParsedCounters
import de.adv990de.obd2.transfer.decideOdometer
import de.adv990de.obd2.transfer.parseCountersJson
import de.adv990de.obd2.transfer.parseSettingsJson
import de.adv990de.obd2.transfer.planSettings
import de.adv990de.obd2.transfer.parseTripMetaCsv
import de.adv990de.obd2.transfer.parseDtcHistoryCsv
import de.adv990de.obd2.transfer.planDtcHistoryMerge
import java.io.InputStream
import java.io.InputStreamReader
import de.adv990de.obd2.transfer.UnreadableReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.time.LocalDate

/**
 * Liest die Teile eines geoeffneten Pakets zu Plaenen. Jeder Teil ist unabhaengig (D9): ein Fehler macht nur ihn [PartPlan.Unreadable], die anderen bleiben. Unbekannte Eintraege und
 * Teile, die diese Version noch nicht kennt ([DataPart.AVAILABLE]), bleiben unbeachtet.
 */
internal class PackageInspector(
    private val trips: TripImporter,
    private val logbook: LogbookImporter,
    private val dtcHistory: DtcHistoryRepository,
    private val settings: SettingsTransfer,
    private val logEntries: VehicleLogRepository,
) {

    suspend fun inspect(reader: PackageReader, nowMs: Long, today: LocalDate): TransferPreview {
        val manifest = reader.manifest() ?: throw TransferFileException(TransferFileException.Kind.NOT_A_BACKUP, "No valid manifest: not a backup of this app")
        val parts = LinkedHashMap<DataPart, PartPlan>()
        for (part in DataPart.IMPORT_ORDER) {
            if (part !in DataPart.AVAILABLE || !reader.has(part.packageEntry)) continue
            parts[part] = readPart(reader, part, nowMs, today, parts[DataPart.LOGBOOK])
        }
        return TransferPreview(
            kind = FileKind.PACKAGE,
            newerFormat = manifest.format > PackageFormat.VERSION,
            ignoredAppLog = reader.has(DataPart.APP_LOG.packageEntry),
            parts = parts,
        )
    }

    /**
     * Die Fahrten und, wenn das Paket `trips_meta.csv` hat, deren FIN und Fehlercodes — je Fahrt ueber die Nummer aus `trips.csv` zugeordnet. Eine unlesbare Meta-Datei verwirft nur
     * sich selbst ([TripMetaSummary.unreadable]); die Fahrten bleiben planbar.
     */
    private suspend fun planTrips(reader: PackageReader, stream: InputStream, nowMs: Long): PartPlan.Trips {
        val plan = trips.plan(stream, nowMs)
        if (!reader.has(TRIPS_META)) return PartPlan.Trips(plan)
        val parsed = try {
            val metaStream = reader.openEntry(TRIPS_META) ?: throw IOException("entry $TRIPS_META vanished")
            metaStream.use { parseTripMetaCsv(InputStreamReader(it, Charsets.UTF_8)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return PartPlan.Trips(plan, TripMetaSummary(0, 0, 0, 0, unreadable = true))
        }
        val withMeta = plan.trips.map { it.withMeta(parsed.byTrip[it.sourceId]) }
        val accepted = plan.trips.map { it.sourceId }.toSet()
        return PartPlan.Trips(
            plan.copy(trips = withMeta),
            TripMetaSummary(
                withVin = withMeta.count { it.vin != null },
                withCodesRecorded = withMeta.count { it.dtcCodes != null },
                invalidVins = parsed.invalidVins,
                orphanRows = parsed.byTrip.keys.count { it !in accepted },
                unreadable = false,
            ),
        )
    }

    /**
     * Die Vorhersage zu den Zaehlern: der hoechste Logbuch-Stand nach dem Import ist der groessere aus dem gespeicherten und dem, den das Logbuch der Datei bringt (nur wenn es lesbar ist).
     * Endgueltig entscheidet [TransferImporter.apply] gegen den dann gespeicherten Stand.
     */
    private suspend fun planCounters(parsed: ParsedCounters, logbookPlan: PartPlan?): PartPlan.Counters {
        val fromFile = (logbookPlan as? PartPlan.Logbook)?.plan?.toInsert?.maxOfOrNull { it.odometerKm }
        val predicted = listOfNotNull(logEntries.maxOdometerKm.first(), fromFile).maxOrNull()
        return PartPlan.Counters(parsed.values, parsed.rejectedKeys, decideOdometer(parsed.values, predicted), predicted)
    }

    private suspend fun readPart(reader: PackageReader, part: DataPart, nowMs: Long, today: LocalDate, logbookPlan: PartPlan?): PartPlan = try {
        val stream = reader.openEntry(part.packageEntry) ?: throw IOException("entry ${part.packageEntry} vanished")
        stream.use {
            when (part) {
                DataPart.TRIPS -> planTrips(reader, it, nowMs)
                DataPart.LOGBOOK -> PartPlan.Logbook(logbook.plan(it, today))
                DataPart.DTC_HISTORY -> PartPlan.DtcHistory(planDtcHistoryMerge(dtcHistory.all(), parseDtcHistoryCsv(InputStreamReader(it, Charsets.UTF_8), nowMs)))
                DataPart.SETTINGS -> PartPlan.Settings(planSettings(parseSettingsJson(it.reader(Charsets.UTF_8).readText()), settings.snapshot()))
                DataPart.COUNTERS -> planCounters(parseCountersJson(it.reader(Charsets.UTF_8).readText()), logbookPlan)
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
