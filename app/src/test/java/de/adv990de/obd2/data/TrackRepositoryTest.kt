package de.adv990de.obd2.data

import androidx.room.withTransaction
import de.adv990de.obd2.data.db.AppDatabase
import de.adv990de.obd2.data.db.TrackDao
import de.adv990de.obd2.data.db.entity.TrackEntity
import de.adv990de.obd2.data.db.entity.TrackPointEntity
import de.adv990de.obd2.projectRoot
import de.adv990de.obd2.track.GpxTrack
import de.adv990de.obd2.track.TRACK_NAME_MAX
import de.adv990de.obd2.track.TrackPoint
import de.adv990de.obd2.track.TrackSource
import de.adv990de.obd2.track.normalizeTrackName
import de.adv990de.obd2.track.trackStats
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [TrackRepository] gegen eine Attrappe des DAOs: was geschrieben wird, in welcher Form und in welcher Reihenfolge. Dass die Transaktionen wirklich alles oder nichts sind und
 * das Loeschen kaskadiert, zeigt `TrackRepositoryDeviceTest` an einer echten Datenbank.
 */
class TrackRepositoryTest {

    private val dao = mockk<TrackDao>(relaxUnitFun = true)
    private val db = mockk<AppDatabase> { every { trackDao() } returns dao }
    private val repo = TrackRepository(db)

    private var inTransaction = false
    private val insertedTracks = ArrayList<TrackEntity>()
    private val insertedPoints = ArrayList<List<TrackPointEntity>>()
    private var nextId = 40L

