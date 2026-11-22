package de.adv990de.obd2.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Linie ist erfunden: ein Platzhalter-Weg, auf dem die Richtungsaenderungen von Hand gesetzt sind. */
class RoadbookTrackerTest {

    private fun p(i: Int) = TrackPoint(null, 50.0 + i * 0.001, 10.0, null, null, null)

    private val line = FollowLine.of(List(60) { p(it) }, FollowDirection.FORWARD)

    private fun turn(apex: Double, deg: Double) = Maneuver(apex - 10, apex, apex + 10, deg, maneuverKindFor(deg))

    private fun tracker(vararg maneuvers: Maneuver) = RoadbookTracker(maneuvers.toList(), line)

    private fun result(along: Double, off: Double = 0.0) = FollowResult(off, along, line.totalM - along, line.totalM, line.segmentAt(along), 0.0)

    private fun RoadbookTracker.at(along: Double, speed: Float? = 0f, nowMs: Long = 0, off: Double = 0.0, minTurn: Double = 60.0) =
        update(result(along, off), p(0), speed, nowMs, minTurn)

    @Test
    fun `the trigger distance follows the speed within its limits`() {
        assertEquals(150.0, roadbookTriggerM(null), 0.0)
        assertEquals(150.0, roadbookTriggerM(0f), 0.0)
        assertEquals(150.0, roadbookTriggerM(30f), 0.0) // 100 m, unter dem Minimum
        assertEquals(333.3, roadbookTriggerM(100f), 0.5)
        assertEquals(800.0, roadbookTriggerM(300f), 0.0)
        assertEquals(150.0, roadbookTriggerM(-5f), 0.0)
    }

    @Test
    fun `nothing shows while the turn is farther than the trigger`() {
        val t = tracker(turn(1_000.0, 90.0))
        assertNull(t.at(0.0))
        assertNull(t.at(849.0))
        assertNotNull("exactly at the limit", t.at(850.0))
    }

    @Test
    fun `a higher speed shows the turn earlier`() {
        val t = tracker(turn(1_000.0, 90.0))
        assertNull(t.at(700.0, speed = 50f)) // 300 m, Schwelle 167 m
        assertNotNull(t.at(700.0, speed = 100f)) // 300 m, Schwelle 333 m
    }

    @Test
    fun `the hud reports the distance to the apex and the maneuver`() {
        val hud = tracker(turn(1_000.0, 90.0)).at(900.0)!!
        assertEquals(100.0, hud.distanceM, 0.01)
        assertEquals(90.0, hud.maneuver.turnDeg, 0.0)
        assertEquals(150.0, hud.triggerM, 0.0)
    }

    @Test
    fun `the bar fills towards the turn and the opacity rises`() {
        val t = tracker(turn(1_000.0, 90.0))
        val atTrigger = t.at(850.0)!!
        assertEquals(0f, atTrigger.progress, 0.001f)
        assertEquals(ROADBOOK_MIN_ALPHA, atTrigger.alpha, 0.001f)
        val half = t.at(925.0)!!
        assertEquals(0.5f, half.progress, 0.01f)
        val near = t.at(1_000.0)!!
        assertEquals(1f, near.progress, 0.001f)
        assertEquals(1f, near.alpha, 0.001f)
        // Bei 60 Prozent des Weges ist die Deckkraft schon voll.
        assertEquals(1f, t.at(940.0)!!.alpha, 0.001f)
    }

    @Test
    fun `a visible hud stays a little beyond the limit and for at least three seconds`() {
        val t = tracker(turn(1_000.0, 90.0))
        assertNotNull(t.at(850.0, nowMs = 0)) // 150 m: sichtbar
        assertNotNull("within the hysteresis band", t.at(836.0, nowMs = 500)) // 164 m < 165 m
        assertNotNull("beyond the band but inside the dwell time", t.at(800.0, nowMs = 1_000))
        assertNull("after the dwell time it goes", t.at(800.0, nowMs = ROADBOOK_MIN_DWELL_MS + 1))
    }

    @Test
    fun `a hud that was never shown does not stay`() {
        val t = tracker(turn(1_000.0, 90.0))
        assertNull(t.at(800.0, nowMs = 0))
        assertNull(t.at(800.0, nowMs = 100))
    }

