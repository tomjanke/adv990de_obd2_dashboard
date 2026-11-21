package de.adv990de.obd2.data

import androidx.room.withTransaction
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.entity.TrackEntity
import de.adv990de.obd2.data.db.entity.TrackPointEntity
import de.adv990de.obd2.track.GpxTrack
import de.adv990de.obd2.track.TrackFingerprint
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.TrackSource
import de.adv990de.obd2.track.TrackStats
import de.adv990de.obd2.track.TrackSummary
import de.adv990de.obd2.track.normalizeTrackName
import de.adv990de.obd2.track.trackStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Punkte je Schreibblock (und je Abbruchpruefung). */
private const val POINT_CHUNK = 500

/** Punkte je Seite beim Lesen — ein Track kann zehntausende haben, nie alle auf einmal an Room. */
private const val READ_PAGE = 5_000

/** Ids je Loeschaufruf — SQLite erlaubt auf API 26 nur 999 gebundene Variablen (wie bei `SessionRepository`). */
private const val DELETE_CHUNK_SIZE = 50

/**
 * Die Tracks: Aufzeichnen, Importieren, Auflisten, Umbenennen, Loeschen. Rein lokal; **`DataRetention` fasst die Tabellen nie an** (eigene Daten, bis der Nutzer loescht — `TrackRepositoryTest`
 * haelt das fest). Eine **laufende Aufzeichnung** ([activeTrackId], hoechstens eine) ist nicht loeschbar; ein Track, den ein beendeter Prozess hinterliess, schliesst [recoverUnfinished] ab.
 *
 * Alles Schreiben ist abbruchsicher und alles oder nichts je Aufruf: eine Transaktion, zwischen den Bloecken eine Abbruchpruefung — ein Fehler oder Abbruch laesst keine halbe Punktfolge zurueck.
 * Fehler werden nicht geschluckt; wer aufzeichnet, meldet sie einmal und macht weiter („Aufzeichnen ist Beiwerk“, Sache des Aufzeichners).
 */
@Singleton
class TrackRepository @Inject constructor(private val db: AppDatabase) {

    private val dao get() = db.trackDao()
    private val active = MutableStateFlow<Long?>(null)

    /** Die Id der laufenden Aufzeichnung, `null` ohne eine. */
    val activeTrackId: Long? get() = active.value

    /** Neueste zuerst. Eine Zeile mit einer Herkunft, die diese Version nicht kennt (aus einer spaeteren Version), wird **uebersprungen**, nicht zum Absturz. */
    val tracks: Flow<List<TrackSummary>> by lazy {
        combine(dao.observeAll(), active) { rows, running -> rows.mapNotNull { it.toSummary(running) } }
    }

    /**
     * Legt einen Track an und liefert seine Id. Eine **aufgezeichnete** Spur wird zur laufenden Aufzeichnung ([IllegalStateException], wenn schon eine laeuft); [sessionId] ist die OBD-Fahrt, falls
     * der Track an eine gekoppelt ist (muss es geben). [startMs] ist der Beginn der Aufzeichnung.
     */
    suspend fun create(
        name: String,
        source: TrackSource,
        sessionId: Long? = null,
        startMs: Long? = null,
        createdMs: Long = System.currentTimeMillis(),
    ): Long = withContext(Dispatchers.IO) {
        val clean = requireNotNull(normalizeTrackName(name)) { "A track needs a name" }
        check(source != TrackSource.RECORDED || active.value == null) { "A recording is already running" }
        val id = dao.insert(
            TrackEntity(
                name = clean, source = source.name, startMs = startMs, endMs = null, sessionId = sessionId,
                distanceM = 0, ascentM = null, descentM = null, pointCount = 0, createdMs = createdMs,
            ),
        )
        if (source == TrackSource.RECORDED) active.value = id
        id
    }

    /**
     * Haengt [points] an den Track an (Nummern fortlaufend ab der naechsten freien), in Bloecken zu [POINT_CHUNK] in **einer** Transaktion: ein Fehler oder Abbruch schreibt keinen Teil.
     * Der Track muss es geben (Fremdschluessel).
     */
    suspend fun appendPoints(trackId: Long, points: List<TrackPoint>) {
        if (points.isEmpty()) return
        withContext(Dispatchers.IO) {
            db.withTransaction {
                var seq = dao.nextSeq(trackId)
                for (chunk in points.chunked(POINT_CHUNK)) {
                    currentCoroutineContext().ensureActive()
                    dao.insertPoints(chunk.map { it.toEntity(trackId, seq++) })
                }
                dao.addPointCount(trackId, points.size)
            }
        }
    }

    /** Beendet die Aufzeichnung. Die laufende Aufzeichnung ist danach frei, **auch wenn das Schreiben scheitert** (sonst liesse sich nie wieder eine starten; der Rest ist ein Fall fuer [recoverUnfinished]). */
    suspend fun finish(trackId: Long, endMs: Long?, stats: TrackStats) {
        try {
            withContext(Dispatchers.IO) {
                dao.finish(trackId, endMs, stats.distanceM.roundToLong(), stats.ascentM?.roundToInt(), stats.descentM?.roundToInt())
            }
        } finally {
            if (active.value == trackId) active.value = null
        }
    }

    /** Alle Punkte des Tracks in Reihenfolge, seitenweise gelesen. */
    suspend fun points(trackId: Long): List<TrackPoint> = withContext(Dispatchers.IO) { readAll(trackId) }

    suspend fun rename(id: Long, name: String) = withContext(Dispatchers.IO) {
        dao.rename(id, requireNotNull(normalizeTrackName(name)) { "A track needs a name" })
    }

