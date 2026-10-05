package de.adv990de.obd2.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * QS C / SEC-5: Ein gecachter Standort ohne Altersprüfung — der gecachte GPS-Fix schlug einen frischen Netzwerk-Fix,
 * und nach dem ersten aktiven Fix blieb die Position eingefroren, solange keine andere App einen Standort anforderte
 * (die Außentemperatur galt dann für den Ort von vor Stunden). Die Auswahl steht hier als reine Funktion.
 */
class LocationFreshnessTest {

    private val minute = 60_000L

    private fun fix(ageMs: Long, lat: Double = 50.0) = LocationFix(latitude = lat, longitude = 8.0, ageMs = ageMs)

    // ── freshestFix ───────────────────────────────────────────────────────

    @Test
    fun `the youngest valid fix wins - not the GPS one by default`() {
        val gps = fix(8 * minute, lat = 1.0)
        val network = fix(2 * minute, lat = 2.0)

        assertSame(network, freshestFix(listOf(gps, network)))
        assertSame(network, freshestFix(listOf(network, gps)))
    }

    @Test
    fun `a stale GPS fix does not beat a fresh network fix - the old behaviour used it`() {
        val staleGps = fix(3_600_000L)
        val freshNetwork = fix(30_000L)

        assertSame(freshNetwork, freshestFix(listOf(staleGps, freshNetwork)))
    }

    @Test
    fun `when every fix is stale there is none - better no temperature than one from the wrong region`() {
        assertNull(freshestFix(listOf(fix(CACHED_FIX_MAX_AGE_MS + 1), fix(24 * 60 * minute))))
    }

    @Test
    fun `the maximum age is inclusive`() {
        val atLimit = fix(CACHED_FIX_MAX_AGE_MS)
        assertSame(atLimit, freshestFix(listOf(atLimit)))
        assertNull(freshestFix(listOf(fix(CACHED_FIX_MAX_AGE_MS + 1))))
    }

    @Test
    fun `a fix from the future counts as invalid - the monotonic clock restarted or the data is broken`() {
        assertNull(freshestFix(listOf(fix(-1))))
        assertNull(freshestFix(listOf(fix(-5 * minute))))
    }

    @Test
    fun `a brand new fix with age zero is valid`() {
        val now = fix(0)
        assertSame(now, freshestFix(listOf(now)))
    }

    @Test
    fun `missing providers are ignored`() {
        val only = fix(minute)
        assertSame(only, freshestFix(listOf(null, only, null)))
        assertNull(freshestFix(listOf(null, null)))
        assertNull(freshestFix(emptyList()))
    }

    @Test
    fun `the limit can be tightened by the caller`() {
        val fix = fix(5 * minute)
        assertNull(freshestFix(listOf(fix), maxAgeMs = 4 * minute))
        assertSame(fix, freshestFix(listOf(fix), maxAgeMs = 5 * minute))
    }

    @Test
    fun `the default limit is ten minutes`() {
        assertEquals(10 * minute, CACHED_FIX_MAX_AGE_MS)
    }

    // ── fixAgeMs ──────────────────────────────────────────────────────────

    @Test
    fun `the age is the difference of the monotonic clocks in whole milliseconds`() {
        assertEquals(2_000L, fixAgeMs(nowElapsedNanos = 10_000_000_000L, fixElapsedNanos = 8_000_000_000L))
        assertEquals(1L, fixAgeMs(nowElapsedNanos = 2_999_999L, fixElapsedNanos = 1_000_000L))
        assertEquals(0L, fixAgeMs(nowElapsedNanos = 5L, fixElapsedNanos = 5L))
    }

    @Test
    fun `a fix newer than now has a negative age`() {
        assertEquals(-3L, fixAgeMs(nowElapsedNanos = 1_000_000_000L, fixElapsedNanos = 1_003_000_000L))
    }
}
