package de.adv990de.obd2.alarm

import de.adv990de.obd2.data.LogLevel

/**
 * Wohin der `AlarmController` schreibt, wenn ein Alarm entsteht oder endet (ins App-Log). Eine Naht statt des `AppLogRepository`, damit der Controller ohne Datenbank
 * testbar bleibt; Hilt bindet sie im `AlarmModule` an `AppLogRepository.log` (das nie wirft).
 */
fun interface AlarmLog {
    suspend fun log(level: LogLevel, message: String)

    /** Schreibt nirgends hin — der Standard für Tests. */
    object None : AlarmLog {
        override suspend fun log(level: LogLevel, message: String) = Unit
    }
}
