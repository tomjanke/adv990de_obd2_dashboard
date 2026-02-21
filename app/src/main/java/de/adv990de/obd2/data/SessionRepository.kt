package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import androidx.paging.PagingSource
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.core.content.FileProvider
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.entity.SensorReadingEntity
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.obd.model.SensorValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase
) {

    val allSessions: Flow<List<SessionEntity>> = db.sessionDao().getAll()

    fun pagingSource(): PagingSource<Int, SessionEntity> = db.sessionDao().pagingSource()

    suspend fun startSession(vin: String? = null): Long = withContext(Dispatchers.IO) {
        db.sessionDao().insert(SessionEntity(startTime = System.currentTimeMillis(), vehicleVin = vin))
    }

    suspend fun endSession(sessionId: Long) = withContext(Dispatchers.IO) {
        val session = db.sessionDao().getById(sessionId) ?: return@withContext
        db.sessionDao().update(session.copy(endTime = System.currentTimeMillis()))
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
     * Exports a session to a CSV file and returns a shareable [Uri].
     * Note: all readings for the session are loaded into memory at once.
     * For typical sessions this is acceptable; very long sessions may need
     * a streaming/cursor-based approach if memory becomes a concern.
     */
    suspend fun exportToCsv(sessionId: Long): Uri = withContext(Dispatchers.IO) {
        val readings = db.sensorReadingDao().getBySession(sessionId)
        val exportDir = File(context.cacheDir, "exports").also { it.mkdirs() }
        val file = File(exportDir, "session_$sessionId.csv")

        file.bufferedWriter().use { writer ->
            writer.write("timestamp,pid,value\n")
            readings.forEach { r ->
                writer.write("${r.timestamp},${r.pidId},${r.value}\n")
            }
        }

        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
