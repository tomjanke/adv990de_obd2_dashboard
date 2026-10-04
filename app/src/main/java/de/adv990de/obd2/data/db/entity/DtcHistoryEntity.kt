package de.adv990de.obd2.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per fault code the vehicle has ever reported to this app (local fault history).
 * Only the code is stored — the description is resolved for display by `dtcDescription(code)`.
 */
@Entity(tableName = "dtc_history")
data class DtcHistoryEntity(
    @PrimaryKey val code: String,        // e.g. "P0301"
    val firstSeen: Long,                 // epoch millis of the first sighting, never overwritten
    val lastSeen: Long,                  // epoch millis of the latest sighting, only moves forward
    val lastWasPending: Boolean,         // true if the latest sighting was a mode 07 (pending) code only
)
