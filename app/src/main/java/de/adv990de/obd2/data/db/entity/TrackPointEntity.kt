package de.adv990de.obd2.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * Ein Punkt eines Tracks. Der Schluessel `(trackId, seq)` ist zugleich der Index, nach dem gelesen wird (die Punkte eines Tracks in Reihenfolge); `seq` zaehlt je Track ab 0 ohne Luecke.
 * Loescht man den Track, gehen seine Punkte mit (`CASCADE`). Koordinaten als `Double` (6 Nachkommastellen genuegen), alles andere ist fakultativ: eine GPX-Datei hat oft keine Zeit
 * oder Hoehe.
 */
@Entity(
    tableName = "track_points",
    primaryKeys = ["trackId", "seq"],
    foreignKeys = [ForeignKey(
        entity = TrackEntity::class,
        parentColumns = ["id"],
        childColumns = ["trackId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class TrackPointEntity(
    val trackId: Long,
    val seq: Int,
    val timeMs: Long?,
    val lat: Double,
    val lon: Double,
    val ele: Float?,
    val accuracyM: Float?,
    val speedKmh: Float?,
)
