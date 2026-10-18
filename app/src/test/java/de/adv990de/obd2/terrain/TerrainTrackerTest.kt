package de.adv990de.obd2.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Der Tracker reicht nur zusammen, was die drei Schaetzer liefern; die Regeln selbst stehen in deren Tests. */
class TerrainTrackerTest {

    private fun fix(t: Long, speed: Float? = 50f, altitude: Float? = 100f, bearing: Float? = 90f) =
        GpsFix(t, speed, altitude, verticalAccuracyM = 5f, bearingDeg = bearing, bearingAccuracyDeg = 10f)

    @Test
    fun `a first good fix gives altitude and heading but no grade yet`() {
        val d = TerrainTracker().update(0, TerrainInput(fix(0), 1013.25f))
        assertEquals(100f, d.altitudeM!!, 0.01f)
        assertEquals(90f, d.headingDeg!!, 0.001f)
        assertNull(d.gradePercent)
        assertTrue(d.gradeHistory.isEmpty())
    }

    @Test
    fun `without a fix the barometer keeps the altitude and nothing else moves`() {
        val tracker = TerrainTracker()
        tracker.update(0, TerrainInput(fix(0), 1013.25f))
        val d = tracker.update(1_000, TerrainInput(null, 1012.25f))
        assertNotNull(d.altitudeM)
        assertEquals(108.3f, d.altitudeM!!, 1f)
        assertNull(d.gradePercent)
    }

    @Test
    fun `reset clears everything including the history`() {
        val tracker = TerrainTracker()
        var t = 0L
        repeat(60) { t += 1_000; tracker.update(t, TerrainInput(fix(t, speed = 36f), 1013.25f)) }
        tracker.reset()
        val d = tracker.update(t + 1_000, TerrainInput(null, 1013.25f))
        assertNull(d.altitudeM)
        assertNull(d.headingDeg)
        assertTrue(d.gradeHistory.isEmpty())
    }

    @Test
    fun `the history is a copy that later updates do not change`() {
        val tracker = TerrainTracker()
        var t = 0L
        var snapshot: List<Float> = emptyList()
        repeat(60) { t += 1_000; snapshot = tracker.update(t, TerrainInput(fix(t, speed = 36f), 1013.25f)).gradeHistory }
        val sizeThen = snapshot.size
        repeat(30) { t += 1_000; tracker.update(t, TerrainInput(fix(t, speed = 36f), 1013.25f)) }
        assertEquals(sizeThen, snapshot.size)
    }
}
