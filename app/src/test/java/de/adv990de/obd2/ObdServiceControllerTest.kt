package de.adv990de.obd2

import android.content.Context
import android.content.pm.PackageManager
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.After
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

    private fun makeRepo(): ObdRepository = mockk { coEvery { disconnectByUser() } just Runs }

    /** [scope] ist der, den die Produktion als `@ApplicationScope` einspeist — hier der des Tests. */
    private fun controller(
        scope: CoroutineScope,
        repo: ObdRepository = makeRepo(),
        context: Context = mockk(relaxed = true),
        log: AppLogRepository = mockk(relaxed = true),
        prefs: AppPreferences = fakeAppPreferences(),
    ) = ObdServiceController(repo, prefs, context, log, scope)

    // ── shouldIncludeLocationServiceType (aus dem ConnectionCoordinatorTest hierher gezogen) ──

    @Test
    fun `the location service type is requested only when GPS speed is on AND a location permission is granted`() {
        // Der Typ braucht den normalen Standort (ab API 34 wirft startForeground() sonst eine SecurityException) — nicht den Hintergrund-Standort:
        // ein bei sichtbarer App gestarteter Dienst dieses Typs behaelt den Vordergrund-Zugriff (am Pixel 8 Pro gemessen, s. CLAUDE.md).
        assertTrue(shouldIncludeLocationServiceType(useGpsSpeed = true, hasLocationPermission = true))
        assertFalse(shouldIncludeLocationServiceType(useGpsSpeed = false, hasLocationPermission = true))
        assertFalse(shouldIncludeLocationServiceType(useGpsSpeed = true, hasLocationPermission = false))
        assertFalse(shouldIncludeLocationServiceType(useGpsSpeed = false, hasLocationPermission = false))
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

    // ── endSessionSafely (UID-20) ─────────────────────────────────────────

    @Test
    fun `endSessionSafely logs a failed disconnect instead of throwing - and still stops the service`() = runTest {
        val repo = mockk<ObdRepository> { coEvery { disconnectByUser() } throws IllegalStateException("boom") }
        val log = mockk<AppLogRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, repo = repo, context = context, log = log)

        c.endSessionSafely()

        coVerify { log.log(LogLevel.WARN, match { "boom" in it }) }
        verify(exactly = 1) { context.stopService(any()) }
    }

    @Test
    fun `endSessionSafely passes a cancellation on - it is not a failure to log`() = runTest {
        val repo = mockk<ObdRepository> { coEvery { disconnectByUser() } throws kotlinx.coroutines.CancellationException("cancelled") }
        val log = mockk<AppLogRepository>(relaxed = true)
        val c = controller(backgroundScope, repo = repo, log = log)

        try {
            c.endSessionSafely()
            fail("CancellationException erwartet")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // erwartet
        }
        coVerify(exactly = 0) { log.log(LogLevel.WARN, any()) }
    }

    @Test
    fun `endSessionSafely ends the session as before when nothing fails`() = runTest {
        val repo = makeRepo()
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, repo = repo, context = context)

        c.endSessionSafely()

        coVerify(exactly = 1) { repo.disconnectByUser() }
        verify(exactly = 1) { context.stopService(any()) }
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

    // ── zwei Bedarfe, ein Dienst (F8): OBD-Sitzung und Streckenaufzeichnung ─────────────────────────

    @Test
    fun `there is one controller per process - the demands live in it`() {
        assertTrue(
            "ohne @Singleton haetten Koordinator, Einstellungen und Aufzeichnung je eigene Bedarfe",
            ObdServiceController::class.java.isAnnotationPresent(javax.inject.Singleton::class.java),
        )
    }

    @Test
    fun `the track alone starts the service`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, context = context)

        c.setTrack(true)

        verify(exactly = 1) { context.startForegroundService(any()) }
        assertEquals(ForegroundDemand(obd = false, track = true), c.currentDemand)
    }

    @Test
    fun `ending the OBD session leaves the service up while a track is recording`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, context = context)
        c.setObd(true)
        c.setTrack(true)

        c.endSession()

        verify(exactly = 0) { context.stopService(any()) }
        assertEquals(ForegroundDemand(obd = false, track = true), c.currentDemand)
        verify(exactly = 3) { context.startForegroundService(any()) }   // OBD, plus Track, nach dem Trennen noch einmal fuer die Track-Benachrichtigung
    }

    @Test
    fun `ending the track leaves the service up while the adapter is connected`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, context = context)
        c.setObd(true)
        c.setTrack(true)

        c.setTrack(false)

        verify(exactly = 0) { context.stopService(any()) }
        assertEquals(ForegroundDemand(obd = true, track = false), c.currentDemand)
    }

    @Test
    fun `the service stops only when both demands are off`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, context = context)
        c.setObd(true)
        c.setTrack(true)

        c.setObd(false)
        verify(exactly = 0) { context.stopService(any()) }
        c.setTrack(false)

        verify(exactly = 1) { context.stopService(any()) }
        assertEquals(ForegroundDemand(), c.currentDemand)
    }

    @Test
    fun `a track that ends first and then the adapter - the service stops with the last one`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, context = context)
        c.setObd(true)
        c.setTrack(true)

        c.setTrack(false)
        c.endSession()

        verify(exactly = 1) { context.stopService(any()) }
    }

    @Test
    fun `the thin start and stop wrappers are the OBD demand`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, context = context)

        c.start()
        assertEquals(ForegroundDemand(obd = true, track = false), c.currentDemand)
        c.stop()

        assertEquals(ForegroundDemand(), c.currentDemand)
        verify(exactly = 1) { context.stopService(any()) }
    }

    @Test
    fun `a stop whose caller is cancelled on the way still switches the demand off and stops the service`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = mockk<ObdRepository> { coEvery { disconnectByUser() } coAnswers { gate.await() } }
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, repo = repo, context = context)
        c.setObd(true)

        val caller = launch { c.endSession() }
        runCurrent()
        caller.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        verify(exactly = 1) { context.stopService(any()) }
        assertEquals(ForegroundDemand(), c.currentDemand)
    }

    @Test
    fun `two changes at the same moment are applied one after the other`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val context = mockk<Context>(relaxed = true)
        // Das erste Lesen der Einstellung haengt: waehrenddessen darf die zweite Aenderung den Bedarf nicht schon umschreiben.
        val prefs = mockk<AppPreferences>(relaxed = true) {
            every { useGpsSpeed } returns kotlinx.coroutines.flow.flow { gate.await(); emit(true) }
        }
        val c = controller(backgroundScope, context = context, prefs = prefs)

        val first = launch { c.setObd(true) }
        runCurrent()
        val second = launch { c.setTrack(true) }
        runCurrent()
        assertEquals("the second change waits its turn", ForegroundDemand(obd = true, track = false), c.currentDemand)

        gate.complete(Unit)
        advanceUntilIdle()
        first.join()
        second.join()

        assertEquals(ForegroundDemand(obd = true, track = true), c.currentDemand)
        verify(exactly = 2) { context.startForegroundService(any()) }
    }

    @Test
    fun `a stop that has to wait for the lock is not lost when its caller is cancelled meanwhile`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var hang = false
        val context = mockk<Context>(relaxed = true)
        val prefs = mockk<AppPreferences>(relaxed = true) {
            every { useGpsSpeed } returns kotlinx.coroutines.flow.flow { if (hang) gate.await(); emit(true) }
        }
        val c = controller(backgroundScope, context = context, prefs = prefs)
        c.setObd(true)

        // Eine andere Aenderung haelt die Sperre, waehrend die Sitzung beendet wird.
        hang = true
        val holder = launch { c.setTrack(true) }
        runCurrent()
        val caller = launch { c.endSession() }
        runCurrent()
        caller.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        holder.join()

        assertEquals("the OBD demand is off although the caller was cancelled while waiting", ForegroundDemand(obd = false, track = true), c.currentDemand)
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

    // ── Dienst-Typ bei laufender Verbindung ────────────────────────────────
    //
    // Der Typ wird beim Start des Dienstes gewaehlt (`start()`). Schaltet der Nutzer die GPS-Geschwindigkeit ein oder erteilt er
    // "Immer", waehrend die Verbindung steht, behielt der Dienst bisher den alten Typ bis zur naechsten Verbindung. Jetzt ruft
    // `refreshServiceType()` den Start erneut, wenn der laufende Dienst einen anderen Typ hat als der jetzt gewuenschte.

    @After
    fun resetRunningServiceType() {
        ObdForegroundService.runningIncludesLocation = null
    }

    /** Eine laufende OBD-Sitzung mit dem Dienst im Zustand [includesLocation] (so, wie ihn `onStartCommand` meldet); die Aufrufe des Starts selbst zaehlen im Test nicht mit. */
    private suspend fun ObdServiceController.connected(context: Context, includesLocation: Boolean?) {
        setObd(true)
        io.mockk.clearMocks(context, answers = false)
        ObdForegroundService.runningIncludesLocation = includesLocation
    }

    @Test
    fun `a refresh without any demand never starts the service`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, context = context)
        c.setObd(true)
        c.setObd(false)
        io.mockk.clearMocks(context, answers = false)
        ObdForegroundService.runningIncludesLocation = true   // ein Rest vom vorigen Lauf: der Typ passt nicht zum "kein Bedarf"

        c.refreshServiceType(ForegroundStartMode.TYPED)

        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `a refresh is due only when a running service has a different type than the one wanted now`() {
        val typed = ForegroundStartMode.TYPED
        // laeuft ohne Standort-Typ, jetzt waere er gewuenscht -> neu starten
        assertTrue(shouldRefreshServiceType(runningIncludesLocation = false, wantsLocation = true, startMode = typed))
        // laeuft mit Standort-Typ, GPS ist inzwischen aus -> neu starten (der Typ darf wieder entfallen)
        assertTrue(shouldRefreshServiceType(runningIncludesLocation = true, wantsLocation = false, startMode = typed))
        // gleich -> nichts tun
        assertFalse(shouldRefreshServiceType(runningIncludesLocation = false, wantsLocation = false, startMode = typed))
        assertFalse(shouldRefreshServiceType(runningIncludesLocation = true, wantsLocation = true, startMode = typed))
        // laeuft nicht (abgelehnt, beendet): ein Refresh startet ihn nicht von sich aus
        assertFalse(shouldRefreshServiceType(runningIncludesLocation = null, wantsLocation = true, startMode = typed))
        assertFalse(shouldRefreshServiceType(runningIncludesLocation = null, wantsLocation = false, startMode = typed))
        // vor API 34 gilt der Typ aus dem Manifest, startForeground() kennt keinen Typ
        assertFalse(shouldRefreshServiceType(runningIncludesLocation = false, wantsLocation = true, startMode = ForegroundStartMode.LEGACY))
    }

    @Test
    fun `refreshServiceType restarts a running service whose type no longer matches`() = runTest {
        val context = mockk<Context>(relaxed = true)
        // GPS-Geschwindigkeit an; der relaxed Mock-Context meldet jede Berechtigung als erteilt
        val c = controller(backgroundScope, context = context, prefs = fakeAppPreferences(useGpsSpeed = true))
        c.connected(context, includesLocation = false)

        c.refreshServiceType(ForegroundStartMode.TYPED)

        verify(exactly = 1) { context.startForegroundService(any()) }
    }

    @Test
    fun `refreshServiceType does nothing when the running service already has the wanted type`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, context = context, prefs = fakeAppPreferences(useGpsSpeed = true))
        c.connected(context, includesLocation = true)

        c.refreshServiceType(ForegroundStartMode.TYPED)

        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `refreshServiceType never starts a service that is not running`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, context = context, prefs = fakeAppPreferences(useGpsSpeed = true))
        c.connected(context, includesLocation = null)

        c.refreshServiceType(ForegroundStartMode.TYPED)

        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `refreshServiceType drops the location type again when GPS speed is switched off`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, context = context, prefs = fakeAppPreferences(useGpsSpeed = false))
        c.connected(context, includesLocation = true)

        c.refreshServiceType(ForegroundStartMode.TYPED)

        verify(exactly = 1) { context.startForegroundService(any()) }
    }

    @Test
    fun `refreshServiceType gives a recording the location type even when GPS speed is off`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, context = context, prefs = fakeAppPreferences(useGpsSpeed = false))
        c.setTrack(true)
        io.mockk.clearMocks(context, answers = false)
        ObdForegroundService.runningIncludesLocation = false   // ein Dienst, der den Typ verloren hat

        c.refreshServiceType(ForegroundStartMode.TYPED)

        verify(exactly = 1) { context.startForegroundService(any()) }
    }

    private fun denyLocation(context: Context) {
        every { context.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, any(), any()) } returns PackageManager.PERMISSION_DENIED
        every { context.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, any(), any()) } returns PackageManager.PERMISSION_DENIED
    }

    @Test
    fun `refreshServiceType drops the location type when GPS speed is on but no location permission is granted`() = runTest {
        // Ohne Standort wirft startForeground() mit dem Typ `location` ab API 34 eine SecurityException: der Dienst darf ihn dann nicht tragen.
        val context = mockk<Context>(relaxed = true)
        denyLocation(context)
        val c = controller(backgroundScope, context = context, prefs = fakeAppPreferences(useGpsSpeed = true))
        c.connected(context, includesLocation = true)

        c.refreshServiceType(ForegroundStartMode.TYPED)

        verify(exactly = 1) { context.startForegroundService(any()) }
    }

    @Test
    fun `refreshServiceType does not give a service the location type while no location permission is granted`() = runTest {
        val context = mockk<Context>(relaxed = true)
        denyLocation(context)
        val c = controller(backgroundScope, context = context, prefs = fakeAppPreferences(useGpsSpeed = true))
        c.connected(context, includesLocation = false)

        c.refreshServiceType(ForegroundStartMode.TYPED)

        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `refreshServiceType leaves the service alone before API 34`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, context = context, prefs = fakeAppPreferences(useGpsSpeed = true))
        c.connected(context, includesLocation = false)

        c.refreshServiceType(ForegroundStartMode.LEGACY)

        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `refreshServiceTypeInBackground never crashes the process - a failure goes to the log`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val log = mockk<AppLogRepository>(relaxed = true)
        val c = controller(backgroundScope, context = context, log = log, prefs = fakeAppPreferences(useGpsSpeed = true))
        c.connected(context, includesLocation = false)
        every { context.startForegroundService(any()) } throws NullPointerException("bug")

        c.refreshServiceTypeInBackground(ForegroundStartMode.TYPED)
        runCurrent()   // ein ungefangener Fehler im Scope wuerde hier den Test (und in der App den Prozess) beenden

        coVerify { log.log(LogLevel.WARN, match { "NullPointerException" in it }) }
    }

    @Test
    fun `keepServiceTypeCurrent refreshes whenever the GPS speed setting changes - and only then`() = runTest {
        val gps = kotlinx.coroutines.flow.MutableStateFlow(false)
        val prefs: AppPreferences = mockk { every { useGpsSpeed } returns gps }
        val context = mockk<Context>(relaxed = true)
        val c = controller(backgroundScope, context = context, prefs = prefs)
        c.connected(context, includesLocation = false)

        backgroundScope.launch { c.keepServiceTypeCurrent(ForegroundStartMode.TYPED) }
        runCurrent()
        verify(exactly = 0) { context.startForegroundService(any()) }   // GPS aus, Dienst ohne Standort-Typ: passt

        gps.value = true
        runCurrent()
        verify(exactly = 1) { context.startForegroundService(any()) }   // GPS an: der Dienst bekommt den Typ

        gps.value = true                                                 // derselbe Wert: kein weiterer Start
        runCurrent()
        verify(exactly = 1) { context.startForegroundService(any()) }
    }
}
