package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.paging.PagingSource
import androidx.sqlite.db.SimpleSQLiteQuery
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val DELETE_CHUNK_SIZE = 50

@Singleton
class SessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase
) {

    val allSessions: Flow<List<SessionEntity>> = db.sessionDao().getAll()

    private val _activeSessionId = MutableStateFlow<Long?>(null)

    /**
     * The trip that is being recorded right now, or null. Held explicitly because `endTime == null`
     * does not mark it reliably (a killed process leaves the column null for ever). The history
     * protects exactly this trip: deleting it would break the running recording (its next write
     * violates the foreign key). Lives in memory only — after a restart no trip is active.
     */
    val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()

    /** Every trip id, observed — the history derives "is there anything to delete" from it. */
    val sessionIds: Flow<List<Long>> get() = db.sessionDao().observeIds()

    fun pagingSource(): PagingSource<Int, SessionEntity> = db.sessionDao().pagingSource()

    /**
     * Starts a trip. Atomic with respect to cancellation: a caller that is cancelled while the row is
     * being inserted never receives the id (`withContext` drops the result of a cancelled caller), so
     * the pipeline could never end the trip — an empty row, protected as "active" for ever. The insert
     * therefore runs in [NonCancellable]; if the caller was cancelled meanwhile, the empty trip is
     * discarded again before the cancellation continues. A caller that is already cancelled starts
     * no trip at all.
     */
    suspend fun startSession(vin: String? = null): Long {
        currentCoroutineContext().ensureActive()
        var created: Long? = null
        try {
            return withContext(Dispatchers.IO + NonCancellable) {
                val id = db.sessionDao().insert(SessionEntity(startTime = System.currentTimeMillis(), vehicleVin = vin))
                _activeSessionId.value = id
                created = id
                id
            }
        } catch (e: CancellationException) {
            created?.let { discardSession(it) }
            throw e
        }
    }

    /**
     * Marks the trip as finished. The pipeline calls this from a `finally` block, and a trip normally
     * ends BY cancellation (disconnect, slot change, app closing) — `withContext` throws at once in a
     * cancelled caller, so without [NonCancellable] the trip never got its `endTime`. The active id is
     * cleared first and outside the database call: a failing update must not leave a finished trip
     * protected against deletion.
     */
    suspend fun endSession(sessionId: Long) {
        clearActive(sessionId)
        withContext(Dispatchers.IO + NonCancellable) {
            val session = db.sessionDao().getById(sessionId) ?: return@withContext
            db.sessionDao().update(session.copy(endTime = System.currentTimeMillis()))
        }
    }

    /** Drops a trip (an empty one whose caller was cancelled, see [startSession]). Same cancellation rules as [endSession]. */
    suspend fun discardSession(sessionId: Long) {
        clearActive(sessionId)
        withContext(Dispatchers.IO + NonCancellable) { deleteRows(listOf(sessionId)) }
    }

    /**
     * The user deletes trips from the history. The trip that is being recorded is skipped whatever
     * the caller passes: deleting it would break the recording. Works in small chunks, so a long
     * delete lets the recorder's writes through in between and no statement exceeds SQLite's limit
     * of 999 bound variables (API 26).
     */
    suspend fun deleteSessions(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        val active = _activeSessionId.value
        deleteRows(ids.filter { it != active }.distinct())
    }

    private fun clearActive(sessionId: Long) {
        _activeSessionId.compareAndSet(sessionId, null)
    }

    /** Readings first: in production the foreign key cascades anyway, but a database without the
     *  `foreign_keys` pragma (tests) would otherwise keep orphaned readings. */
    private suspend fun deleteRows(ids: List<Long>) {
        ids.chunked(DELETE_CHUNK_SIZE).forEach { chunk ->
            db.sensorReadingDao().deleteBySessions(chunk)
            db.sessionDao().deleteByIds(chunk)
        }
    }

    suspend fun writeReadings(sessionId: Long, values: List<SensorValue>) = withContext(Dispatchers.IO) {
        val entities = values.map {
            SensorReadingEntity(
                sessionId = sessionId,
                timestamp = it.timestamp,
                pidId = it.pid.id,
                value = it.value
            )
        }
        db.sensorReadingDao().insertAll(entities)
    }

    /**
     * Retention: removes the trips that started more than [maxAgeMs] ago (default 90 days). Runs once
     * per app start (see [DataRetention]). Same rules as a delete from the history: in chunks (a long
     * cascade must not block the recorder, and SQLite allows only 999 bound variables on API 26), and
     * the trip that is being recorded is never touched.
     */
    suspend fun deleteOldSessions(
        maxAgeMs: Long = 90L * 24 * 60 * 60 * 1000
    ) = withContext(Dispatchers.IO) {
        val active = _activeSessionId.value
        val old = db.sessionDao().idsOlderThan(System.currentTimeMillis() - maxAgeMs)
        deleteRows(old.filter { it != active })
    }

    /**
     * Retention for the exported CSV files (see [exportToCsv]): removes the ones older than a day. Runs once per app
     * start from [DataRetention]; an export also cleans up before it writes. Returns how many files were deleted.
     */
    suspend fun purgeStaleExports(): Int = withContext(Dispatchers.IO) {
        deleteStaleExports(File(context.cacheDir, "exports"), System.currentTimeMillis())
    }

    /**
     * Exports a session to a CSV file and returns a shareable [Uri].
     *
     * Cursor-based: rows are written as they are read, and the query ([EXPORT_QUERY_SQL]) needs no sort, so memory
     * stays constant however long the trip is. Columns: `timestamp,pid,value,name` — the first three are the
     * original ones (a parser that reads by position keeps working), `name` is the stable English PID name: Excel
     * turns `0105` into the number 105 when the file is opened by double click, the name column still identifies the PID.
     *
     * Every export gets its own file (DAT-06: a fixed name was deleted under a share that was still reading it);
     * exports older than a day are removed first (DAT-07: they were never deleted). A failed export leaves no
     * half-written file behind.
     */
    suspend fun exportToCsv(sessionId: Long): Uri = withContext(Dispatchers.IO) {
        val exportDir = File(context.cacheDir, "exports")
        val now = System.currentTimeMillis()
        deleteStaleExports(exportDir, now)
        val file = createExportFile(exportDir, sessionId, now)

        try {
            val query = SimpleSQLiteQuery(EXPORT_QUERY_SQL, arrayOf(sessionId))
            db.sensorReadingDao().getBySessionCursor(query).use { cursor ->
                file.bufferedWriter().use { writer ->
                    writer.write(csvRow("timestamp", "pid", "value", "name"))
                    val colTimestamp = cursor.getColumnIndexOrThrow("timestamp")
                    val colPid       = cursor.getColumnIndexOrThrow("pidId")
                    val colValue     = cursor.getColumnIndexOrThrow("value")
                    while (cursor.moveToNext()) {
                        val pidId = cursor.getString(colPid).orEmpty()
                        writer.write(
                            csvRow(
                                cursor.getLong(colTimestamp).toString(),
                                pidId,
                                cursor.getFloat(colValue).toString(),
                                PidRegistry.all[pidId]?.name.orEmpty(),
                            )
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }

        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
