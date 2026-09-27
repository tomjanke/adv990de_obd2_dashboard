package de.adv990de.obd2.ui.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [holdOutsideTemp] is a pure function over plain [Duration]s on one monotonic time axis — like
 * `updateOilBarTimer`'s `nowMillis` and `isReadingsStale`'s elapsed milliseconds — so every case
 * here is a plain value comparison, no clock, no coroutines. The real clock (a `TimeSource` mark,
 * injectable with `testTimeSource`) lives in [WeatherViewModel]; its wiring is covered in
 * `WeatherViewModelTest`.
 */
class OutsideTemperatureHoldTest {

    private fun at(time: Duration, fetchedC: Float?, held: HeldOutsideTemp?) =
        holdOutsideTemp(fetchedC = fetchedC, now = time, held = held)

    @Test
    fun `a successful fetch is shown and remembered with its time`() {
        assertEquals(HeldOutsideTemp(tempC = 18.0f, refreshedAt = 5.minutes), at(5.minutes, 18.0f, held = null))
    }

    @Test
    fun `nothing to show when the very first fetch fails`() {
        assertNull(at(0.minutes, fetchedC = null, held = null))
    }

    @Test
    fun `a failed fetch right after a good reading keeps the old value`() {
        val held = HeldOutsideTemp(tempC = 18.0f, refreshedAt = 0.minutes)

        assertEquals(held, at(1.minutes, fetchedC = null, held = held))
    }

    @Test
    fun `the old value is kept until just before 30 minutes have passed`() {
        val held = HeldOutsideTemp(tempC = 18.0f, refreshedAt = 10.minutes)

        assertEquals(held, at(10.minutes + 29.minutes + 59.seconds, fetchedC = null, held = held))
    }

    @Test
    fun `a failed fetch 30 minutes after the last good reading falls back to nothing`() {
        val held = HeldOutsideTemp(tempC = 18.0f, refreshedAt = 10.minutes)

        // Exactly 30 minutes counts as expired, same >= convention as isReadingsStale.
        assertNull(at(40.minutes, fetchedC = null, held = held))
        assertNull(at(95.minutes, fetchedC = null, held = held))
    }

    @Test
    fun `a success after a hold period shows the new value at once and restarts the window`() {
        val stale = HeldOutsideTemp(tempC = 18.0f, refreshedAt = 0.minutes)
        val kept = at(20.minutes, fetchedC = null, held = stale)
        assertEquals(stale, kept)

        val refreshed = at(25.minutes, fetchedC = 21.0f, held = kept)
        assertEquals(HeldOutsideTemp(tempC = 21.0f, refreshedAt = 25.minutes), refreshed)

        // The window now counts from 25 min: still held at 54 min (would have expired at 30 min
        // with the old refresh time), gone at 55 min.
        assertEquals(refreshed, at(54.minutes, fetchedC = null, held = refreshed))
        assertNull(at(55.minutes, fetchedC = null, held = refreshed))
    }

    @Test
    fun `a new value always wins, even while an older one is still within its window`() {
        val held = HeldOutsideTemp(tempC = 18.0f, refreshedAt = 0.minutes)

        assertEquals(HeldOutsideTemp(tempC = -2.5f, refreshedAt = 1.minutes), at(1.minutes, -2.5f, held))
    }
}
