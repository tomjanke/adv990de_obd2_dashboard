package de.adv990de.obd2.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import de.adv990de.obd2.data.db.entity.TrackEntity
import de.adv990de.obd2.data.db.entity.TrackPointEntity
import kotlinx.coroutines.flow.Flow

/** Tracks und ihre Punkte. Ein `@Insert` mit einer Liste ist eine Transaktion; Ids beim Loeschen hoechstens in Bloecken zu 50 (SQLite erlaubt auf API 26 nur 999 Variablen). */
@Dao
interface TrackDao {
    @Insert suspend fun insert(track: TrackEntity): Long

    @Insert suspend fun insertPoints(points: List<TrackPointEntity>)

    /** Neueste zuerst (Beginn, ohne Zeit die Anlage; dann die Id). */
    @Query("SELECT * FROM tracks ORDER BY COALESCE(startMs, createdMs) DESC, id DESC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getById(id: Long): TrackEntity?

    /** Die naechste freie Nummer der Punkte dieses Tracks (0 bei einem leeren Track). */
    @Query("SELECT COALESCE(MAX(seq), -1) + 1 FROM track_points WHERE trackId = :trackId")
    suspend fun nextSeq(trackId: Long): Int

    @Query("UPDATE tracks SET pointCount = pointCount + :added WHERE id = :trackId")
    suspend fun addPointCount(trackId: Long, added: Int)

    /** Eine Seite Punkte in Reihenfolge, beginnend hinter [afterSeq] (-1 = von vorn). */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND seq > :afterSeq ORDER BY seq LIMIT :limit")
    suspend fun pointsAfter(trackId: Long, afterSeq: Int, limit: Int): List<TrackPointEntity>

    /** Die Ids der Tracks mit diesem Namen und dieser Punktzahl: die Vorauswahl, wenn der Import nach Doppelten sucht. */
    @Query("SELECT id FROM tracks WHERE name = :name AND pointCount = :pointCount")
    suspend fun idsByNameAndCount(name: String, pointCount: Int): List<Long>

    /** Der letzte Punkt dieses Tracks; `null` ohne Punkte. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY seq DESC LIMIT 1")
    suspend fun lastPoint(trackId: Long): TrackPointEntity?

    /** Beendet einen Track: Ende, Kennzahlen und die tatsaechliche Zahl der Punkte (massgeblich, nicht die laufend mitgezaehlte). */
    @Query(
        "UPDATE tracks SET endMs = :endMs, distanceM = :distanceM, ascentM = :ascentM, descentM = :descentM, " +
            "pointCount = (SELECT COUNT(*) FROM track_points WHERE trackId = :id) WHERE id = :id",
    )
    suspend fun finish(id: Long, endMs: Long?, distanceM: Long, ascentM: Int?, descentM: Int?)

    /** Aufgezeichnete Tracks ohne Ende: laufend oder von einem beendeten Prozess abgebrochen. Importe zaehlen nie (sie haben oft keine Zeit). */
    @Query("SELECT * FROM tracks WHERE source = 'RECORDED' AND endMs IS NULL")
    suspend fun unfinishedRecorded(): List<TrackEntity>

    @Query("UPDATE tracks SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
