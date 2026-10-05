package de.adv990de.obd2

import android.content.Context
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.data.ObdRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Hinweis: Arbeit im `backgroundScope` laeuft mit `runCurrent()` — `advanceUntilIdle()` hoert auf, sobald nur noch
 * Hintergrundaufgaben uebrig sind, und fuehrt sie nicht aus.
 *
 * B1/B4: das eine Verhalten rund um den Vordergrunddienst. Vorher standen Start (zweimal), Stopp (dreimal) und
 * die Trennung des Nutzers in drei Klassen nebeneinander — und keine fing ab, dass `startForegroundService()` ab
 * API 31 im Hintergrund eine Ausnahme wirft.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObdServiceControllerTest {

    private fun makePrefs(useGpsSpeed: Boolean = false): AppPreferences = mockk {
        every { this@mockk.useGpsSpeed } returns flowOf(useGpsSpeed)
    }

    private fun makeRepo(): ObdRepository = mockk { coEvery { disconnectByUser() } just Runs }

    /** [scope] ist der, den die Produktion als `@ApplicationScope` einspeist — hier der des Tests. */
    private fun controller(
        scope: CoroutineScope,
        repo: ObdRepository = makeRepo(),
        context: Context = mockk(relaxed = true),
        log: AppLogRepository = mockk(relaxed = true),
        prefs: AppPreferences = makePrefs(),
    ) = ObdServiceController(repo, prefs, context, log, scope)

    // ── shouldIncludeLocationServiceType (aus dem ConnectionCoordinatorTest hierher gezogen) ──

    @Test
    fun `the location service type is requested only when GPS speed is on AND background location is granted`() {
        assertTrue(shouldIncludeLocationServiceType(useGpsSpeed = true, hasBackgroundLocation = true))
        assertFalse(shouldIncludeLocationServiceType(useGpsSpeed = false, hasBackgroundLocation = true))
        assertFalse(shouldIncludeLocationServiceType(useGpsSpeed = true, hasBackgroundLocation = false))
        assertFalse(shouldIncludeLocationServiceType(useGpsSpeed = false, hasBackgroundLocation = false))
    }

    // ── start ─────────────────────────────────────────────────────────────

    @Test
    fun `start asks the system to start the foreground service`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, context = context)

        c.start()

        verify(exactly = 1) { context.startForegroundService(any()) }
    }

    @Test
    fun `a refused background start is logged and does not throw - the connection is up regardless (B4)`() = runTest {
        // ForegroundServiceStartNotAllowedException (API 31+) ist eine IllegalStateException.
        val context = mockk<Context>(relaxed = true) {
            every { startForegroundService(any()) } throws IllegalStateException("startForegroundService() not allowed")
        }
        val log = mockk<AppLogRepository>(relaxed = true)
        val c = controller(backgroundScope, context = context, log = log)

        c.start()          // wirft nicht

        coVerify { log.log(LogLevel.WARN, match { "IllegalStateException" in it && "not allowed" in it }) }
    }

    @Test
    fun `a missing permission for the service type is logged and does not throw either`() = runTest {
        val context = mockk<Context>(relaxed = true) {
            every { startForegroundService(any()) } throws SecurityException("needs FOREGROUND_SERVICE_LOCATION")
        }
        val log = mockk<AppLogRepository>(relaxed = true)
        val c = controller(backgroundScope, context = context, log = log)

        c.start()

        coVerify { log.log(LogLevel.WARN, match { "SecurityException" in it }) }
    }

    @Test
    fun `an unrelated failure is NOT swallowed - only the two known start refusals are`() = runTest {
        val context = mockk<Context>(relaxed = true) {
            every { startForegroundService(any()) } throws NullPointerException("bug")
        }
        val c = controller(backgroundScope, context = context)

        try {
            c.start()
            fail("ein Programmierfehler darf nicht als „Dienst abgelehnt“ verschwinden")
        } catch (e: NullPointerException) {
            assertEquals("bug", e.message)
        }
    }

    // ── catchingForegroundStart (die gemeinsame Entscheidung) ─────────────

    @Test
    fun `catchingForegroundStart returns null on success and the failure for the two refusals`() {
        assertNull(catchingForegroundStart { })
        val ise = IllegalStateException("a")
        val se = SecurityException("b")
        assertSame(ise, catchingForegroundStart { throw ise })
        assertSame(se, catchingForegroundStart { throw se })
        assertNotNull(catchingForegroundStart { throw IllegalStateException() })
    }

    @Test
    fun `catchingForegroundStart lets everything else through`() {
        try {
            catchingForegroundStart { throw IllegalArgumentException("x") }
            fail("IllegalArgumentException muss durchgereicht werden")
        } catch (e: IllegalArgumentException) {
            assertEquals("x", e.message)
        }
    }

    // ── endSession ────────────────────────────────────────────────────────

    @Test
    fun `endSession disconnects as a USER disconnect and stops the service`() = runTest {
        val repo = makeRepo()
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, repo = repo, context = context)

        c.endSession()

        coVerify(exactly = 1) { repo.disconnectByUser() }
        verify(exactly = 1) { context.stopService(any()) }
    }

    @Test
    fun `endSession stops the service even when the disconnect fails`() = runTest {
        val repo = mockk<ObdRepository> { coEvery { disconnectByUser() } throws IllegalStateException("lock") }
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, repo = repo, context = context)

        try { c.endSession() } catch (_: IllegalStateException) { }

        verify(exactly = 1) { context.stopService(any()) }
    }

    @Test
    fun `a cancelled caller does not interrupt the disconnect - the adapter must really be released`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var finished = false
        val repo = mockk<ObdRepository> { coEvery { disconnectByUser() } coAnswers { gate.await(); finished = true } }
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, repo = repo, context = context)

        val caller = launch { c.endSession() }
        runCurrent()
        caller.cancel()           // z. B. die Coroutine des Dienstes stirbt mit onDestroy
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue("die Trennung lief zu Ende", finished)
    }

    // ── endSessionInBackground ────────────────────────────────────────────

    @Test
    fun `endSessionInBackground runs on the application scope and ends the session`() = runTest {
        val repo = makeRepo()
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, repo = repo, context = context)

        c.endSessionInBackground()
        // Der Aufruf kehrt zurueck, ohne selbst zu trennen: die Arbeit ist im uebergebenen Scope eingereiht (aus
        // Activity.onDestroy darf nichts blockieren).
        coVerify(exactly = 0) { repo.disconnectByUser() }
        runCurrent()

        coVerify(exactly = 1) { repo.disconnectByUser() }
        verify(exactly = 1) { context.stopService(any()) }
    }

    @Test
    fun `endSessionInBackground never crashes the process when the disconnect fails - it logs`() = runTest {
        val repo = mockk<ObdRepository> { coEvery { disconnectByUser() } throws IllegalStateException("boom") }
        val log = mockk<AppLogRepository>(relaxed = true)
        val c = controller(backgroundScope, repo = repo, log = log)

        c.endSessionInBackground()
        runCurrent()      // ein ungefangener Fehler im Scope wuerde hier den Test (und in der App den Prozess) beenden

        coVerify { log.log(LogLevel.WARN, match { "boom" in it }) }
    }

    // ── onServiceStartFailed (vom Dienst selbst gemeldet) ─────────────────

    @Test
    fun `a failed startForeground inside the service is logged from the application scope`() = runTest {
        val log = mockk<AppLogRepository>(relaxed = true)
        val c = controller(backgroundScope, log = log)

        c.onServiceStartFailed(SecurityException("type not allowed"))
        runCurrent()

        coVerify { log.log(LogLevel.WARN, match { "SecurityException" in it && "type not allowed" in it }) }
    }
}
