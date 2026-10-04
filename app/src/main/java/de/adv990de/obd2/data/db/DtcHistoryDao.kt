package de.adv990de.obd2.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import de.adv990de.obd2.data.db.entity.DtcHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * The local fault history. An abstract class rather than an interface because [recordSeen] needs a
 * body to be one `@Transaction`.
 *
 * SQLite on API 26 is version 3.18; `INSERT … ON CONFLICT DO UPDATE` (UPSERT) arrived in 3.24, so a
 * sighting is `INSERT OR IGNORE` plus a guarded `UPDATE`.
 */
@Dao
abstract class DtcHistoryDao {

    /** Newest sighting first; equal times are ordered by code so the list never reshuffles. */
    @Query("SELECT * FROM dtc_history ORDER BY lastSeen DESC, code ASC")
    abstract fun observeAll(): Flow<List<DtcHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(entity: DtcHistoryEntity)

    /** Only moves `lastSeen` forward: an older reading never flips the flag or the time back. */
    @Query("UPDATE dtc_history SET lastSeen = :now, lastWasPending = :pending WHERE code = :code AND lastSeen < :now")
    protected abstract suspend fun touch(code: String, now: Long, pending: Boolean)

    /** A new code gets `firstSeen == lastSeen == now`; a known code keeps its `firstSeen` and moves `lastSeen`. */
    @Transaction
    open suspend fun recordSeen(code: String, now: Long, pending: Boolean) {
        insertIfAbsent(DtcHistoryEntity(code = code, firstSeen = now, lastSeen = now, lastWasPending = pending))
        touch(code, now, pending)
    }

    /** Callers pass at most 500 codes per call (the repository chunks): SQLite allows 999 bound variables on API 26. */
    @Query("DELETE FROM dtc_history WHERE code IN (:codes)")
    abstract suspend fun deleteByCodes(codes: List<String>)

    /** Keeps the [limit] most recently seen codes — a defective adapter inventing codes cannot grow the table without bound. */
    @Query("DELETE FROM dtc_history WHERE code NOT IN (SELECT code FROM dtc_history ORDER BY lastSeen DESC, code ASC LIMIT :limit)")
    abstract suspend fun pruneToNewest(limit: Int)
}
