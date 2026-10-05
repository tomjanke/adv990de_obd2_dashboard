package de.adv990de.obd2.crash

import de.adv990de.obd2.data.AppPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QS B33: Sentry laesst sich zur Laufzeit abschalten. Ohne DSN im Build (der Fall der ausgelieferten Releases) tut der
 * Controller nie etwas; mit DSN folgt Sentry dem Schalter — und startet NIE, bevor der Nutzer-Wert gelesen wurde.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrashReportingControllerTest {

    private class FakeSdk(override val configuredDsn: String?) : SentrySdk {
        override var isActive = false
        var starts = 0
        var stops = 0
        override fun start() { starts++; isActive = true }
        override fun stop() { stops++; isActive = false }
    }

    private fun prefs(enabled: MutableStateFlow<Boolean>): AppPreferences =
        mockk { every { crashReportsEnabled } returns enabled }

    // ── isAvailable ───────────────────────────────────────────────────────

    @Test
    fun `crash reporting is available only when the build carries a DSN`() {
        assertTrue(CrashReportingController(FakeSdk("https://k@o.ingest.sentry.io/1"), prefs(MutableStateFlow(true))).isAvailable)
        assertFalse(CrashReportingController(FakeSdk(null), prefs(MutableStateFlow(true))).isAvailable)
    }

    // ── apply ─────────────────────────────────────────────────────────────

    @Test
    fun `without a DSN nothing is ever started - whatever the switch says`() {
        val sdk = FakeSdk(null)
        val controller = CrashReportingController(sdk, prefs(MutableStateFlow(true)))

        controller.apply(enabled = true)
        controller.apply(enabled = false)

        assertEquals(0, sdk.starts)
        assertEquals(0, sdk.stops)
    }

    @Test
    fun `enabled with a DSN starts once, a repeated enable does not start twice`() {
        val sdk = FakeSdk("dsn")
        val controller = CrashReportingController(sdk, prefs(MutableStateFlow(true)))

        controller.apply(true)
        controller.apply(true)

        assertEquals(1, sdk.starts)
        assertTrue(sdk.isActive)
    }

    @Test
    fun `turning it off stops a running SDK, turning it on again starts it`() {
        val sdk = FakeSdk("dsn")
        val controller = CrashReportingController(sdk, prefs(MutableStateFlow(true)))

        controller.apply(true)
        controller.apply(false)
        assertFalse(sdk.isActive)
        assertEquals(1, sdk.stops)

        controller.apply(true)
        assertTrue(sdk.isActive)
        assertEquals(2, sdk.starts)
    }

    @Test
    fun `disabling something that never ran does not stop anything`() {
        val sdk = FakeSdk("dsn")

        CrashReportingController(sdk, prefs(MutableStateFlow(false))).apply(false)

        assertEquals(0, sdk.stops)
    }

    // ── run: follows the preference, starts only after the value is known ─

    @Test
    fun `run does not start the SDK before the preference was read, and not at all when the user turned it off`() = runTest {
        val sdk = FakeSdk("dsn")
        val enabled = MutableStateFlow(false)       // der Nutzer hat abgeschaltet

        val job = launch { CrashReportingController(sdk, prefs(enabled)).run() }
        runCurrent()

        assertEquals("kein Start gegen die Einstellung des Nutzers", 0, sdk.starts)
        job.cancel()
    }

    @Test
    fun `run follows the switch while the app runs`() = runTest {
        val sdk = FakeSdk("dsn")
        val enabled = MutableStateFlow(true)

        val job = launch { CrashReportingController(sdk, prefs(enabled)).run() }
        runCurrent()
        assertTrue("an: gestartet", sdk.isActive)

        enabled.value = false
        runCurrent()
        assertFalse("aus: gestoppt", sdk.isActive)

        enabled.value = true
        runCurrent()
        assertTrue("wieder an", sdk.isActive)
        job.cancel()
    }

    // ── manifest value ────────────────────────────────────────────────────

    @Test
    fun `a blank manifest DSN counts as no DSN`() {
        assertEquals(null, normalizedDsn(null))
        assertEquals(null, normalizedDsn(""))
        assertEquals(null, normalizedDsn("   "))
        assertEquals("https://k@o.ingest.sentry.io/1", normalizedDsn("  https://k@o.ingest.sentry.io/1 "))
    }

    // ── status shown in the app info ──────────────────────────────────────

    @Test
    fun `the status distinguishes not included, off and on`() {
        assertEquals(CrashReportingStatus.NotIncluded, crashReportingStatus(available = false, enabled = true))
        assertEquals(CrashReportingStatus.NotIncluded, crashReportingStatus(available = false, enabled = false))
        assertEquals(CrashReportingStatus.Off, crashReportingStatus(available = true, enabled = false))
        assertEquals(CrashReportingStatus.On, crashReportingStatus(available = true, enabled = true))
    }
}
