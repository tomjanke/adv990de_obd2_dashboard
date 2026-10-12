package de.adv990de.obd2.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sensor_readings",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class SensorReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    val pidId: String,
    val value: Float,
    /**
     * Woher der Wert kam: `ECU` oder `GPS` (`ValueSource.name`). `null` bei Zeilen, die vor 1.25.0 aufgezeichnet wurden — ihre Herkunft
     * kennt niemand (die GPS-Geschwindigkeit trug dieselbe PID-ID wie die des Fahrzeugs), `ECU` zu raten waere eine Behauptung.
     */
    val source: String? = null,
)
