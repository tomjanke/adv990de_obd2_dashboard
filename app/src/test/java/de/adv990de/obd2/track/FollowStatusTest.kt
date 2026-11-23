package de.adv990de.obd2.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FollowStatusTest {

    private val point = TrackPoint(0L, 50.0, 8.0, null, null, null)

    private fun following(distanceToTrackM: Double, remainingM: Double = 12_400.0): FollowReading.Following =
        FollowReading.Following(
            position = point,
            result = FollowResult(
                distanceToTrackM = distanceToTrackM,
                alongM = 1_000.0,
                remainingM = remainingM,
                totalM = 13_400.0,
                segmentIndex = 3,
                bearingDeg = 90.0,
            ),
        )

    // --- die Zustaende ohne Position ---

    @Test
    fun `without a position the status waits for the GPS`() {
        assertEquals(FollowStatus.Waiting, FollowStatusTracker().update(FollowReading.Searching))
    }

    @Test
    fun `without the permission the status says there is no location`() {
        assertEquals(FollowStatus.NoLocation, FollowStatusTracker().update(FollowReading.NoPermission))
    }

    // --- auf dem Track / abseits ---

    @Test
    fun `right on the track the status shows the remaining distance`() {
        assertEquals(FollowStatus.OnTrack(12_400.0), FollowStatusTracker().update(following(3.0)))
    }

    @Test
    fun `the first fix counts as on the track up to 20 metres`() {
        assertEquals(FollowStatus.OnTrack(12_400.0), FollowStatusTracker().update(following(FOLLOW_STATUS_ON_TRACK_M)))
    }

    @Test
    fun `the first fix beyond 20 metres is off the track`() {
        assertEquals(FollowStatus.OffTrack(21.0), FollowStatusTracker().update(following(21.0)))
    }

    @Test
    fun `being on the track stays so up to 30 metres`() {
        val tracker = FollowStatusTracker()
        tracker.update(following(5.0))
        assertEquals(FollowStatus.OnTrack(12_400.0), tracker.update(following(FOLLOW_STATUS_OFF_TRACK_M)))
    }

    @Test
    fun `being on the track ends beyond 30 metres`() {
        val tracker = FollowStatusTracker()
        tracker.update(following(5.0))
        assertEquals(FollowStatus.OffTrack(30.5), tracker.update(following(30.5)))
    }

    @Test
    fun `being off the track needs 20 metres or less to be on it again`() {
        val tracker = FollowStatusTracker()
        tracker.update(following(80.0))
        assertEquals(FollowStatus.OffTrack(25.0), tracker.update(following(25.0)))
        assertEquals(FollowStatus.OnTrack(12_400.0), tracker.update(following(20.0)))
    }

    @Test
    fun `a GPS fix that jitters around the border does not flicker`() {
        val tracker = FollowStatusTracker()
        val states = listOf(12.0, 22.0, 18.0, 27.0, 19.0, 29.0).map { tracker.update(following(it)) }
        assertTrue(states.all { it is FollowStatus.OnTrack })
    }

    @Test
    fun `off the track the distance is the one to the track`() {
        assertEquals(FollowStatus.OffTrack(185.0), FollowStatusTracker().update(following(185.0)))
    }

    @Test
    fun `a lost position forgets the old state`() {
        val tracker = FollowStatusTracker()
        tracker.update(following(5.0))
        tracker.update(FollowReading.Searching)
        // 25 m waere nach einer Zeit auf dem Track noch „auf dem Track“, nach dem Verlust der Position beginnt es neu.
        assertEquals(FollowStatus.OffTrack(25.0), tracker.update(following(25.0)))
    }

    // --- Ziel ---

    @Test
    fun `on the track with less than 20 metres left the destination is reached`() {
        assertEquals(FollowStatus.Arrived, FollowStatusTracker().update(following(4.0, remainingM = 19.0)))
    }

    @Test
    fun `exactly 20 metres left is not yet the destination`() {
        assertEquals(FollowStatus.OnTrack(ROADBOOK_ARRIVED_M), FollowStatusTracker().update(following(4.0, remainingM = ROADBOOK_ARRIVED_M)))
    }

    @Test
    fun `off the track near the end the distance to the track wins over the destination`() {
        assertEquals(FollowStatus.OffTrack(90.0), FollowStatusTracker().update(following(90.0, remainingM = 5.0)))
    }

    // --- Entfernungen ---

    @Test
    fun `below a kilometre the distance is rounded to 5 metres`() {
        assertEquals("85 m", formatFollowDistance(84.0, Locale.GERMANY))
        assertEquals("85 m", formatFollowDistance(87.4, Locale.GERMANY))
        assertEquals("90 m", formatFollowDistance(87.5, Locale.GERMANY))
    }

    @Test
    fun `a distance under 3 metres shows as zero metres`() {
        assertEquals("0 m", formatFollowDistance(2.4, Locale.GERMANY))
    }

    @Test
    fun `from a kilometre on the distance has one decimal in the device language`() {
        assertEquals("12,4 km", formatFollowDistance(12_400.0, Locale.GERMANY))
        assertEquals("12.4 km", formatFollowDistance(12_400.0, Locale.US))
        assertEquals("1,0 km", formatFollowDistance(1_000.0, Locale.GERMANY))
    }

    @Test
    fun `just below a kilometre it stays in metres`() {
        assertEquals("995 m", formatFollowDistance(997.0, Locale.GERMANY))
    }

    @Test
    fun `rounding to 5 metres never shows 1000 m`() {
        assertEquals("1,0 km", formatFollowDistance(999.0, Locale.GERMANY))
    }

    @Test
    fun `an unusable distance shows as zero`() {
        assertEquals("0 m", formatFollowDistance(Double.NaN, Locale.GERMANY))
        assertEquals("0 m", formatFollowDistance(-5.0, Locale.GERMANY))
    }
}
