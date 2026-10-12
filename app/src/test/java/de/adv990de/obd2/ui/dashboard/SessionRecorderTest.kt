package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.crash.CrashReporter
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.data.SessionRepository
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
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
        supported: Set<String>? = null,
        logOnlyDiscovered: Boolean = false,
        loggedPidIds: Set<String> = setOf(PidRegistry.RPM.id, PidRegistry.SPEED.id),
    ): SessionRecorder {
        val obd = mockk<ObdRepository> { every { supportedPidIds } returns MutableStateFlow(supported) }
        return SessionRecorder(sessions, obd, crash, { level, message -> logged += level to message }, logOnlyDiscovered, loggedPidIds)
    }

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
    fun `only the PIDs chosen for recording are written`() = runTest {
        val repo = sessions()
        val recorder = recorder(repo, loggedPidIds = setOf(PidRegistry.SPEED.id))
        val written = slot<List<SensorValue>>()
        coEvery { repo.writeReadings(any(), capture(written)) } just Runs

        recorder.start(null)
        recorder.record(listOf(rpm, speed))

        assertEquals(listOf(speed), written.captured)
    }

    @Test
    fun `with only discovered PIDs on, a PID the ECU does not report is not written - and everything is written while the set is unknown`() = runTest {
        val onlySpeed = setOf(PidRegistry.SPEED.id)
        val repoKnown = sessions()
        val written = slot<List<SensorValue>>()
        coEvery { repoKnown.writeReadings(any(), capture(written)) } just Runs
        recorder(repoKnown, supported = onlySpeed, logOnlyDiscovered = true).also {
            it.start(null)
            it.record(listOf(rpm, speed))
        }
        assertEquals("nur das gemeldete PID", listOf(speed), written.captured)

        val repoUnknown = sessions()
        val writtenAll = slot<List<SensorValue>>()
        coEvery { repoUnknown.writeReadings(any(), capture(writtenAll)) } just Runs
        recorder(repoUnknown, supported = null, logOnlyDiscovered = true).also {
            it.start(null)
            it.record(listOf(rpm, speed))
        }
        assertEquals("ohne Discovery wird nichts verworfen", listOf(rpm, speed), writtenAll.captured)
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
    fun `without start there is nothing to record or end`() = runTest {
        val repo = sessions()
        val recorder = recorder(repo)

        recorder.record(listOf(rpm))
        recorder.end()

        coVerify(exactly = 0) { repo.writeReadings(any(), any()) }
        coVerify(exactly = 0) { repo.endSession(any()) }
    }
}
