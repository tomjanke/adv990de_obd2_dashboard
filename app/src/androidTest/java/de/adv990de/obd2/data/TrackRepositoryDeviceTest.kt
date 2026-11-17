package de.adv990de.obd2.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.track.GpxTrack
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.TrackSource
import de.adv990de.obd2.track.trackStats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TrackRepository] und [de.adv990de.obd2.data.db.TrackDao] an einer echten Datenbank: Reihenfolge der Punkte, Kaskade beim Loeschen, Bindung an die Fahrt, alles oder nichts beim
 * Import und das Wiederherstellen nach einem beendeten Prozess.
 */
@RunWith(AndroidJUnit4::class)
class TrackRepositoryDeviceTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TrackRepository

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = TrackRepository(db)
    }

    @After
    fun teardown() = db.close()

    private fun point(i: Int, time: Long? = 1_000L * i, lat: Double = 50.0 + i * 0.001) =
        TrackPoint(timeMs = time, lat = lat, lon = 8.0, ele = 100f + i, accuracyM = 4f, speedKmh = 40f)

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun pointsComeBackInTheOrderTheyWereWritten_acrossSeveralBlocks() = runBlocking {
        val id = repo.create("Tour", TrackSource.RECORDED, startMs = 0)

        repo.appendPoints(id, List(700) { point(it) })
        repo.appendPoints(id, List(300) { point(700 + it) })

        val read = repo.points(id)
        assertEquals(1_000, read.size)
        assertEquals((0 until 1_000).map { 1_000L * it }, read.map { it.timeMs })
        assertEquals(point(5).lat, read[5].lat, 0.0)
        assertEquals(point(5).ele, read[5].ele)
        assertEquals("the running track already shows its count", 1_000, repo.tracks.first().single().pointCount)
    }

    @Test
    fun allOptionalFieldsOfAPointSurviveAsNull() = runBlocking {
        val id = repo.create("Tour", TrackSource.RECORDED)

        repo.appendPoints(id, listOf(TrackPoint(null, 50.0, 8.0, null, null, null)))

        val p = repo.points(id).single()
        assertEquals(listOf(null, null, null, null), listOf(p.timeMs, p.ele, p.accuracyM, p.speedKmh))
    }

    @Test
    fun finishingStoresTheStatistics_andTheListShowsThemNewestFirst() = runBlocking {
        val old = repo.create("alt", TrackSource.RECORDED, startMs = 1_000, createdMs = 1_000)
        repo.appendPoints(old, List(5) { point(it) })
        repo.finish(old, endMs = 5_000, stats = trackStats(repo.points(old)))
        val newer = repo.create("neu", TrackSource.RECORDED, startMs = 9_000, createdMs = 9_000)

        val list = repo.tracks.first()

        assertEquals(listOf(newer, old), list.map { it.id })
        assertEquals(listOf(true, false), list.map { it.running })
        val finished = list.last()
        assertEquals(5_000L, finished.endMs)
        assertEquals(5, finished.pointCount)
        assertTrue("distance of five points 111 m apart", finished.distanceM in 400..500)
    }

    @Test
    fun finishingCorrectsTheCountFromTheRealPoints() = runBlocking {
        val id = repo.create("Tour", TrackSource.RECORDED)
        repo.appendPoints(id, List(4) { point(it) })
        db.openHelper.writableDatabase.execSQL("UPDATE tracks SET pointCount = 99 WHERE id = $id")   // a count that drifted

        repo.finish(id, endMs = 4_000, stats = trackStats(repo.points(id)))

        assertEquals("the number of points actually stored is authoritative", 4, repo.tracks.first().single().pointCount)
    }

    @Test
    fun deletingATrackTakesItsPointsWithIt_butNotThoseOfAnother() = runBlocking {
        val a = repo.create("A", TrackSource.IMPORTED)
        val b = repo.create("B", TrackSource.IMPORTED)
        repo.appendPoints(a, List(10) { point(it) })
        repo.appendPoints(b, List(4) { point(it) })

        repo.delete(listOf(a))

        assertEquals(listOf(b), repo.tracks.first().map { it.id })
        assertEquals(4, count("track_points"))
        assertEquals(emptyList<TrackPoint>(), repo.points(a))
    }

    @Test
    fun theRunningRecordingCannotBeDeleted() = runBlocking {
        val running = repo.create("läuft", TrackSource.RECORDED)
        repo.appendPoints(running, List(3) { point(it) })

        repo.delete(listOf(running))

        assertEquals(listOf(running), repo.tracks.first().map { it.id })
        assertEquals(3, count("track_points"))
    }

    @Test
    fun aTrackKeepsItsTripLink_andLosesItWhenTheTripIsDeleted_butStays() = runBlocking {
        val sessionId = db.sessionDao().insert(SessionEntity(startTime = 1_000, endTime = 2_000))
        val id = repo.create("Tour", TrackSource.IMPORTED, sessionId = sessionId)
        assertEquals(sessionId, repo.tracks.first().single().sessionId)

        db.sessionDao().deleteByIds(listOf(sessionId))

        val after = repo.tracks.first().single()
        assertEquals("the track stays (own data, outlives the 90-day retention of trips)", id, after.id)
        assertNull("the link does not dangle", after.sessionId)
    }

    @Test
    fun aTrackCannotPointToATripThatDoesNotExist() {
        assertThrows(SQLiteException::class.java) { runBlocking { repo.create("Tour", TrackSource.IMPORTED, sessionId = 999) } }
    }

    @Test
    fun renameChangesOnlyTheName() = runBlocking {
        val id = repo.create("alt", TrackSource.IMPORTED)

        repo.rename(id, "  neu ")

        assertEquals("neu", repo.tracks.first().single().name)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.rename(id, " ") } }
        assertEquals("neu", repo.tracks.first().single().name)
    }

    // ── Import: alles oder nichts ────────────────────────────────────────────

    @Test
    fun anImportWritesEveryTrackWithItsPoints() = runBlocking {
        val ids = repo.importTracks(
            listOf(GpxTrack("A", List(3) { point(it) }), GpxTrack(null, List(2) { point(it) })),
            nowMs = 7_000,
        ) { "Track $it" }

        val list = repo.tracks.first().sortedBy { it.id }
        assertEquals(ids, list.map { it.id })
        assertEquals(listOf("A", "Track 2"), list.map { it.name })
        assertEquals(listOf(TrackSource.IMPORTED, TrackSource.IMPORTED), list.map { it.source })
        assertEquals(listOf(3, 2), list.map { it.pointCount })
        assertEquals(listOf(false, false), list.map { it.running })
        assertEquals(5, count("track_points"))
        assertEquals("a point list read back is the one written", 3, repo.points(ids[0]).size)
    }

    @Test
    fun aFailureInTheMiddleOfAnImport_leavesNeitherTracksNorPoints() = runBlocking {
        val untouched = repo.create("vorher", TrackSource.IMPORTED)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_on_boom BEFORE INSERT ON track_points WHEN NEW.lat = 99 BEGIN SELECT RAISE(ABORT, 'boom'); END",
        )
        val good = GpxTrack("gut", List(3) { point(it) })
        val bad = GpxTrack("kaputt", listOf(point(0), point(1, lat = 99.0), point(2)))

        assertThrows(SQLiteException::class.java) { runBlocking { repo.importTracks(listOf(good, bad), nowMs = 1) { "Track $it" } } }

        assertEquals("the first track, written before the failure, is rolled back too", listOf(untouched), repo.tracks.first().map { it.id })
        assertEquals(0, count("track_points"))
    }

    @Test
    fun aFailureWhileAppendingPoints_leavesNoHalfBlockAndNoWrongCount() = runBlocking {
        val id = repo.create("Tour", TrackSource.RECORDED)
        repo.appendPoints(id, List(3) { point(it) })
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_on_boom BEFORE INSERT ON track_points WHEN NEW.lat = 99 BEGIN SELECT RAISE(ABORT, 'boom'); END",
        )

        assertThrows(SQLiteException::class.java) {
            runBlocking { repo.appendPoints(id, List(600) { point(10 + it) } + point(999, lat = 99.0)) }
        }

        assertEquals("all or nothing for one call", 3, count("track_points"))
        assertEquals(3, repo.tracks.first().single().pointCount)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_on_boom")
        repo.appendPoints(id, listOf(point(50)))
        assertEquals("the sequence continues without a gap or a clash", (0..3).toList(), db.trackDao().pointsAfter(id, -1, 100).map { it.seq })
    }

    // ── Wiederherstellen ──────────────────────────────────────────────────────

    @Test
    fun aRecordingThatWasCutOffIsFinishedAtItsLastPoint_afterTheProcessWasGone() = runBlocking {
        val cut = repo.create("abgebrochen", TrackSource.RECORDED, startMs = 100)
        repo.appendPoints(cut, List(4) { point(it, time = 2_000L + 1_000L * it) })
        val empty = TrackRepository(db).create("leer", TrackSource.RECORDED, startMs = 200)   // a second "process": only one recording runs per repository
        val restarted = TrackRepository(db)   // the new process: nothing is running

        val recovered = restarted.recoverUnfinished()

        assertEquals(1, recovered)
        val list = restarted.tracks.first()
        assertEquals("the empty one is dropped", listOf(cut), list.map { it.id })
        val t = list.single()
        assertEquals(5_000L, t.endMs)
        assertEquals(4, t.pointCount)
        assertFalse(t.running)
        assertTrue(t.distanceM > 0)
        assertFalse(empty in list.map { it.id })
    }

    @Test
    fun anImportedTrackWithoutTimesIsNotTakenForACutOffRecording() = runBlocking {
        val id = repo.importTracks(listOf(GpxTrack("ohne Zeit", List(3) { point(it, time = null) })), nowMs = 1) { "Track $it" }.single()
        val restarted = TrackRepository(db)

        assertEquals(0, restarted.recoverUnfinished())

        assertEquals(listOf(id), restarted.tracks.first().map { it.id })
        assertEquals(3, restarted.points(id).size)
    }
}
