package de.adv990de.obd2.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdWatchdogTest {

    @Test
    fun `not stale while elapsed time is below the timeout`() {
        assertFalse(isReadingsStale(elapsedSinceLastSuccessMs = 19_999L, timeoutMs = 20_000L))
    }

    @Test
    fun `stale exactly at the timeout`() {
        assertTrue(isReadingsStale(elapsedSinceLastSuccessMs = 20_000L, timeoutMs = 20_000L))
    }

    @Test
    fun `stale well past the timeout`() {
        assertTrue(isReadingsStale(elapsedSinceLastSuccessMs = 60_000L, timeoutMs = 20_000L))
    }

    @Test
    fun `zero elapsed time is never stale`() {
        assertFalse(isReadingsStale(elapsedSinceLastSuccessMs = 0L, timeoutMs = 20_000L))
    }
}
