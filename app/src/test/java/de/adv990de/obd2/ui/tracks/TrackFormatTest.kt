package de.adv990de.obd2.ui.tracks

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TrackFormatTest {

    @Test
    fun `a distance below a kilometre is whole metres`() {
        assertEquals("0 m", formatTrackDistance(0, Locale.US))
        assertEquals("850 m", formatTrackDistance(850, Locale.US))
        assertEquals("999 m", formatTrackDistance(999, Locale.US))
    }

    @Test
    fun `from a kilometre on it is kilometres with one decimal in the language of the device`() {
        assertEquals("1.0 km", formatTrackDistance(1_000, Locale.US))
        assertEquals("12.3 km", formatTrackDistance(12_345, Locale.US))
        assertEquals("12,3 km", formatTrackDistance(12_345, Locale.GERMANY))
        assertEquals("123.5 km", formatTrackDistance(123_456, Locale.US))
    }

    @Test
    fun `a negative distance is zero`() {
        assertEquals("0 m", formatTrackDistance(-5, Locale.US))
    }

    @Test
    fun `a duration is minutes and seconds under an hour and hours above`() {
        assertEquals("0:59", formatTrackDuration(59_000))
        assertEquals("1:05", formatTrackDuration(65_000))
        assertEquals("59:59", formatTrackDuration(3_599_000))
        assertEquals("1:00:00", formatTrackDuration(3_600_000))
        assertEquals("1:01:01", formatTrackDuration(3_661_000))
        assertEquals("10:00:00", formatTrackDuration(36_000_000))
    }

    @Test
    fun `an unknown or negative duration`() {
        assertEquals("–", formatTrackDuration(null))
        assertEquals("0:00", formatTrackDuration(-1_000))
    }

    @Test
    fun `a height is whole metres and unknown is a dash`() {
        assertEquals("40 m", formatTrackMetres(40, Locale.US))
        assertEquals("1,234 m", formatTrackMetres(1234, Locale.US))
        assertEquals("1.234 m", formatTrackMetres(1234, Locale.GERMANY))
        assertEquals("–", formatTrackMetres(null, Locale.US))
    }
}
