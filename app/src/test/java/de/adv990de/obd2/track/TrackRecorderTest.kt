package de.adv990de.obd2.track

import de.adv990de.obd2.ObdServiceController
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.TrackRepository
import de.adv990de.obd2.location.LocationSpeedProvider
import de.adv990de.obd2.location.TrackFix
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** 2026-11-17T10:00:00Z — alle Koordinaten dieses Tests sind erfunden. */
private const val EPOCH = 1_794_909_600_000L

private const val BASE_LAT = 51.2345
private const val BASE_LON = 9.8765

/** 0,0001 Grad Breite sind rund 11,1 m: weiter als der Mindestabstand des Filters (5 m). */
private const val STEP_DEG = 0.0001

private fun latOf(i: Int) = BASE_LAT + i * STEP_DEG

/** Der i-te Fix: eine Sekunde nach dem vorigen, 11 m weiter, gute Genauigkeit, 30 km/h. */
private fun fix(i: Int, accuracyM: Float? = 5f, speedKmh: Float? = 30f) = TrackFix(
    timeMs = EPOCH + 1_000L * i, elapsedMs = 1_000L * i, lat = latOf(i), lon = BASE_LON,
    altitudeM = 100f + i, accuracyM = accuracyM, speedKmh = speedKmh,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TrackRecorderTest {

    private class FakeLocation : LocationSpeedProvider {
        var permission = true
        var canRegister = true
        var registrations = 0
        var stops = 0
        var requestedIntervalMs: Long? = null
        private var listener: ((TrackFix?) -> Unit)? = null

        override fun hasPermission() = permission
        override fun startUpdates(onSpeed: (Float?) -> Unit): (() -> Unit)? = null
        override fun startPositionUpdates(minIntervalMs: Long, onFix: (TrackFix?) -> Unit): (() -> Unit)? {
            registrations++
            requestedIntervalMs = minIntervalMs
            if (!canRegister) return null
            listener = onFix
            return {
                stops++
                listener = null
            }
        }

        fun emit(fix: TrackFix?) {
            listener?.invoke(fix)
        }
    }

    /** Alles, was ein Test von Repository, Protokoll und Provider wissen will, an einer Stelle. */
    private class Harness(val test: TestScope) {
        val location = FakeLocation()
        val repository = mockk<TrackRepository>()
        val appLog = mockk<AppLogRepository>()
        val controller = mockk<ObdServiceController>()

        val calls = mutableListOf<String>()
        val appended = mutableListOf<List<TrackPoint>>()
        val logs = mutableListOf<Pair<LogLevel, String>>()
        var createCalls = 0
        var createdName: String? = null
        var createdStartMs: Long? = null
        var failAppend = false
        var failPoints = false
        var failFinish = false
        var appendAttempts = 0
        var recovered = 0
        var failRecover = false
        var failCreate = false
        var finishCalls = 0
        var finishedEndMs: Long? = null
        var finishedStats: TrackStats? = null

        init {
            coEvery { repository.create(any(), any(), any(), any(), any()) } coAnswers {
                createCalls++
                if (failCreate) throw IllegalStateException("A recording is already running")
                createdName = firstArg()
                createdStartMs = arg(3)
                calls += "create"
                TRACK_ID
            }
            coEvery { repository.appendPoints(any(), any()) } coAnswers {
                appendAttempts++
                if (failAppend) throw IllegalStateException("disk full")
                appended += secondArg<List<TrackPoint>>()
                calls += "append"
            }
            coEvery { repository.points(any()) } coAnswers {
                if (failPoints) throw IllegalStateException("cannot read")
                appended.flatten()
            }
            coEvery { repository.finish(any(), any(), any()) } coAnswers {
                finishCalls++
                if (failFinish) throw IllegalStateException("cannot write")
                finishedEndMs = secondArg()
                finishedStats = thirdArg()
                calls += "finish"
            }
            coEvery { controller.setTrack(any()) } coAnswers { calls += "setTrack(${firstArg<Boolean>()})" }
            coEvery { repository.recoverUnfinished() } coAnswers {
                if (failRecover) throw IllegalStateException("db locked")
                recovered
            }
            coEvery { appLog.log(any(), any()) } coAnswers { logs += firstArg<LogLevel>() to secondArg<String>() }
        }

        val recorder = TrackRecorder(location, repository, appLog, controller, test.backgroundScope).apply {
            elapsedMs = { test.testScheduler.currentTime }
            wallMs = { EPOCH + test.testScheduler.currentTime }
            zone = { ZoneOffset.UTC }
        }

        /** Der i-te Fix trifft eine Sekunde nach dem vorigen ein (die Zeit des Tests laeuft mit). */
        fun emit(i: Int, accuracyM: Float? = 5f, speedKmh: Float? = 30f) {
            test.testScheduler.advanceTimeBy(1_000)
            location.emit(fix(i, accuracyM, speedKmh))
            test.testScheduler.runCurrent()
        }

        fun advance(ms: Long) {
            test.testScheduler.advanceTimeBy(ms)
            test.testScheduler.runCurrent()
        }

        val recording get() = recorder.state.value as RecordingState.Recording
    }

    private fun recorderTest(body: suspend Harness.() -> Unit) = runTest { Harness(this).body() }

    private companion object {
        const val TRACK_ID = 7L
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    @Test
    fun `without the permission nothing is created and nothing is registered`() = recorderTest {
        location.permission = false

        val result = recorder.start("Tour")

        assertEquals(StartResult.NoPermission, result)
        assertEquals(0, createCalls)
        assertEquals(0, location.registrations)
        assertEquals(RecordingState.Idle, recorder.state.value)
    }

    @Test
    fun `if the system offers no position source nothing is created`() = recorderTest {
        location.canRegister = false

        val result = recorder.start("Tour")

        assertEquals(StartResult.NoLocationService, result)
        assertEquals("the track is only created once the source is registered", 0, createCalls)
        assertEquals(RecordingState.Idle, recorder.state.value)
    }

    @Test
    fun `a started recording creates its track, registers once and shows the start`() = recorderTest {
        val result = recorder.start("  Feierabend ")

        assertEquals(StartResult.Started, result)
        assertEquals("Feierabend", createdName)
        assertEquals(EPOCH, createdStartMs)
        assertEquals(1, location.registrations)
        assertEquals(1_000L, location.requestedIntervalMs)
        assertEquals(
            RecordingState.Recording(trackId = TRACK_ID, points = 0, distanceM = 0.0, startedMs = EPOCH, signal = false, writeFailed = false),
            recorder.state.value,
        )
    }

    @Test
    fun `without a name the track is called after the start time, whatever the language`() = recorderTest {
        recorder.start(null)

        assertEquals("2026-11-17 10:00", createdName)
    }

    @Test
    fun `a blank name counts as no name`() = recorderTest {
        recorder.start("   ")

        assertEquals("2026-11-17 10:00", createdName)
    }

    @Test
    fun `the default name follows the zone of the device`() {
        assertEquals("2026-11-17 11:00", defaultTrackName(EPOCH, ZoneOffset.ofHours(1)))
        assertEquals("2026-11-17 10:00", defaultTrackName(EPOCH, ZoneOffset.UTC))
        assertEquals("24-hour clock, fixed digits", "2026-11-17 14:05", defaultTrackName(EPOCH + (4 * 60 + 5) * 60_000L, ZoneOffset.UTC))
    }

    @Test
    fun `a second start while recording does nothing`() = recorderTest {
        recorder.start("Eins")

        val second = recorder.start("Zwei")

        assertEquals(StartResult.AlreadyRunning, second)
        assertEquals(1, createCalls)
        assertEquals(1, location.registrations)
        assertEquals("Eins", createdName)
    }

    @Test
    fun `a failing create gives the source back and stays idle`() = recorderTest {
        failCreate = true

        val result = recorder.start("Tour")

        assertEquals(StartResult.Failed, result)
        assertEquals("the registration is undone", 1, location.stops)
        assertEquals(RecordingState.Idle, recorder.state.value)
    }

    // ── Fixe und Filter ───────────────────────────────────────────────────────

    @Test
    fun `a fix with a signal is shown as signal and counted`() = recorderTest {
        recorder.start("Tour")

        emit(1)

        assertTrue(recording.signal)
        assertEquals(1, recording.points)
    }

    @Test
    fun `a missing source reports no signal and a fix brings it back`() = recorderTest {
        recorder.start("Tour")
        emit(1)

        location.emit(null)
        advance(0)
        assertFalse("GPS switched off: no signal", recording.signal)

        emit(2)
        assertTrue("and back again", recording.signal)
        assertEquals("the recording went on", 2, recording.points)
    }

    @Test
    fun `the filter decides what becomes a point`() = recorderTest {
        recorder.start("Tour")

        emit(1, accuracyM = 100f)
        assertEquals("a poor fix is dropped", 0, recording.points)

        emit(2)
        assertEquals(1, recording.points)

        emit(3, speedKmh = 0.5f)
        assertEquals("standing: no point", 1, recording.points)

        emit(4)
        assertEquals(2, recording.points)
    }

    @Test
    fun `the distance adds up between accepted points`() = recorderTest {
        recorder.start("Tour")
        emit(1)
        emit(2)
        emit(3)

        val expected = distanceM(
            TrackPoint(null, latOf(1), BASE_LON, null, null, null),
            TrackPoint(null, latOf(3), BASE_LON, null, null, null),
        )
        assertEquals(expected, recording.distanceM, 0.5)
    }

    // ── Schreiben ─────────────────────────────────────────────────────────────

    @Test
    fun `nine points are held back, the tenth writes a block`() = recorderTest {
        recorder.start("Tour")

        for (i in 1..9) emit(i)
        assertTrue("nothing written yet", appended.isEmpty())

        emit(10)
        assertEquals(1, appended.size)
        assertEquals((1..10).map { latOf(it) }, appended.single().map { it.lat })
    }

    @Test
    fun `a point is written after fifteen seconds even if the block is not full`() = recorderTest {
        recorder.start("Tour")
        emit(1)

        advance(13_999)
        assertTrue("one millisecond short of 15 s: not yet", appended.isEmpty())

        advance(1)
        assertEquals(1, appended.size)
        assertEquals(1, appended.single().size)
    }

    @Test
    fun `a fix that arrives at the very moment of the timer is not lost`() = recorderTest {
        recorder.start("Tour")
        emit(1)
        advance(13_999)

        test.testScheduler.advanceTimeBy(1) // t = 15 000: Frist und Fix treffen im selben Augenblick ein
        location.emit(fix(2))
        test.testScheduler.runCurrent()

        assertEquals(2, recording.points)
        assertEquals(listOf(latOf(1), latOf(2)), appended.flatten().map { it.lat })
    }

    @Test
    fun `an idle buffer writes nothing on the timer`() = recorderTest {
        recorder.start("Tour")

        advance(60_000)

        assertTrue(appended.isEmpty())
        assertEquals(0, finishCalls)
    }

    @Test
    fun `the points keep time, height and speed of their fix`() = recorderTest {
        recorder.start("Tour")
        emit(1)
        recorder.stop()

        val p = appended.single().single()
        assertEquals(EPOCH + 1_000L, p.timeMs)
        assertEquals(101f, p.ele)
        assertEquals(5f, p.accuracyM)
        assertEquals(30f, p.speedKmh)
        assertEquals(BASE_LON, p.lon, 0.0)
    }

    // ── Stopp ─────────────────────────────────────────────────────────────────

    @Test
    fun `stopping writes what is left, finishes the track and gives the source back`() = recorderTest {
        recorder.start("Tour")
        emit(1)
        emit(2)
        emit(3)

        recorder.stop()

        assertEquals(listOf("create", "setTrack(true)", "append", "finish", "setTrack(false)"), calls)
        assertEquals(3, appended.single().size)
        assertEquals(1, finishCalls)
        assertEquals(EPOCH + 3_000L, finishedEndMs)
        assertEquals(trackStats(appended.flatten()), finishedStats)
        assertEquals(1, location.stops)
        assertEquals(RecordingState.Idle, recorder.state.value)
    }

    @Test
    fun `stopping without a recording does nothing`() = recorderTest {
        recorder.stop()

        assertEquals(0, finishCalls)
        assertEquals(0, location.stops)
        assertTrue(logs.isEmpty())
        assertEquals(RecordingState.Idle, recorder.state.value)
    }

    @Test
    fun `stopping twice finishes once`() = recorderTest {
        recorder.start("Tour")
        emit(1)

        recorder.stop()
        recorder.stop()

        assertEquals(1, finishCalls)
    }

    @Test
    fun `a stop that is cancelled on the way still finishes the track`() = recorderTest {
        recorder.start("Tour")
        emit(1)
        val gate = CompletableDeferred<Unit>()
        var done = false
        coEvery { repository.finish(any(), any(), any()) } coAnswers {
            gate.await()
            done = true
        }

        val job = test.launch { recorder.stop() }
        test.testScheduler.runCurrent()
        job.cancel()
        gate.complete(Unit)
        test.testScheduler.advanceUntilIdle()

        assertTrue("the track is finished although the caller is gone", done)
        assertEquals(RecordingState.Idle, recorder.state.value)
    }

    @Test
    fun `a recording after a recording starts with a fresh filter`() = recorderTest {
        recorder.start("Eins")
        emit(1)
        recorder.stop()

        recorder.start("Zwei")
        location.emit(fix(1))
        test.testScheduler.runCurrent()

        assertEquals("the first fix counts again, even at the same place", 1, recording.points)
        assertEquals(2, createCalls)
    }

    // ── Vordergrunddienst und Wiederherstellung ───────────────────────────────

    @Test
    fun `the service is asked for once the track exists, and released after it is finished`() = recorderTest {
        recorder.start("Tour")
        assertEquals(listOf("create", "setTrack(true)"), calls)

        recorder.stop()

        assertEquals(listOf("create", "setTrack(true)", "finish", "setTrack(false)"), calls)
    }

    @Test
    fun `a start that does not happen never asks for the service`() = recorderTest {
        location.permission = false
        recorder.start("Tour")
        location.permission = true
        location.canRegister = false
        recorder.start("Tour")
        location.canRegister = true
        failCreate = true
        recorder.start("Tour")

        assertTrue("no setTrack at all: $calls", calls.none { it.startsWith("setTrack") })
    }

    @Test
    fun `a second start does not ask for the service again`() = recorderTest {
        recorder.start("Eins")
        recorder.start("Zwei")

        assertEquals(1, calls.count { it == "setTrack(true)" })
    }

    @Test
    fun `the service is released even if finishing the track fails`() = recorderTest {
        recorder.start("Tour")
        failFinish = true

        recorder.stop()

        assertEquals("setTrack(false)", calls.last())
    }

    @Test
    fun `recovering at start reports how many tracks were closed`() = recorderTest {
        recovered = 2

        recorder.recoverAtStart()

        assertTrue(logs.any { it.first == LogLevel.INFO && it.second.contains("2") })
    }

    @Test
    fun `recovering at start with nothing to close says nothing`() = recorderTest {
        recovered = 0

        recorder.recoverAtStart()

        assertTrue(logs.isEmpty())
    }

    @Test
    fun `a failing recovery is logged and does not throw`() = recorderTest {
        failRecover = true

        recorder.recoverAtStart()

        assertTrue(logs.any { it.first == LogLevel.WARN && it.second.contains("IllegalStateException") })
    }

    // ── Fehler beim Schreiben ─────────────────────────────────────────────────

    @Test
    fun `a failed write is reported once, shown, and the recording goes on`() = recorderTest {
        failAppend = true
        recorder.start("Tour")

        for (i in 1..10) emit(i)
        assertTrue(recording.writeFailed)
        assertEquals("the display keeps counting", 10, recording.points)
        assertEquals(1, logs.count { it.first == LogLevel.WARN })

        advance(15_000)
        advance(15_000)
        assertEquals("still failing, still one line", 1, logs.count { it.first == LogLevel.WARN })
    }

    @Test
    fun `after a failure the next try waits for the time, not for the next point`() = recorderTest {
        failAppend = true
        recorder.start("Tour")
        for (i in 1..10) emit(i)
        assertEquals(1, appendAttempts)

        for (i in 11..14) emit(i)

        assertEquals("four more points, still one attempt", 1, appendAttempts)
    }

    @Test
    fun `after a failure the points are tried again and nothing is lost or doubled`() = recorderTest {
        failAppend = true
        recorder.start("Tour")
        for (i in 1..10) emit(i)
        emit(11)
        emit(12)
        assertTrue(appended.isEmpty())

        failAppend = false
        advance(15_000)

        assertFalse(recording.writeFailed)
        assertEquals((1..12).map { latOf(it) }, appended.flatten().map { it.lat })
        recorder.stop()
        assertEquals("nothing written twice", 12, appended.flatten().size)
    }

    @Test
    fun `a write that keeps failing does not grow without limit`() = recorderTest {
        recorder.maxPendingPoints = 20
        failAppend = true
        recorder.start("Tour")
        for (i in 1..30) emit(i)

        failAppend = false
        advance(15_000)

        assertEquals("the oldest points were dropped", (11..30).map { latOf(it) }, appended.single().map { it.lat })
    }

    @Test
    fun `stopping while writes keep failing still finishes the track and says how much was lost`() = recorderTest {
        failAppend = true
        recorder.start("Tour")
        emit(1)
        emit(2)

        recorder.stop()

        assertEquals(1, finishCalls)
        assertEquals(RecordingState.Idle, recorder.state.value)
        assertTrue(logs.any { it.first == LogLevel.WARN && it.second.contains("2 unsaved points") })
    }

    @Test
    fun `if the points cannot be read back the track is finished with the counted distance`() = recorderTest {
        recorder.start("Tour")
        emit(1)
        emit(2)
        failPoints = true
        val counted = recording.distanceM

        recorder.stop()

        assertEquals(1, finishCalls)
        assertEquals(counted, finishedStats!!.distanceM, 0.0)
        assertEquals("no height without the points", null, finishedStats!!.ascentM)
        assertEquals(RecordingState.Idle, recorder.state.value)
    }

    @Test
    fun `if finishing fails the recorder is free again and says so`() = recorderTest {
        recorder.start("Tour")
        emit(1)
        failFinish = true

        recorder.stop()

        assertEquals(RecordingState.Idle, recorder.state.value)
        assertTrue(logs.any { it.first == LogLevel.WARN && it.second.contains("finishing the track failed") })
        failFinish = false
        assertEquals("a new recording can start", StartResult.Started, recorder.start("Neu"))
    }

    @Test
    fun `the log never carries a position`() = recorderTest {
        failAppend = true
        recorder.start("Tour")
        for (i in 1..10) emit(i)
        failAppend = false
        recorder.stop()

        assertTrue("some lines were written", logs.isNotEmpty())
        logs.forEach { (_, line) ->
            assertFalse("no latitude in: $line", line.contains("51.2") || line.contains("51,2"))
            assertFalse("no longitude in: $line", line.contains("9.87") || line.contains("9,87"))
        }
    }
}
