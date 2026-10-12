package de.adv990de.obd2.data

import de.adv990de.obd2.data.db.DtcHistoryDao
import de.adv990de.obd2.obd.model.DtcEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Upper bound of the table: a defective adapter that invents codes cannot grow it without bound. */
private const val MAX_ENTRIES = 200

/** Codes per delete statement — SQLite allows only 999 bound variables on API 26. */
private const val DELETE_CHUNK_SIZE = 500

/**
 * The local fault history: which codes the vehicle reported and when — also after it stops reporting
 * them and while offline. Filled from the existing read-only mode 03/07 reads; the app never writes
 * to the vehicle, and deleting here only deletes the local record.
 */
@Singleton
class DtcHistoryRepository @Inject constructor(
    private val dao: DtcHistoryDao,
    private val appLogRepository: AppLogRepository,
) {

    /** Newest sighting first. Only the code is stored; the description is resolved from it for display. */
    val history: Flow<List<DtcHistoryEntry>> by lazy {
        dao.observeAll().map { rows ->
            rows.map { DtcHistoryEntry(it.code, it.firstSeen, it.lastSeen, it.lastWasPending) }
        }
    }

    /**
     * Records the codes of one successful read as seen at [now]. Best effort: a database error is
     * logged but never thrown, so neither the connection pipeline nor the DTC page can fail on the
     * history. A code read as confirmed AND pending counts as confirmed.
     */
    suspend fun record(entries: List<DtcEntry>, now: Long = System.currentTimeMillis()) {
        if (entries.isEmpty()) return
        try {
            entries.groupBy { it.code }.forEach { (code, sightings) ->
                dao.recordSeen(code, now, pending = sightings.all { it.isPending })
            }
            dao.pruneToNewest(MAX_ENTRIES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                appLogRepository.log(LogLevel.WARN, "DTC history: could not record the fault codes (${e.message})")
            } catch (inner: Exception) {
                if (inner is CancellationException) throw inner
            }
        }
    }

    /** Deletes the local records of [codes]; in chunks, duplicates ignored. */
    suspend fun delete(codes: Collection<String>) {
        codes.distinct().chunked(DELETE_CHUNK_SIZE).forEach { dao.deleteByCodes(it) }
    }
}
