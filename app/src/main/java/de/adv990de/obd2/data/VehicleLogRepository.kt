package de.adv990de.obd2.data

import de.adv990de.obd2.data.db.VehicleLogDao
import de.adv990de.obd2.data.db.entity.VehicleLogEntity
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Ids je Loeschaufruf — SQLite erlaubt auf API 26 nur 999 gebundene Variablen (wie bei `SessionRepository`). */
private const val DELETE_CHUNK_SIZE = 50

/**
 * Das Logbuch: Tanken, Oel, Kuehlfluessigkeit, Kettenpflege, Service, Reifen- und Kettenkit-Wechsel mit Datum und Kilometerstand. Rein lokal; das Fahrzeug
 * wird nie beschrieben. Die Aufbewahrung ([DataRetention]) fasst die Tabelle nicht an — Wartungsdaten sind langlebig.
 *
 * Eine Zeile mit einer Art, die diese Version nicht kennt (aus einer spaeteren Version), wird **uebersprungen**, nicht zum Absturz: das Logbuch ist Beiwerk
 * der Anzeige. Geschrieben werden Fehler nicht geschluckt: wer speichert oder importiert, muss erfahren, dass es misslang.
 */
@Singleton
class VehicleLogRepository @Inject constructor(private val dao: VehicleLogDao) {

    /** Neueste zuerst (Datum, Stand, Anlage); Zeilen unbekannter Art fehlen. */
    val entries: Flow<List<LogEntry>> by lazy { dao.observeAll().map { rows -> rows.mapNotNull { it.toEntry() } } }

    /** Der hoechste Kilometerstand im Logbuch, `null` ohne Eintrag; meldet nur Aenderungen (Zeilen unbekannter Art zaehlen nicht). */
    val maxOdometerKm: Flow<Int?> by lazy { entries.map { list -> list.maxOfOrNull { it.odometerKm } }.distinctUntilChanged() }

    /** Alle Eintraege, aelteste zuerst — fuer den Export. */
    suspend fun all(): List<LogEntry> = dao.getAll().mapNotNull { it.toEntry() }

    suspend fun get(id: Long): LogEntry? = dao.getById(id)?.toEntry()

    /** Legt an (Id 0) oder ersetzt (vorhandene Id); liefert die Id. */
    suspend fun save(entry: LogEntry): Long = dao.upsert(entry.toRow())

    /** Loescht die Eintraege; in Bloecken, Doppelte ignoriert. */
    suspend fun delete(ids: Collection<Long>) {
        ids.distinct().chunked(DELETE_CHUNK_SIZE).forEach { dao.deleteByIds(it) }
    }

    /** Schreibt alle Eintraege als **neue** Zeilen in einer Transaktion (alles oder nichts); Ids der Eintraege zaehlen nicht. */
    suspend fun importEntries(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        dao.insertAll(entries.map { it.toRow().copy(id = 0) })
    }

    /** Der Eintrag mit dem hoechsten Kilometerstand — der "letzte Stand" im Formular. */
    suspend fun latestByOdometer(): LogEntry? = dao.latestByOdometer()?.toEntry()
}

private fun LogEntry.toRow() = VehicleLogEntity(
    id = id,
    type = type.name,
    epochDay = date.toEpochDay(),
    odometerKm = odometerKm,
    amountMl = amountMl,
    fullTank = fullTank,
    costCents = costCents,
    note = note,
    brand = brand,
)

private fun VehicleLogEntity.toEntry(): LogEntry? {
    val logType = LogType.entries.firstOrNull { it.name == type } ?: return null
    return LogEntry(
        id = id,
        type = logType,
        date = LocalDate.ofEpochDay(epochDay),
        odometerKm = odometerKm,
        amountMl = amountMl,
        fullTank = fullTank,
        costCents = costCents,
        note = note,
        brand = brand,
    )
}
