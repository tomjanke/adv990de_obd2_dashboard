package de.adv990de.obd2.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Nur erfundene Linien (um 50 N, 10 E), in Metern ab einem Ursprung: Norden und Osten. */
class ManeuversTest {

    private val metresPerDegLat = 111_195.0

    private fun at(northM: Double, eastM: Double) =
        TrackPoint(null, 50.0 + northM / metresPerDegLat, 10.0 + eastM / (metresPerDegLat * cos(Math.toRadians(50.0))), null, null, null)

    /** Verbindet die Eckpunkte (Norden, Osten) mit Punkten im Abstand [stepM]. */
    private fun path(vararg corners: Pair<Double, Double>, stepM: Double = 5.0): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        out.add(at(corners[0].first, corners[0].second))
        for (i in 1 until corners.size) {
            val (n0, e0) = corners[i - 1]
            val (n1, e1) = corners[i]
            val len = Math.hypot(n1 - n0, e1 - e0)
            val steps = maxOf(1, Math.round(len / stepM).toInt())
            for (k in 1..steps) out.add(at(n0 + (n1 - n0) * k / steps, e0 + (e1 - e0) * k / steps))
        }
        return out
    }

    private fun maneuvers(points: List<TrackPoint>, direction: FollowDirection = FollowDirection.FORWARD) =
        detectManeuvers(FollowLine.of(points, direction)).filter { !it.isDestination }

    @Test
    fun `a straight line has no turns, only the destination`() {
        val all = detectManeuvers(FollowLine.of(path(0.0 to 0.0, 1_000.0 to 0.0), FollowDirection.FORWARD))
        assertEquals(1, all.size)
        assertTrue(all.single().isDestination)
        assertEquals(1_000.0, all.single().apexAlongM, 5.0)
    }

    @Test
    fun `a right angle to the right is one turn of about plus 90 degrees`() {
        val m = maneuvers(path(0.0 to 0.0, 300.0 to 0.0, 300.0 to 300.0)).single()
        assertEquals(90.0, m.turnDeg, 15.0)
        assertTrue(m.isRight)
        assertEquals(ManeuverKind.NORMAL, m.kind)
        assertEquals("the apex sits at the corner", 300.0, m.apexAlongM, 25.0)
        assertTrue(m.startAlongM < m.apexAlongM && m.apexAlongM < m.endAlongM)
    }

    @Test
    fun `a right angle to the left is negative`() {
        val m = maneuvers(path(0.0 to 0.0, 300.0 to 0.0, 300.0 to -300.0)).single()
        assertEquals(-90.0, m.turnDeg, 15.0)
        assertFalse(m.isRight)
    }

    @Test
    fun `a hairpin is one turn of about 180 degrees`() {
        val m = maneuvers(path(0.0 to 0.0, 300.0 to 0.0, 300.0 to 12.0, 0.0 to 12.0)).single()
        assertEquals(180.0, abs(m.turnDeg), 25.0)
        assertEquals(ManeuverKind.HAIRPIN, m.kind)
    }

    @Test
    fun `a long gentle arc is no turn`() {
        // 90 Grad auf 300 m Radius: ueber ein Fenster von 50 m sind es nur rund 10 Grad.
        val r = 300.0
        val arc = (0..60).map { i ->
            val a = i / 60.0 * PI / 2
            at(r * sin(a), r * (1 - cos(a)))
        }
        assertTrue(maneuvers(path(0.0 to 0.0, 0.0 to 0.0) + arc).isEmpty())
    }

    @Test
    fun `an s-bend gives two turns of opposite sign`() {
        // Rechts, 70 m geradeaus, links: die Zwischenstrecke ist laenger als der Abstand, ab dem gleichsinnige Kurven verschmelzen — und sie sind gegensinnig.
        val m = maneuvers(path(0.0 to 0.0, 200.0 to 0.0, 200.0 to 80.0, 400.0 to 80.0, 400.0 to 80.0, 600.0 to 80.0))
        assertEquals(2, m.size)
        assertTrue(m[0].isRight)
        assertFalse(m[1].isRight)
    }

    @Test
    fun `two bends in the same direction close together merge into one turn`() {
        // Zwei Knicke von je 45 Grad im Abstand von 20 m.
        val m = maneuvers(path(0.0 to 0.0, 200.0 to 0.0, 220.0 to 20.0, 220.0 to 220.0))
        assertEquals(1, m.size)
        assertEquals(90.0, m.single().turnDeg, 15.0)
    }

    @Test
    fun `two right angles to the right 100 m apart are one turn, 150 m apart they are two`() {
        // Rechts, d Meter geradeaus, rechts: die Luecke zwischen den beiden Aenderungen entscheidet ueber das Zusammenfassen (Grenze 40 m).
        fun rights(d: Double) = maneuvers(path(0.0 to 0.0, 300.0 to 0.0, 300.0 to d, 0.0 to d, 0.0 to d + 300.0)).filter { it.isRight }
        val close = rights(100.0)
        assertEquals("a gap under the limit merges into one", 1, close.size)
        assertEquals(180.0, close.single().turnDeg, 20.0)
        val far = rights(150.0)
        assertEquals("a gap over the limit stays two", 2, far.size)
        assertEquals(90.0, far[0].turnDeg, 20.0)
    }

    @Test
    fun `jitter on a straight line is no turn`() {
        val jittery = (0..200).map { i -> at(i * 5.0, if (i % 2 == 0) 3.0 else -3.0) }
        assertTrue(maneuvers(jittery).isEmpty())
    }

    @Test
    fun `a sparse line with only three points still finds the corner`() {
        val m = maneuvers(listOf(at(0.0, 0.0), at(300.0, 0.0), at(300.0, 300.0))).single()
        assertEquals(90.0, m.turnDeg, 20.0)
    }

    @Test
    fun `driving the line backwards mirrors the turn`() {
        val points = path(0.0 to 0.0, 300.0 to 0.0, 300.0 to 300.0)
        val forward = maneuvers(points).single()
        val reverse = maneuvers(points, FollowDirection.REVERSE).single()
        assertTrue(forward.isRight)
        assertFalse("right becomes left when the line is driven the other way", reverse.isRight)
        val total = FollowLine.of(points, FollowDirection.FORWARD).totalM
        assertEquals(total - forward.apexAlongM, reverse.apexAlongM, 30.0)
    }

    @Test
    fun `a full circle is one hairpin-sized turn`() {
        val circle = (0..72).map { i ->
            val a = i / 72.0 * 2 * PI
            at(30 * sin(a), 30 * (1 - cos(a)))
        }
        val m = maneuvers(path(-200.0 to 0.0, 0.0 to 0.0) + circle).single()
        assertTrue(abs(m.turnDeg) > 300)
        assertEquals(ManeuverKind.HAIRPIN, m.kind)
    }

    @Test
    fun `the destination is the last entry and missing for a very short line`() {
        val all = detectManeuvers(FollowLine.of(path(0.0 to 0.0, 300.0 to 0.0, 300.0 to 300.0), FollowDirection.FORWARD))
        val last = all.last()
        assertTrue(last.isDestination)
        assertEquals(0.0, last.turnDeg, 0.0)
        assertEquals(600.0, last.apexAlongM, 5.0)
        assertTrue(detectManeuvers(FollowLine.of(path(0.0 to 0.0, 50.0 to 0.0), FollowDirection.FORWARD)).isEmpty())
    }

    @Test
    fun `a line without length has no maneuvers`() {
        assertTrue(detectManeuvers(FollowLine.of(listOf(at(0.0, 0.0), at(0.0, 0.0)), FollowDirection.FORWARD)).isEmpty())
    }

    @Test
    fun `the class follows the angle at its thresholds`() {
        assertEquals(ManeuverKind.SLIGHT, maneuverKindFor(59.9))
        assertEquals(ManeuverKind.NORMAL, maneuverKindFor(60.0))
        assertEquals(ManeuverKind.NORMAL, maneuverKindFor(-109.9))
        assertEquals(ManeuverKind.SHARP, maneuverKindFor(110.0))
        assertEquals(ManeuverKind.SHARP, maneuverKindFor(149.9))
        assertEquals(ManeuverKind.HAIRPIN, maneuverKindFor(-150.0))
    }

    @Test
    fun `the sensitivity levels map to their thresholds and unknown values are the default`() {
        assertEquals(90.0, RoadbookSensitivity.fromLevel(1).minTurnDeg, 0.0)
        assertEquals(40.0, RoadbookSensitivity.fromLevel(2).minTurnDeg, 0.0)
        assertEquals(30.0, RoadbookSensitivity.fromLevel(3).minTurnDeg, 0.0)
        assertEquals(RoadbookSensitivity.STANDARD, RoadbookSensitivity.fromLevel(0))
        assertEquals(RoadbookSensitivity.STANDARD, RoadbookSensitivity.fromLevel(99))
        assertEquals(listOf(1, 2, 3), RoadbookSensitivity.OPTIONS)
        assertEquals(2, RoadbookSensitivity.DEFAULT_LEVEL)
    }

    @Test
    fun `the candidate floor is the smallest threshold`() {
        assertEquals(MANEUVER_CANDIDATE_MIN_DEG, RoadbookSensitivity.ALSO_SLIGHT.minTurnDeg, 0.0)
        // Ein Knick von 25 Grad liegt unter jeder Schwelle.
        val m = maneuvers(path(0.0 to 0.0, 300.0 to 0.0, 300.0 + 260.0 * cos(Math.toRadians(25.0)) to 260.0 * sin(Math.toRadians(25.0))))
        assertTrue(m.isEmpty())
    }
}
