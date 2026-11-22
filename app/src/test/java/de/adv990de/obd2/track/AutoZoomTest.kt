package de.adv990de.obd2.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoZoomTest {

    @Test
    fun `the target span follows the speed within its limits`() {
        assertEquals(AUTO_ZOOM_MIN_SPAN_M, autoZoomTargetSpanM(0f), 0.0)
        assertEquals(AUTO_ZOOM_MIN_SPAN_M, autoZoomTargetSpanM(-10f), 0.0)
        assertEquals(272.0, autoZoomTargetSpanM(30f), 2.0)
        assertEquals(451.0, autoZoomTargetSpanM(60f), 2.0)
        assertEquals(690.0, autoZoomTargetSpanM(100f), 3.0)
        assertEquals(870.0, autoZoomTargetSpanM(130f), 3.0)
        assertEquals(AUTO_ZOOM_MAX_SPAN_M, autoZoomTargetSpanM(500f), 0.0)
        assertEquals(AUTO_ZOOM_UNKNOWN_SPAN_M, autoZoomTargetSpanM(null), 0.0)
    }

    @Test
    fun `a faster ride always zooms out`() {
        var last = 0.0
        for (v in listOf(0f, 20f, 40f, 60f, 80f, 100f, 120f, 140f)) {
            val span = autoZoomTargetSpanM(v)
            assertTrue("$v km/h", span >= last)
            last = span
        }
    }

    @Test
    fun `the first value is the target itself`() {
        assertEquals(autoZoomTargetSpanM(60f), AutoZoom().update(60f, 1_000), 0.0)
    }

    @Test
    fun `a small change in speed does not move the span`() {
        val z = AutoZoom()
        val start = z.update(60f, 0)
        for (i in 1..30) assertEquals(start, z.update(if (i % 2 == 0) 62f else 58f, i * 1_000L), 0.0)
    }

    @Test
    fun `a big change moves the span and it converges to the target`() {
        val z = AutoZoom()
        z.update(30f, 0)
        var span = 0.0
        for (i in 1..40) span = z.update(100f, i * 1_000L)
        assertEquals(autoZoomTargetSpanM(100f), span, autoZoomTargetSpanM(100f) * 0.15)
    }

    @Test
    fun `the span moves gradually, not in one step`() {
        val z = AutoZoom()
        val before = z.update(30f, 0)
        val target = autoZoomTargetSpanM(100f)
        val oneSecond = z.update(100f, 1_000)
        assertTrue("moved", oneSecond > before)
        assertTrue("but not to the target in one second", oneSecond < target - 1)
    }

    @Test
    fun `without a speed the last target stays`() {
        val z = AutoZoom()
        z.update(100f, 0)
        var span = 0.0
        for (i in 1..30) span = z.update(null, i * 1_000L)
        assertEquals(autoZoomTargetSpanM(100f), span, 1.0)
    }

    @Test
    fun `a long pause counts as at most five seconds and a backward clock changes nothing`() {
        val z = AutoZoom()
        val start = z.update(30f, 0)
        val after = z.update(100f, 1_000_000)
        val capped = AutoZoom().also { it.update(30f, 0) }.update(100f, 5_000)
        assertEquals(capped, after, 0.0)
        assertTrue(after > start)
        assertEquals(after, z.update(100f, 0), 1e-9)
    }
}