    @Before
    fun setup() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        val block = slot<suspend () -> Any?>()
        coEvery { db.withTransaction(capture(block)) } coAnswers {
            inTransaction = true
            try { block.captured.invoke() } finally { inTransaction = false }
        }
        coEvery { dao.insert(any()) } coAnswers { insertedTracks += firstArg<TrackEntity>(); nextId++ }
        coEvery { dao.insertPoints(any()) } coAnswers { insertedPoints += firstArg<List<TrackPointEntity>>() }
        coEvery { dao.nextSeq(any()) } returns 0
    }

    @After
    fun teardown() = unmockkStatic("androidx.room.RoomDatabaseKt")

    private fun point(i: Int, time: Long? = 1_000L * i, ele: Float? = null) =
        TrackPoint(timeMs = time, lat = 50.0 + i * 0.001, lon = 8.0, ele = ele, accuracyM = 5f, speedKmh = 30f)

    // ── Name ──────────────────────────────────────────────────────────────────

    @Test
    fun aNameIsTrimmed_cutAtTheLimit_andBlankIsNoName() {
        assertEquals("Feldberg", normalizeTrackName("  Feldberg \n"))
        assertEquals(TRACK_NAME_MAX, normalizeTrackName("x".repeat(TRACK_NAME_MAX + 50))!!.length)
        assertEquals("the limit is 100 characters (the page offers a field of that size)", 100, normalizeTrackName("x".repeat(500))!!.length)
        assertNull(normalizeTrackName("   "))
        assertNull(normalizeTrackName(null))
    }

    @Test
    fun createRejectsABlankName_andStoresTheTrimmedOne() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.create("  ", TrackSource.RECORDED) } }

        val id = repo.create("  Morgenrunde ", TrackSource.RECORDED, sessionId = 7, startMs = 5_000, createdMs = 6_000)

        assertEquals(40L, id)
        val row = insertedTracks.single()
        assertEquals("Morgenrunde", row.name)
        assertEquals("RECORDED", row.source)
        assertEquals(listOf<Any?>(7L, 5_000L, null, 6_000L), listOf(row.sessionId, row.startMs, row.endMs, row.createdMs))
        assertEquals(0, row.pointCount)
    }

    // ── Laufende Aufzeichnung ─────────────────────────────────────────────────

    @Test
    fun onlyOneRecordingRunsAtATime_andFinishingFreesIt() = runBlocking<Unit> {
        val first = repo.create("A", TrackSource.RECORDED)
        assertEquals(first, repo.activeTrackId)
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.create("B", TrackSource.RECORDED) } }

        repo.finish(first, endMs = 9_000, stats = trackStats(listOf(point(0), point(1))))

        assertNull(repo.activeTrackId)
        repo.create("B", TrackSource.RECORDED)   // jetzt geht es
    }

    @Test
    fun anImportedTrackIsNeverTheRunningRecording() = runBlocking<Unit> {
        repo.create("Import", TrackSource.IMPORTED)

        assertNull(repo.activeTrackId)
        repo.create("Aufzeichnung", TrackSource.RECORDED)   // darf neben einem Import angelegt werden
    }

    @Test
    fun finishWritesTheRoundedStatistics() = runBlocking {
        val id = repo.create("A", TrackSource.RECORDED)
        val stats = trackStats(listOf(point(0, ele = 100f), point(1, ele = 140f), point(2, ele = 120f)))

        repo.finish(id, endMs = 2_000, stats = stats)

        coVerify {
            dao.finish(
                id, 2_000L, Math.round(stats.distanceM),
                stats.ascentM?.let { Math.round(it).toInt() }, stats.descentM?.let { Math.round(it).toInt() },
            )
        }
    }

    @Test
    fun finishFreesTheRecording_evenWhenWritingFails() = runBlocking {
        val id = repo.create("A", TrackSource.RECORDED)
        coEvery { dao.finish(any(), any(), any(), any(), any()) } throws IllegalStateException("disk")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.finish(id, endMs = 1, stats = trackStats(listOf(point(0)))) }
        }

        assertNull("a later start must not be blocked by a recording that cannot be finished", repo.activeTrackId)
    }

    // ── Punkte ────────────────────────────────────────────────────────────────

    @Test
    fun pointsContinueTheSequence_inBlocks_insideOneTransaction() = runBlocking {
        coEvery { dao.nextSeq(5) } returns 7
        coEvery { dao.insertPoints(any()) } coAnswers {
            assertTrue("the points are written inside the transaction", inTransaction)
            insertedPoints += firstArg<List<TrackPointEntity>>()
        }

        repo.appendPoints(5, List(1_200) { point(it) })

        assertEquals("blocks of 500", listOf(500, 500, 200), insertedPoints.map { it.size })
        val all = insertedPoints.flatten()
        assertEquals((7 until 7 + 1_200).toList(), all.map { it.seq })
        assertTrue(all.all { it.trackId == 5L })
        assertEquals(point(0).lat, all.first().lat, 0.0)
        coVerify { dao.addPointCount(5, 1_200) }
    }

    @Test
    fun noPointsMeansNoWrite() = runBlocking {
        repo.appendPoints(5, emptyList())

        coVerify(exactly = 0) { dao.insertPoints(any()) }
        coVerify(exactly = 0) { dao.addPointCount(any(), any()) }
    }

    @Test
    fun pointsAreReadInPagesUntilTheyEnd() = runBlocking {
        fun rows(range: IntRange) = range.map { TrackPointEntity(1, it, 1_000L * it, 50.0, 8.0, null, null, null) }
        coEvery { dao.pointsAfter(1, -1, any()) } returns rows(0..4_999)
        coEvery { dao.pointsAfter(1, 4_999, any()) } returns rows(5_000..5_999)

        val read = repo.points(1)

        assertEquals(6_000, read.size)
        assertEquals((0 until 6_000).map { 1_000L * it }, read.map { it.timeMs })
    }

    // ── Import ────────────────────────────────────────────────────────────────

    @Test
    fun anImportIsOneTransaction_withStatsAndTimesFromThePoints() = runBlocking {
        coEvery { dao.insert(any()) } coAnswers {
            assertTrue("a track is inserted inside the transaction", inTransaction)
            insertedTracks += firstArg<TrackEntity>(); nextId++
        }
        val withTime = GpxTrack("Tour", listOf(point(0, time = 10_000, ele = 100f), point(1, time = 20_000, ele = 130f), point(2, time = 30_000, ele = 100f)))
        val withoutTime = GpxTrack(null, listOf(point(0, time = null), point(1, time = null)))

        val ids = repo.importTracks(listOf(withTime, withoutTime), nowMs = 99_000) { "Track $it" }

        assertEquals(listOf(40L, 41L), ids)
        val (a, b) = insertedTracks
        assertEquals(listOf("Tour", "IMPORTED", 10_000L, 30_000L, null), listOf(a.name, a.source, a.startMs, a.endMs, a.sessionId))
        assertEquals(Math.round(trackStats(withTime.points).distanceM), a.distanceM)
        assertEquals(3, a.pointCount)
        assertEquals("a track without a name gets the fallback with its position in the file", "Track 2", b.name)
        assertNull("no times in the file, none invented", b.startMs)
        assertNull(b.endMs)
        assertEquals(99_000L, b.createdMs)
        assertEquals("the points of both tracks, sequence from 0 for each", listOf(3, 2), insertedPoints.map { it.size })
        assertEquals(listOf(0, 1, 2), insertedPoints[0].map { it.seq })
        assertEquals(listOf(41L), insertedPoints[1].map { it.trackId }.distinct())
        assertNull("an import never runs", repo.activeTrackId)
    }

    @Test
    fun anImportSkipsATrackWithoutPoints_andCountsOnlyTheOthersForTheFallbackName() = runBlocking {
        repo.importTracks(listOf(GpxTrack("leer", emptyList()), GpxTrack(null, listOf(point(0)))), nowMs = 1) { "Track $it" }

        assertEquals(listOf("Track 1"), insertedTracks.map { it.name })
    }

    // ── Loeschen ──────────────────────────────────────────────────────────────

    @Test
    fun deleteSkipsTheRunningRecording_andGoesInBlocks() = runBlocking {
        val running = repo.create("läuft", TrackSource.RECORDED)
        val chunks = ArrayList<List<Long>>()
        coEvery { dao.deleteByIds(any()) } coAnswers { chunks += firstArg<List<Long>>() }

        repo.delete((1_001L..1_120L).toList() + running + 1_005L)   // doppelt und die laufende dabei

        assertEquals("blocks of at most 50, each id once, the running one never", listOf(50, 50, 20), chunks.map { it.size })
        assertFalse(chunks.flatten().contains(running))
        assertEquals(120, chunks.flatten().toSet().size)
    }

    // ── Liste ─────────────────────────────────────────────────────────────────

    @Test
    fun theListMarksTheRunningRecording_andSkipsRowsOfAnUnknownSource() = runBlocking {
        val running = repo.create("läuft", TrackSource.RECORDED)
        fun row(id: Long, source: String, end: Long?) = TrackEntity(id, "T$id", source, 1, end, null, 0, null, null, 0, 1)
        every { dao.observeAll() } returns flowOf(listOf(row(running, "RECORDED", null), row(2, "IMPORTED", null), row(3, "RECORDED", 9), row(4, "FUTURE", null)))

        val list = repo.tracks.first()

        assertEquals("a row written by a later version is skipped, not a crash", listOf(running, 2L, 3L), list.map { it.id })
        assertEquals(listOf(true, false, false), list.map { it.running })
    }

    // ── Wiederherstellen ──────────────────────────────────────────────────────

    @Test
    fun anUnfinishedRecordingGetsItsEndFromTheLastPoint_andAnEmptyOneIsDropped() = runBlocking {
        fun row(id: Long) = TrackEntity(id, "T$id", "RECORDED", 1_000, null, null, 0, null, null, 0, 1)
        coEvery { dao.unfinishedRecorded() } returns listOf(row(1), row(2))
        val pts = (0..3).map { TrackPointEntity(1, it, 5_000L + 1_000L * it, 50.0 + it * 0.001, 8.0, null, null, null) }
        coEvery { dao.pointsAfter(1, -1, any()) } returns pts
        coEvery { dao.pointsAfter(2, -1, any()) } returns emptyList()

        val recovered = repo.recoverUnfinished()

        assertEquals(1, recovered)
        coVerify { dao.finish(1, 8_000L, any(), null, null) }
        coVerify { dao.deleteByIds(listOf(2L)) }
    }

    @Test
    fun recoveryLeavesTheRunningRecordingAlone() = runBlocking {
        val running = repo.create("läuft", TrackSource.RECORDED)
        coEvery { dao.unfinishedRecorded() } returns listOf(TrackEntity(running, "läuft", "RECORDED", 1, null, null, 0, null, null, 0, 1))

        assertEquals(0, repo.recoverUnfinished())

        coVerify(exactly = 0) { dao.finish(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.deleteByIds(any()) }
    }

    // ── Aufbewahrung ──────────────────────────────────────────────────────────

    @Test
    fun theRetentionNeverTouchesTracks() {
        val source = File(projectRoot, "app/src/main/java/de/adv990de/obd2/data/DataRetention.kt").readText()

        assertFalse("DataRetention must not know the tracks (user data, kept until the user deletes it)", source.contains("track", ignoreCase = true))
    }
}
