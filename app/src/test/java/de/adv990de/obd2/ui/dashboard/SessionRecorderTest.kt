package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.crash.CrashReporter
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.obd.model.ValueSource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Aufzeichnung einer Sitzung: Fahrt anlegen, schreiben, beenden — und dass ein Datenbankfehler die Anzeige nie beendet. */
class SessionRecorderTest {

    private val rpm = SensorValue(pid = PidRegistry.RPM, value = 1500f)
    private val speed = SensorValue(pid = PidRegistry.SPEED, value = 50f)
    private val logged = mutableListOf<Pair<LogLevel, String>>()

    private fun recorder(
        sessions: SessionRepository,
        crash: CrashReporter = mockk(relaxed = true),
    ): SessionRecorder = SessionRecorder(sessions, crash) { level, message -> logged += level to message }

    private fun sessions(startResult: Long = 7L): SessionRepository = mockk {
        coEvery { startSession(any()) } returns startResult
        coEvery { writeReadings(any(), any()) } just Runs
        coEvery { endSession(any()) } just Runs
    }

    @Test
    fun `start creates the trip with the VIN and record writes to it`() = runTest {
        val repo = sessions(startResult = 42L)
        val recorder = recorder(repo)

        recorder.start("WP0ZZZ99ZTS392124")
        recorder.record(listOf(rpm, speed))

        coVerify { repo.startSession("WP0ZZZ99ZTS392124") }
        coVerify { repo.writeReadings(42L, listOf(rpm, speed)) }
    }

    @Test
    fun `everything the pipeline hands over is written - no further filter, the GPS speed included`() = runTest {
        val repo = sessions()
        val recorder = recorder(repo)
        val written = slot<List<SensorValue>>()
        coEvery { repo.writeReadings(any(), capture(written)) } just Runs
        // Fahrzeug ohne eigene Geschwindigkeit: die GPS-Geschwindigkeit traegt dieselbe PID-ID 010D (source GPS).
        val gpsSpeed = SensorValue(pid = GPS_SPEED, value = 80f, source = ValueSource.GPS)

        recorder.start(null)
        recorder.record(listOf(rpm, gpsSpeed))

        assertEquals(listOf(rpm, gpsSpeed), written.captured)
    }

    @Test
    fun `a failing start is reported once and the display goes on without a recording`() = runTest {
        val repo = sessions()
        coEvery { repo.startSession(any()) } throws IllegalStateException("disk full")
        val crash = mockk<CrashReporter>(relaxed = true)
        val recorder = recorder(repo, crash)

        recorder.start(null)
        recorder.record(listOf(rpm))   // wirft nicht, schreibt nichts
        recorder.end()

        verify(exactly = 1) { crash.recordException(any()) }
        assertEquals(1, logged.count { it.first == LogLevel.WARN && "disk full" in it.second })
        coVerify(exactly = 0) { repo.writeReadings(any(), any()) }
        coVerify(exactly = 0) { repo.endSession(any()) }
    }

    @Test
    fun `a failing write is reported once per session although it fails every cycle`() = runTest {
        val repo = sessions()
        coEvery { repo.writeReadings(any(), any()) } throws IllegalStateException("database is locked")
        val crash = mockk<CrashReporter>(relaxed = true)
        val recorder = recorder(repo, crash)
        recorder.start(null)

        repeat(3) { recorder.record(listOf(rpm)) }

        verify(exactly = 1) { crash.recordException(any()) }
        assertEquals(1, logged.size)
        coVerify(exactly = 3) { repo.writeReadings(7L, any()) }   // jeder Zyklus versucht es weiter
    }

    @Test
    fun `a cancellation is never swallowed or reported`() = runTest {
        val repo = sessions()
        coEvery { repo.writeReadings(any(), any()) } throws CancellationException("cancelled")
        val crash = mockk<CrashReporter>(relaxed = true)
        val recorder = recorder(repo, crash)
        recorder.start(null)

        var cancelled = false
        try { recorder.record(listOf(rpm)) } catch (_: CancellationException) { cancelled = true }

        assertTrue(cancelled)
        verify(exactly = 0) { crash.recordException(any()) }
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `end closes the trip once it exists and swallows a database error`() = runTest {
        val repo = sessions(startResult = 9L)
        coEvery { repo.endSession(any()) } throws IllegalStateException("closed")
        val recorder = recorder(repo)
        recorder.start(null)

        recorder.end()   // wirft nicht

        coVerify(exactly = 1) { repo.endSession(9L) }
    }

    @Test
    fun `the displayed gear is written like any other value - a derived value on the gear PID`() = runTest {
        val repo = sessions(startResult = 5L)
        val recorder = recorder(repo)
        recorder.start(null)
        val gear = gearSensorValue(GearReading(gear = 3, isNeutral = false), timestamp = 2000L)

        recorder.record(listOf(gear))

        coVerify { repo.writeReadings(5L, listOf(gear)) }
    }

    @Test
    fun `after end nothing more is written - a value that arrives late is dropped, not added to a closed trip`() = runTest {
        val repo = sessions(startResult = 9L)
        val recorder = recorder(repo)
        recorder.start(null)
        recorder.record(listOf(rpm))

        recorder.end()
        recorder.record(listOf(gearSensorValue(GearReading.Unknown, timestamp = 3000L)))
        recorder.end()   // zweimal enden schadet nicht und schliesst die Fahrt nicht zweimal

        coVerify(exactly = 1) { repo.writeReadings(any(), any()) }
        coVerify(exactly = 1) { repo.endSession(9L) }
    }

    @Test
    fun `without start there is nothing to record or end`() = runTest {
        val repo = sessions()
        val recorder = recorder(repo)

        recorder.record(listOf(rpm))
        recorder.end()

        coVerify(exactly = 0) { repo.writeReadings(any(), any()) }
        coVerify(exactly = 0) { repo.endSession(any()) }
    }
}