    @Test
    fun `being off the track hides the hud with a dead band`() {
        val t = tracker(turn(1_000.0, 90.0))
        assertNull("not shown beyond 60 m", t.at(900.0, off = 61.0))
        assertNotNull(t.at(900.0, off = 60.0))
        assertNotNull("shown, it may go up to 100 m", t.at(900.0, off = 100.0, nowMs = 10))
        assertNull(t.at(900.0, off = 101.0, nowMs = 20))
    }

    @Test
    fun `a passed turn gives way to the next one`() {
        val t = tracker(turn(1_000.0, 90.0), turn(1_900.0, -80.0))
        assertEquals(1_000.0, t.at(990.0)!!.maneuver.apexAlongM, 0.0)
        // 15 m hinter dem Ende (1010): durchfahren; die naechste liegt weit weg.
        assertNull(t.at(1_030.0))
        assertEquals(1_900.0, t.at(1_800.0)!!.maneuver.apexAlongM, 0.0)
    }

    @Test
    fun `inside a turn the distance is zero and not negative`() {
        val hud = tracker(turn(1_000.0, 90.0)).at(1_004.0)!!
        assertEquals(0.0, hud.distanceM, 0.0)
        assertEquals(1f, hud.progress, 0.001f)
    }

    @Test
    fun `the sensitivity hides turns below its threshold`() {
        val t = tracker(turn(1_000.0, 70.0))
        assertNull(t.at(900.0, minTurn = 90.0))
        assertNotNull(t.at(900.0, minTurn = 60.0))
        assertNotNull(t.at(900.0, minTurn = 40.0))
        assertNull("a 30 degree turn is below everything", tracker(turn(1_000.0, 30.0)).at(900.0, minTurn = 40.0))
    }

    @Test
    fun `the destination shows near the end and is gone on arrival`() {
        val total = line.totalM
        val t = tracker(Maneuver(total, total, total, 0.0, ManeuverKind.DESTINATION))
        assertNull(t.at(total - 400.0))
        val hud = t.at(total - 100.0)!!
        assertTrue(hud.maneuver.isDestination)
        assertEquals(100.0, hud.distanceM, 0.01)
        assertNull("arrived", t.at(total - 10.0))
    }

    @Test
    fun `a following turn shows small when it comes soon after`() {
        val close = tracker(turn(1_000.0, 90.0), turn(1_100.0, -80.0)).at(900.0)!!
        assertEquals(-80.0, close.next!!.turnDeg, 0.0)
        assertEquals(100.0, close.nextDistanceM!!, 0.01)
        val far = tracker(turn(1_000.0, 90.0), turn(1_200.0, -80.0)).at(900.0)!!
        assertNull(far.next)
        assertNull(far.nextDistanceM)
    }

    @Test
    fun `the next turn after the destination is not shown and the destination has no next`() {
        val total = line.totalM
        val hud = tracker(Maneuver(total, total, total, 0.0, ManeuverKind.DESTINATION)).at(total - 100.0)!!
        assertNull(hud.next)
    }

    @Test
    fun `without a speed the last known one counts`() {
        val t = tracker(turn(1_000.0, 90.0))
        assertNotNull(t.at(700.0, speed = 100f, nowMs = 0))
        assertNotNull("no speed in this fix, 100 km/h still counts", t.at(701.0, speed = null, nowMs = 10))
        val fresh = tracker(turn(1_000.0, 90.0))
        assertNull(fresh.at(700.0, speed = null))
    }

    @Test
    fun `the path of the small map runs from behind the position to beyond the turn`() {
        val hud = tracker(turn(1_000.0, 90.0)).at(900.0)!!
        val first = line.cumulativeM[line.points.indexOf(hud.path.first())]
        val last = line.cumulativeM[line.points.indexOf(hud.path.last())]
        assertTrue("starts at most one segment before 60 m behind", first <= 900.0 - ROADBOOK_PATH_BEHIND_M + 112)
        assertTrue("ends beyond the turn", last >= 1_010.0)
        assertEquals(line.pointAt(1_000.0).lat, hud.apex.lat, 1e-9)
    }
}
