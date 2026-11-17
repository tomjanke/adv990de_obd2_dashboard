package de.adv990de.obd2.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ein Track (aufgezeichnet oder importiert). **Ganze Einheiten, wo es geht:** Meter als `Long`/`Int`, Zeiten in Millisekunden seit 1970. [source] ist der Name von
 * `TrackSource` (`RECORDED`/`IMPORTED`, nie umbenennen). [endMs] ist bei einer laufenden Aufzeichnung `null` — und bei einem Import ohne Zeiten; was „laeuft“ heisst, entscheidet
 * [source] zusammen mit der Aufzeichnung im Repository, nicht die Spalte allein.
 *
 * [sessionId] verweist auf die OBD-Fahrt, falls der Track an eine Sitzung gekoppelt war. `SET NULL`: loescht die Aufbewahrung (90 Tage) die Fahrt, bleibt der Track (eigene Daten, vom
 * Nutzer aufgezeichnet oder importiert) und verliert nur den Verweis.
 */
@Entity(
    tableName = "tracks",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.SET_NULL,
    )],
    indices = [Index("sessionId")],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val source: String,
    val startMs: Long?,
    val endMs: Long?,
    val sessionId: Long?,
    val distanceM: Long,
    val ascentM: Int?,
    val descentM: Int?,
    val pointCount: Int,
    val createdMs: Long,
)
