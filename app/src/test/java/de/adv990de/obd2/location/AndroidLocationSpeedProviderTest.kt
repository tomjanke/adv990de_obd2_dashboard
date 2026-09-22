package de.adv990de.obd2.location

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the GPS request interval. No behavior test can exercise the real
 * LocationManager in a JVM test (see the class's own KDoc) — this only pins the constant so it
 * can't silently drift back to a slower value.
 */
class AndroidLocationSpeedProviderTest {

    @Test
    fun `GPS updates are requested faster than the OBD poll cycle`() {
        assertEquals(200L, AndroidLocationSpeedProvider.MIN_INTERVAL_MS)
    }
}
