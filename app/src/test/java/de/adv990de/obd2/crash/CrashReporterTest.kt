package de.adv990de.obd2.crash

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import io.sentry.Sentry
import io.sentry.protocol.SentryId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Covers [SentryCrashReporter.recordException]'s one real branch: the fallback log message used
 * when a [Throwable] carries no message of its own. [SentryCrashReporter.log] is intentionally
 * left untested — it is two lines of unconditional delegation to [Sentry.addBreadcrumb] and
 * [Log.d] with no branching to exercise.
 */
class CrashReporterTest {

    private val reporter: CrashReporter = SentryCrashReporter()

    @Before
    fun setUp() {
        mockkStatic(Log::class, Sentry::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Sentry.captureException(any<Throwable>()) } returns SentryId()
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class, Sentry::class)
    }

    @Test
    fun `recordException logs the throwable's own message when it has one`() {
        val throwable = IllegalStateException("boom")
        val messageSlot = slot<String>()

        reporter.recordException(throwable)

        verify(exactly = 1) { Sentry.captureException(throwable) }
        verify(exactly = 1) { Log.e("CrashReporter", capture(messageSlot), throwable) }
        assertEquals("boom", messageSlot.captured)
    }

    @Test
    fun `recordException falls back to the class simple name when the message is null`() {
        val throwable = IllegalStateException(null as String?)
        val messageSlot = slot<String>()

        reporter.recordException(throwable)

        verify(exactly = 1) { Sentry.captureException(throwable) }
        verify(exactly = 1) { Log.e("CrashReporter", capture(messageSlot), throwable) }
        assertEquals("IllegalStateException", messageSlot.captured)
    }
}
