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
import de.adv990de.obd2.obd.model.SensorValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

    suspend fun startSession(vin: String? = null): Long = withContext(Dispatchers.IO) {
        val id = db.sessionDao().insert(SessionEntity(startTime = System.currentTimeMillis(), vehicleVin = vin))
        _activeSessionId.value = id
        id
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

    /** The pipeline drops a trip that recorded no DTC ("only trips with DTCs"). Same cancellation rules as [endSession]. */
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

    suspend fun deleteOldSessions(
        maxAgeMs: Long = 90L * 24 * 60 * 60 * 1000
    ) = withContext(Dispatchers.IO) {
        db.sessionDao().deleteOlderThan(System.currentTimeMillis() - maxAgeMs)
    }

    /**
     * Exports a session to a CSV file and returns a shareable [Uri].
     * Uses a cursor-based approach so memory usage stays constant regardless
     * of session length — rows are written as they are read from the database.
     */
    suspend fun exportToCsv(sessionId: Long): Uri = withContext(Dispatchers.IO) {
        val exportDir = File(context.cacheDir, "exports").also { it.mkdirs() }
        val file = File(exportDir, "session_$sessionId.csv")

        file.delete()

        val query = SimpleSQLiteQuery(
            "SELECT timestamp, pidId, value FROM sensor_readings WHERE sessionId = ? ORDER BY timestamp ASC",
            arrayOf(sessionId)
        )
        db.sensorReadingDao().getBySessionCursor(query).use { cursor ->
            file.bufferedWriter().use { writer ->
                writer.write("timestamp,pid,value\n")
                val colTimestamp = cursor.getColumnIndexOrThrow("timestamp")
                val colPid       = cursor.getColumnIndexOrThrow("pidId")
                val colValue     = cursor.getColumnIndexOrThrow("value")
                while (cursor.moveToNext()) {
                    writer.write("${cursor.getLong(colTimestamp)},${cursor.getString(colPid).orEmpty()},${cursor.getFloat(colValue)}\n")
                }
            }
        }

        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
