package de.adv990de.obd2.track

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFilterTest {

    // 0,0001 Grad Breite sind rund 11,1 m; 0,00003 Grad rund 3,3 m; 0,00005 Grad rund 5,6 m; 0,00001 Grad rund 1,1 m
    private fun fix(lat: Double, accuracy: Float? = 5f, speed: Float? = 30f) =
        TrackPoint(timeMs = 1_000L, lat = lat, lon = 8.0, ele = 100f, accuracyM = accuracy, speedKmh = speed)

    @Test
    fun `the first fix is kept even when standing`() {
        assertTrue(TrackPointFilter().accept(fix(50.0, speed = 0f), nowMs = 0L))
    }

    @Test
    fun `the first fix with a poor accuracy is not kept`() {
        assertFalse(TrackPointFilter().accept(fix(50.0, accuracy = 31f), nowMs = 0L))
    }

    @Test
    fun `an accuracy of exactly 30 m is still good`() {
        assertTrue(TrackPointFilter().accept(fix(50.0, accuracy = 30f), nowMs = 0L))
    }

    @Test
    fun `a fix without a known accuracy is kept`() {
        assertTrue(TrackPointFilter().accept(fix(50.0, accuracy = null), nowMs = 0L))
    }

    @Test
    fun `a fix less than 5 m from the last point is dropped`() {
        val f = TrackPointFilter()
        assertTrue(f.accept(fix(50.0), 0L))
        assertFalse(f.accept(fix(50.00003), 1_000L))
    }

    @Test
    fun `a fix 5 m or more from the last point is kept`() {
        val f = TrackPointFilter()
        assertTrue(f.accept(fix(50.0), 0L))
        assertTrue(f.accept(fix(50.00005), 1_000L))
    }

    @Test
    fun `a fix 3 m away after 10 s is kept`() {
        val f = TrackPointFilter()
        assertTrue(f.accept(fix(50.0), 0L))
        assertTrue(f.accept(fix(50.00003), 10_000L))
    }

    @Test
    fun `a fix 3 m away after 9,9 s is dropped`() {
        val f = TrackPointFilter()
        assertTrue(f.accept(fix(50.0), 0L))
        assertFalse(f.accept(fix(50.00003), 9_999L))
    }

    @Test
    fun `a fix 1 m away is dropped even after a long time`() {
        val f = TrackPointFilter()
        assertTrue(f.accept(fix(50.0), 0L))
        assertFalse(f.accept(fix(50.00001), 60_000L))
    }

    @Test
    fun `standing below 2 km per h gives no point even far away`() {
        val f = TrackPointFilter()
        assertTrue(f.accept(fix(50.0), 0L))
        assertFalse(f.accept(fix(50.001, speed = 1.9f), 1_000L))
    }

    @Test
    fun `exactly 2 km per h counts as moving`() {
        val f = TrackPointFilter()
        assertTrue(f.accept(fix(50.0), 0L))
        assertTrue(f.accept(fix(50.001, speed = 2f), 1_000L))
    }

    @Test
    fun `an unknown speed does not block a point`() {
        val f = TrackPointFilter()
        assertTrue(f.accept(fix(50.0), 0L))
        assertTrue(f.accept(fix(50.001, speed = null), 1_000L))
    }

    @Test
    fun `a rejected fix does not move the reference point`() {
        val f = TrackPointFilter()
        assertTrue(f.accept(fix(50.0), 0L))
        assertFalse(f.accept(fix(50.001, accuracy = 80f), 1_000L))
        // 3 m vom ERSTEN Punkt, 10 s nach ihm: zaehlt, weil der abgelehnte Fix nichts verschoben hat
        assertTrue(f.accept(fix(50.00003), 10_000L))
    }

    @Test
    fun `an accepted fix becomes the reference for the next one`() {
        val f = TrackPointFilter()
        assertTrue(f.accept(fix(50.0), 0L))
        assertTrue(f.accept(fix(50.0001), 1_000L))
        assertFalse(f.accept(fix(50.00012), 2_000L))
    }

    @Test
    fun `reset starts over with a first fix`() {
        val f = TrackPointFilter()
        assertTrue(f.accept(fix(50.0), 0L))
        f.reset()
        assertTrue(f.accept(fix(50.00001, speed = 0f), 1_000L))
    }

    @Test
    fun `the constants are the documented ones`() {
        assertTrue(TrackFilterLimits.MAX_ACCURACY_M == 30f)
        assertTrue(TrackFilterLimits.MIN_DISTANCE_M == 5.0)
        assertTrue(TrackFilterLimits.MIN_DISTANCE_AFTER_INTERVAL_M == 2.0)
        assertTrue(TrackFilterLimits.INTERVAL_MS == 10_000L)
        assertTrue(TrackFilterLimits.MIN_SPEED_KMH == 2f)
    }

    @Test
    fun `a fix dropped for its distance does not move the reference point`() {
        val f = TrackPointFilter()
        assertTrue(f.accept(fix(50.0), 0L))
        assertFalse(f.accept(fix(50.00003), 5_000L))
        // 6,7 m vom ersten Punkt (zaehlt), aber nur 3,3 m vom abgelehnten
        assertTrue(f.accept(fix(50.00006), 10_000L))
    }
}
