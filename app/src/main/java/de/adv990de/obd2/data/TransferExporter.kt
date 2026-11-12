package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.BuildConfig
import de.adv990de.obd2.fuel.FuelRepository
import de.adv990de.obd2.logbook.logbookFileName
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.transfer.ExportFormat
import de.adv990de.obd2.transfer.PackageEntry
import de.adv990de.obd2.transfer.PackageFormat
import de.adv990de.obd2.transfer.PackageManifest
import de.adv990de.obd2.transfer.CounterValues
import de.adv990de.obd2.transfer.countersToJson
import de.adv990de.obd2.transfer.csvPartOf
import de.adv990de.obd2.transfer.dtcHistoryCsvHeader
import de.adv990de.obd2.transfer.dtcHistoryCsvLine
import de.adv990de.obd2.transfer.settingsToJson
import de.adv990de.obd2.transfer.toJson
import de.adv990de.obd2.transfer.writePackage
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Wie viele Eintraege je Art da sind (P6: beim Oeffnen der Seite und vor jedem Export frisch gelesen, nicht live). */
data class ExportCounts(
    val trips: Int,
    val logbook: Int,
    val appLog: Int,
    val dtcHistory: Int = 0,
    /** Die Messwerte (Datenzeilen) der Fahrten, die der Export schreibt; die Seite haelt sie gegen die Grenze des Imports (`tripExportSize`). */
    val tripRows: Long = 0,
) {
    /** `null` = keine Menge (Einstellungen und Zaehler sind immer vorhanden). */
    fun of(part: DataPart): Int? = when (part) {
        DataPart.TRIPS -> trips
        DataPart.LOGBOOK -> logbook
        DataPart.APP_LOG -> appLog
        DataPart.DTC_HISTORY -> dtcHistory
        DataPart.SETTINGS, DataPart.COUNTERS -> null
    }

    fun isEmpty(part: DataPart): Boolean = of(part) == 0
}

/** Was exportiert wird: die gewaehlten [parts] im [format] (CSV nur bei genau einer Art mit CSV, s. `csvPartOf`). */
data class ExportRequest(val parts: Set<DataPart>, val format: ExportFormat) {
    init {
        require(parts.isNotEmpty()) { "Nothing chosen to export" }
    }
}

/**
 * Der Export von „Daten sichern“: als Paket (ZIP) oder, fuer genau eine Art, als **dieselbe CSV-Datei wie bisher** — dafuer rufen die CSV-Wege die vorhandenen Exporter. Das Paket
 * schreibt `manifest.json` zuerst und danach jeden gewaehlten, **nicht leeren** Teil; das Manifest nennt die Mengen, die dieser Export wirklich schreibt (eine einzige Abfrage je Teil).
 *
 * Bekannte Grenze (P2): Fahrten, Logbucheintraege und Log-Zeilen, die der Nutzer vor weniger als 10 s geloescht hat (Rueckgaengig-Fenster), koennen noch in der Datei stehen.
 */