    /** Loescht die Tracks samt Punkten; in Bloecken, Doppelte ignoriert, **die laufende Aufzeichnung nie**. */
    suspend fun delete(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        val running = active.value
        ids.distinct().filter { it != running }.chunked(DELETE_CHUNK_SIZE).forEach { dao.deleteByIds(it) }
    }

    /**
     * Schliesst aufgezeichnete Tracks ab, die ein beendeter Prozess ohne Ende hinterliess: Ende = Zeit des letzten Punktes mit Zeit (sonst der Beginn), Kennzahlen aus den Punkten. Ein Track
     * **ohne einen Punkt** wird geloescht (nichts ging verloren: er hat nie etwas aufgenommen). Die laufende Aufzeichnung bleibt unberuehrt. Liefert die Zahl der abgeschlossenen Tracks.
     */
    suspend fun recoverUnfinished(): Int = withContext(Dispatchers.IO) {
        var recovered = 0
        for (row in dao.unfinishedRecorded()) {
            if (row.id == active.value) continue
            currentCoroutineContext().ensureActive()
            val points = readAll(row.id)
            if (points.isEmpty()) {
                dao.deleteByIds(listOf(row.id))
                continue
            }
            val stats = trackStats(points)
            val end = points.lastOrNull { it.timeMs != null }?.timeMs ?: row.startMs ?: row.createdMs
            dao.finish(row.id, end, stats.distanceM.roundToLong(), stats.ascentM?.roundToInt(), stats.descentM?.roundToInt())
            recovered++
        }
        recovered
    }

    /**
     * Schreibt Tracks einer Datei als **neue** Tracks, **alles oder nichts** (eine Transaktion um alle Tracks und Punkte). Beginn und Ende sind die erste und die letzte Zeit der Punkte — hat
     * die Datei keine, bleiben sie `null`. Ein Track ohne Namen heisst [fallbackName] (Nummer des Tracks in der Datei, ab 1, leere Tracks nicht mitgezaehlt); ein Track ohne Punkte wird
     * uebersprungen. Liefert die Ids in Dateireihenfolge. **Preis der einen Transaktion:** die Datenbank ist waehrend des Schreibens fuer andere Schreiber gesperrt.
     */
    suspend fun importTracks(tracks: List<GpxTrack>, nowMs: Long = System.currentTimeMillis(), fallbackName: (Int) -> String): List<Long> =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                val ids = ArrayList<Long>()
                for ((index, track) in tracks.filter { it.points.isNotEmpty() }.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val stats = trackStats(track.points)
                    val name = normalizeTrackName(track.name) ?: normalizeTrackName(fallbackName(index + 1)) ?: "Track ${index + 1}"
                    val id = dao.insert(
                        TrackEntity(
                            name = name, source = TrackSource.IMPORTED.name,
                            startMs = track.points.firstOrNull { it.timeMs != null }?.timeMs,
                            endMs = track.points.lastOrNull { it.timeMs != null }?.timeMs,
                            sessionId = null,
                            distanceM = stats.distanceM.roundToLong(),
                            ascentM = stats.ascentM?.roundToInt(), descentM = stats.descentM?.roundToInt(),
                            pointCount = track.points.size, createdMs = nowMs,
                        ),
                    )
                    var seq = 0
                    for (chunk in track.points.chunked(POINT_CHUNK)) {
                        currentCoroutineContext().ensureActive()
                        dao.insertPoints(chunk.map { it.toEntity(id, seq++) })
                    }
                    ids += id
                }
                ids
            }
        }

    /**
     * Die Fingerabdruecke der Tracks, die zu einem der Schluessel (Name, Punktzahl) passen: die Vorauswahl der Doppelten beim GPX-Import. Gelesen werden nur der erste und der letzte
     * Punkt je Treffer, nie alle Punkte; ein Track ohne Punkte zaehlt nicht.
     */
    suspend fun fingerprints(keys: Collection<Pair<String, Int>>): List<TrackFingerprint> = withContext(Dispatchers.IO) {
        val found = ArrayList<TrackFingerprint>()
        for ((name, count) in keys.distinct()) {
            for (id in dao.idsByNameAndCount(name, count)) {
                currentCoroutineContext().ensureActive()
                val first = dao.pointsAfter(id, -1, 1).firstOrNull() ?: continue
                val last = dao.lastPoint(id) ?: continue
                found += TrackFingerprint(name, count, first.lat, first.lon, last.lat, last.lon)
            }
        }
        found
    }

    private suspend fun readAll(trackId: Long): List<TrackPoint> {
        val all = ArrayList<TrackPoint>()
        var after = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = dao.pointsAfter(trackId, after, READ_PAGE)
            page.mapTo(all) { it.toPoint() }
            if (page.size < READ_PAGE) return all
            after = page.last().seq
        }
    }
}

private fun TrackEntity.toSummary(running: Long?): TrackSummary? {
    val kind = TrackSource.entries.firstOrNull { it.name == source } ?: return null
    return TrackSummary(
        id = id, name = name, source = kind, startMs = startMs, endMs = endMs, sessionId = sessionId,
        distanceM = distanceM, ascentM = ascentM, descentM = descentM, pointCount = pointCount, createdMs = createdMs,
        running = id == running,
    )
}

private fun TrackPoint.toEntity(trackId: Long, seq: Int) =
    TrackPointEntity(trackId, seq, timeMs, lat, lon, ele, accuracyM, speedKmh)

private fun TrackPointEntity.toPoint() = TrackPoint(timeMs, lat, lon, ele, accuracyM, speedKmh)
