package de.adv990de.obd2.data

import de.adv990de.obd2.data.db.AppLogDao
import de.adv990de.obd2.data.db.entity.AppLogEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLogRepository @Inject constructor(private val dao: AppLogDao) {

    val logs: Flow<List<AppLogEntity>> by lazy { dao.getAllNewestFirst() }

    suspend fun log(level: LogLevel, message: String) {
        dao.insert(AppLogEntity(timestamp = System.currentTimeMillis(), level = level.name, message = message))
    }

    suspend fun pruneOlderThan(maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000) {
        dao.deleteOlderThan(System.currentTimeMillis() - maxAgeMs)
    }

    suspend fun clearAll() = dao.deleteAll()
}