class TransferExporter @Inject constructor(
    @ApplicationContext context: Context,
    private val sessions: SessionRepository,
    private val logbook: VehicleLogRepository,
    private val logbookExporter: LogbookExporter,
    private val appLogs: AppLogRepository,
    private val appLogExporter: AppLogExporter,
    private val dtcHistory: DtcHistoryRepository,
    private val settings: SettingsTransfer,
    private val fuel: FuelRepository,
    private val odometer: LogbookOdometer,
) {
    private val files = CsvExportFiles(context)

    internal var appVersion: String = BuildConfig.VERSION_NAME
    internal var clockMs: () -> Long = System::currentTimeMillis

    suspend fun counts(): ExportCounts = ExportCounts(
        trips = sessions.exportableSessionIds().size,
        logbook = logbook.all().size,
        appLog = appLogs.logs.first().size,
        dtcHistory = dtcHistory.all().size,
        tripRows = sessions.exportableReadingCount(),
    )

    suspend fun share(request: ExportRequest): Uri {
        val csvPart = csvPartFor(request)
        if (csvPart != null) return when (csvPart) {
            DataPart.TRIPS -> sessions.exportSessionsToCsv(tripIdsOrNothing())
            DataPart.LOGBOOK -> { requireLogbookRows(); logbookExporter.exportToCsv() }
            else -> { requireAppLogRows(); appLogExporter.exportToCsv() }
        }
        val pkg = buildPackage(request)
        return files.shareStream(PackageFormat::fileName) { out -> writePackage(out, pkg.createdMs, pkg.entries) }
    }

    suspend fun save(target: Uri, request: ExportRequest) {
        val csvPart = csvPartFor(request)
        if (csvPart != null) {
            when (csvPart) {
                DataPart.TRIPS -> sessions.saveSessionsCsv(tripIdsOrNothing(), target)
                DataPart.LOGBOOK -> { requireLogbookRows(); logbookExporter.saveCsv(target) }
                else -> { requireAppLogRows(); appLogExporter.saveCsv(target) }
            }
            return
        }
        val pkg = buildPackage(request)
        files.saveStream(target, PackageFormat::fileName) { out -> writePackage(out, pkg.createdMs, pkg.entries) }
    }

    fun suggestedFileName(request: ExportRequest): String {
        val now = clockMs()
        return when (if (request.format == ExportFormat.CSV) csvPartOf(request.parts) else null) {
            DataPart.TRIPS -> exportAllFileName(now)
            DataPart.LOGBOOK -> logbookFileName(now)
            DataPart.APP_LOG -> appLogExportFileName(now)
            else -> PackageFormat.fileName(now)
        }
    }

    private fun csvPartFor(request: ExportRequest): DataPart? {
        if (request.format != ExportFormat.CSV) return null
        return csvPartOf(request.parts) ?: throw IllegalArgumentException("A csv file holds exactly one kind of data")
    }

    private suspend fun tripIdsOrNothing(): List<Long> = sessions.exportableSessionIds().ifEmpty { throw IllegalStateException(NOTHING_TO_EXPORT) }

    private suspend fun requireLogbookRows() = check(logbook.all().isNotEmpty()) { NOTHING_TO_EXPORT }

    private suspend fun requireAppLogRows() = check(appLogExporter.count() > 0) { NOTHING_TO_EXPORT }

    private class Built(val createdMs: Long, val entries: List<PackageEntry>)

    private suspend fun buildPackage(request: ExportRequest): Built {
        val chosen = request.parts.filter { it in DataPart.AVAILABLE }
        val body = mutableListOf<PackageEntry>()
        val written = linkedMapOf<DataPart, Int>()
        if (DataPart.TRIPS in chosen) {
            val ids = sessions.exportableSessionIds()
            if (ids.isNotEmpty()) {
                written[DataPart.TRIPS] = ids.size
                body += PackageEntry(DataPart.TRIPS.packageEntry) { sessions.writeTripsCsv(it, ids) }
                // trips_meta.csv (FIN und Fehlercodes je Fahrt) gehoert zu den Fahrten und steht nur da, wenn mindestens eine Fahrt eine Angabe hat.
                val withDetail = sessions.tripMeta(ids).filterValues { !it.isEmpty }.keys
                val metaIds = ids.filter { it in withDetail }
                if (metaIds.isNotEmpty()) body += PackageEntry(PackageFormat.TRIPS_META) { sessions.writeTripMetaCsv(it, metaIds) }
            }
        }
        if (DataPart.LOGBOOK in chosen) {
            val rows = logbook.all()
            if (rows.isNotEmpty()) {
                written[DataPart.LOGBOOK] = rows.size
                body += PackageEntry(DataPart.LOGBOOK.packageEntry) { logbookExporter.writeEntries(it, rows) }
            }
        }
        if (DataPart.SETTINGS in chosen) {
            val values = settings.snapshot()
            written[DataPart.SETTINGS] = values.size
            val json = settingsToJson(values)
            body += PackageEntry(DataPart.SETTINGS.packageEntry) { it.write(json) }
        }
        if (DataPart.COUNTERS in chosen) {
            // P10: der Stand im Speicher, nicht der DataStore (der hinkt dem wachsenden Zaehler bis 0,2 bzw. 0,5 km hinterher).
            val estimate = odometer.current()
            val values = CounterValues(fuel.currentKm(), estimate?.anchorKm, estimate?.gpsKm)
            written[DataPart.COUNTERS] = listOf(values.fuelKmSinceFill, values.gpsAnchorKm, values.gpsKm).count { it != null }
            val json = countersToJson(values)
            body += PackageEntry(DataPart.COUNTERS.packageEntry) { it.write(json) }
        }
        if (DataPart.DTC_HISTORY in chosen) {
            val rows = dtcHistory.all()
            if (rows.isNotEmpty()) {
                written[DataPart.DTC_HISTORY] = rows.size
                body += PackageEntry(DataPart.DTC_HISTORY.packageEntry) { out ->
                    out.write(dtcHistoryCsvHeader())
                    rows.forEach { out.write(dtcHistoryCsvLine(it)) }
                }
            }
        }
        if (DataPart.APP_LOG in chosen) {
            val count = appLogExporter.count()
            if (count > 0) {
                written[DataPart.APP_LOG] = count
                body += PackageEntry(DataPart.APP_LOG.packageEntry) { appLogExporter.writeCsv(it) }
            }
        }
        if (body.isEmpty()) throw IllegalStateException(NOTHING_TO_EXPORT)
        val createdMs = clockMs()
        val manifest = PackageManifest(PackageFormat.VERSION, PackageFormat.APP_ID, appVersion, createdMs, written)
        return Built(createdMs, listOf(PackageEntry(PackageFormat.MANIFEST) { it.write(manifest.toJson()) }) + body)
    }

    private companion object {
        const val NOTHING_TO_EXPORT = "Nothing to export"
    }
}
