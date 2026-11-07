package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import androidx.paging.PagingSource
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.obd.model.DtcEntry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.tripimport.ParsedTrip
import de.adv990de.obd2.tripimport.SessionSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.Writer
import javax.inject.Inject
import javax.inject.Singleton

private const val DELETE_CHUNK_SIZE = 50

/** Wie lange eine Fahrt aufbewahrt wird (90 Tage): [DataRetention] loescht aeltere beim App-Start, der Fahrten-Import nimmt sie deshalb gar nicht erst an. */
internal const val SESSION_RETENTION_MS = 90L * 24 * 60 * 60 * 1000

/** So viele Messwerte schreibt der Import je Aufruf an die Datenbank (die Entitaeten entstehen erst hier, nie fuer die ganze Datei auf einmal). */
private const val IMPORT_CHUNK_ROWS = 1_000

@Singleton
class SessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase
) {
    private val files = CsvExportFiles(context)

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
     *
     * [dtcCodes] are the fault codes the vehicle reported when the trip started (`SessionDtcs.kt`: `null` = not recorded,
     * empty = none found). They go into THIS insert — no second step that a process kill could separate from the trip.
     */
    suspend fun startSession(vin: String? = null, dtcCodes: List<DtcEntry>? = null): Long {
        currentCoroutineContext().ensureActive()
        var created: Long? = null
        try {
            return withContext(Dispatchers.IO + NonCancellable) {
                val id = db.sessionDao().insert(
                    SessionEntity(startTime = System.currentTimeMillis(), vehicleVin = vin, dtcCodes = encodeSessionDtcs(dtcCodes)),
                )
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
                value = it.value,
                source = it.source.name,
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
        maxAgeMs: Long = SESSION_RETENTION_MS
    ) = withContext(Dispatchers.IO) {
        val active = _activeSessionId.value
        val old = db.sessionDao().idsOlderThan(System.currentTimeMillis() - maxAgeMs)
        deleteRows(old.filter { it != active })
    }

    /** Beginn und Ende jeder Fahrt in der App — damit der Fahrten-Import erkennt, welche Fahrten der Datei schon da sind ([SessionDao.spans]). */
    suspend fun sessionSpans(): List<SessionSpan> = withContext(Dispatchers.IO) { db.sessionDao().spans() }

    /**
     * Schreibt die Fahrten einer Import-Datei als **neue** Fahrten, **alles oder nichts**: eine Transaktion um alle Fahrten und alle Messwerte — ein Fehler oder der Abbruch des
     * Aufrufers (die Pruefung zwischen den Bloecken wirft) laesst die Datenbank unveraendert. Jede Fahrt bekommt eine neue Id; Beginn und Ende sind der kleinste und der groesste
     * Zeitstempel ihrer Zeilen, FIN und Fehlercodes bleiben `null` (nicht erfasst). Die Messwerte gehen in Bloecken zu [IMPORT_CHUNK_ROWS] an die Datenbank, in der Reihenfolge der
     * Datei (die Reihenfolge der Aufzeichnung, die der Export wieder liest). **Preis der einen Transaktion:** die Datenbank ist waehrend des Schreibens (bei 100 000 Zeilen einige
     * Sekunden) fuer andere Schreiber gesperrt — schreibt gerade die Aufzeichnung einer laufenden Fahrt, wartet sie so lange.
     */
    suspend fun importTrips(trips: List<ParsedTrip>) = withContext(Dispatchers.IO) {
        db.withTransaction {
            for (trip in trips) {
                val sessionId = db.sessionDao().insert(SessionEntity(startTime = trip.startMs, endTime = trip.endMs))
                var from = 0
                while (from < trip.rows.size) {
                    currentCoroutineContext().ensureActive()
                    val to = minOf(from + IMPORT_CHUNK_ROWS, trip.rows.size)
                    db.sensorReadingDao().insertAll(
                        List(to - from) { k ->
                            val i = from + k
                            SensorReadingEntity(
                                sessionId = sessionId,
                                timestamp = trip.rows.timestamp(i),
                                pidId = trip.rows.pid(i),
                                value = trip.rows.value(i),
                                source = trip.rows.source(i),
                            )
                        },
                    )
                    from = to
                }
            }
        }
    }

    /**
     * Retention for the exported CSV files (see [exportToCsv]): removes the ones older than a day. Runs once per app
     * start from [DataRetention]; an export also cleans up before it writes. Returns how many files were deleted.
     */
    suspend fun purgeStaleExports(): Int = withContext(Dispatchers.IO) {
        deleteStaleExports(File(context.cacheDir, EXPORT_CACHE_DIR), System.currentTimeMillis())
    }

    /**
     * Exports ONE trip to a CSV file and returns a shareable [Uri] — the same as [exportSessionsToCsv] with a single trip
     * (the file is named after the trip, `session_<id>_<time>.csv`).
     */
    suspend fun exportToCsv(sessionId: Long): Uri = exportSessionsToCsv(listOf(sessionId))

    /**
     * Exports the given trips, one after the other in the order given, to ONE CSV file and returns a shareable [Uri]
     * (`session_all_<time>.csv`; a single trip keeps its own name). [sessionIds] must not be empty — the caller decides which
     * trips (the history passes the finished ones the user still sees).
     *
     * Cursor-based: rows are written as they are read, one trip after the other, and the query ([EXPORT_QUERY_SQL]) needs no sort,
     * so memory stays constant however many trips and rows there are. Columns ([EXPORT_COLUMNS]): `timestamp,pid,value,name,source,session` —
     * the first three are the original ones (a parser that reads by position keeps working), `name` is the stable English PID name:
     * Excel turns `0105` into the number 105 when the file is opened by double click, the name column still identifies the PID.
     * `source` is `ECU`, `GPS` or `DERIVED` (the GPS speed has the same PID id as the vehicle's); empty for rows recorded before 1.25.0.
     * `session` is the trip id, so the trips stay apart when several share one file (since 1.34.0, in every export).
     *
     * Every export gets its own file (DAT-06: a fixed name was deleted under a share that was still reading it);
     * exports older than a day are removed first (DAT-07: they were never deleted). A failed export leaves no
     * half-written file behind.
     */
    suspend fun exportSessionsToCsv(sessionIds: List<Long>): Uri {
        requireTripsToExport(sessionIds)
        return files.share({ exportFileNameFor(sessionIds, it) }) { writeTripsCsv(it, sessionIds) }
    }

    /**
     * Writes the same CSV as [exportSessionsToCsv] into a file the user chose with the system file dialog ([target], from `CreateDocument`).
     * The CSV is written to the cache first and copied only when it is complete: a failure while reading never touches the target, so no
     * half-written document is left there. The cache file is removed afterwards, whatever happened. A target that cannot be opened for
     * writing is an [IOException].
     */
    suspend fun saveSessionsCsv(sessionIds: List<Long>, target: Uri) {
        requireTripsToExport(sessionIds)
        files.save(target, { exportFileNameFor(sessionIds, it) }) { writeTripsCsv(it, sessionIds) }
    }

    /**
     * Kleinster und groesster Wert von Kuehlmittel und Spannung einer Fahrt (F11, Detail-Dialog) — aus den aufgezeichneten Messwerten, also auch fuer aeltere Fahrten, soweit sie beides aufzeichneten.
     * Die Rechnung steht in [SessionStatsAccumulator] (Spannung nur bei laufendem Motor); hier wird nur gelesen: Zeile fuer Zeile per Cursor ([SESSION_STATS_QUERY_SQL], ohne Sortierlauf), mit
     * konstantem Speicher. Eine abgebrochene Anfrage hoert nach hoechstens [CANCELLATION_CHECK_ROWS] Zeilen auf; ein Lesefehler geht an den Aufrufer (kein „keine Werte“).
     * Eine laufende Aufzeichnung liefert den Stand zum Zeitpunkt der Anfrage.
     */
    suspend fun sessionStats(sessionId: Long): SessionStats = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val accumulator = SessionStatsAccumulator()
        val query = SimpleSQLiteQuery(SESSION_STATS_QUERY_SQL, arrayOf(sessionId))
        db.sensorReadingDao().getBySessionCursor(query).use { cursor ->
            val colTimestamp = cursor.getColumnIndexOrThrow("timestamp")
            val colPid       = cursor.getColumnIndexOrThrow("pidId")
            val colValue     = cursor.getColumnIndexOrThrow("value")
            var read = 0
            while (cursor.moveToNext()) {
                accumulator.add(cursor.getLong(colTimestamp), cursor.getString(colPid).orEmpty(), cursor.getFloat(colValue))
                if (++read % CANCELLATION_CHECK_ROWS == 0) currentCoroutineContext().ensureActive()
            }
        }
        accumulator.result()
    }

    /** The trips a data export takes: every finished trip, oldest first — the one being recorded right now is not part of it. */
    suspend fun exportableSessionIds(): List<Long> {
        val active = _activeSessionId.value
        return sessionIds.first().filter { it != active }.sorted()
    }

    private fun requireTripsToExport(sessionIds: List<Long>) = require(sessionIds.isNotEmpty()) { "There is no trip to export" }

    /** The one place that writes the rows of the export: the trips, one after the other, into the file [CsvExportFiles] opened. */
    internal suspend fun writeTripsCsv(writer: Writer, sessionIds: List<Long>) {
        writer.write(csvRow(*EXPORT_COLUMNS))
        var written = 0
        for (id in sessionIds) {
            currentCoroutineContext().ensureActive()
            val tripId = id.toString()
            val query = SimpleSQLiteQuery(EXPORT_QUERY_SQL, arrayOf(id))
            db.sensorReadingDao().getBySessionCursor(query).use { cursor ->
                val colTimestamp = cursor.getColumnIndexOrThrow("timestamp")
                val colPid       = cursor.getColumnIndexOrThrow("pidId")
                val colValue     = cursor.getColumnIndexOrThrow("value")
                val colSource    = cursor.getColumnIndexOrThrow("source")
                while (cursor.moveToNext()) {
                    val pidId = cursor.getString(colPid).orEmpty()
                    writer.write(
                        csvRow(
                            cursor.getLong(colTimestamp).toString(),
                            pidId,
                            cursor.getFloat(colValue).toString(),
                            csvPidName(pidId),
                            cursor.getString(colSource).orEmpty(),
                            tripId,
                        )
                    )
                    // A long export (many trips) must stop when the caller is cancelled, not run to the end.
                    if (++written % CANCELLATION_CHECK_ROWS == 0) currentCoroutineContext().ensureActive()
                }
            }
        }
    }
}
