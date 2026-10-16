package de.adv990de.obd2.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
    val vehicleVin: String? = null,
    /**
     * Die Fehlercodes, die das Fahrzeug bei Beginn der Fahrt gemeldet hat (Format und drei Zustaende: `SessionDtcs.kt`):
     * `null` = nicht erfasst (Fahrten vor 1.33.0, oder das Auslesen scheiterte), leer = gelesen, keine Codes.
     */
    val dtcCodes: String? = null,
)
