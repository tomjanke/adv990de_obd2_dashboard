package de.adv990de.obd2.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SparklineTest {

    // --- Test 1 ---

    @Test
    fun `constant values stay constant`() {
        val points = listOf(5f, 5f, 5f, 5f, 5f)

        assertEquals(points, smoothSparklinePoints(points))
    }

    // --- Test 2 ---

    @Test
    fun `a single spike is attenuated by its neighbors`() {
        val points = listOf(10f, 10f, 10f, 100f, 10f, 10f, 10f)

        val result = smoothSparklinePoints(points)

        // window=3 around index 3: average of points[2..4] = (10+100+10)/3 = 40
        assertEquals(40f, result[3], 0.001f)
        assertTrue("Spike must be dampened, not passed through", result[3] < 100f)
        assertTrue("Spike must still be visible as a bump, not erased", result[3] > 10f)
    }

    // --- Test 3 ---

    @Test
    fun `edge points average with fewer neighbors instead of crashing`() {
        val points = listOf(10f, 20f, 30f)

        val result = smoothSparklinePoints(points)

        // i=0: avg(10,20)=15 ; i=1: avg(10,20,30)=20 ; i=2: avg(20,30)=25
        assertEquals(listOf(15f, 20f, 25f), result)
    }

    // --- Test 4 ---

    @Test
    fun `lists shorter than 2 points pass through unchanged`() {
        assertEquals(emptyList<Float>(), smoothSparklinePoints(emptyList()))
        assertEquals(listOf(7f), smoothSparklinePoints(listOf(7f)))
    }

    // --- Test 5 ---

    @Test
    fun `a two-point list averages both points for both entries`() {
        val result = smoothSparklinePoints(listOf(10f, 20f))

        assertEquals(listOf(15f, 15f), result)
    }

    // --- sparklinePointOffset ---
    //
    // Coverage for the min/max-to-pixel mapping extracted from the Sparkline Canvas block
    // (see its KDoc for why `range`/`usableHeight` are each coerced away from zero).

    // --- Test 6 ---

    @Test
    fun `flat line does not divide by zero and maps to the top of the usable band`() {
        // min == max: without the coerceAtLeast(0.001f) guard on range, (value - min) / range
        // would be 0f / 0f == NaN.
        val result = sparklinePointOffset(
            index = 2,
            value = 5f,
            pointCount = 5,
            min = 5f,
            max = 5f,
            width = 100f,
            height = 48f,
            inset = 2f,
        )

        assertFalse("y must be finite when min == max", result.y.isNaN())
        // usableHeight = 48 - 2*2 = 44; (value - min) == 0 regardless of the guarded range,
        // so y collapses to inset + usableHeight (the band's bottom/top-of-value edge).
        assertEquals(2f + 44f, result.y, 0.001f)
    }

    // --- Test 7 ---

    @Test
    fun `min maps to the bottom and max maps to the top of the usable band`() {
        val first = sparklinePointOffset(
            index = 0, value = 10f, pointCount = 3, min = 10f, max = 30f, width = 100f, height = 50f, inset = 2f,
        )
        val last = sparklinePointOffset(
            index = 2, value = 30f, pointCount = 3, min = 10f, max = 30f, width = 100f, height = 50f, inset = 2f,
        )

        assertEquals(0f, first.x, 0.001f)
        assertEquals(100f, last.x, 0.001f)
        val usableHeight = 50f - 2 * 2f
        assertEquals(2f + usableHeight, first.y, 0.001f) // min value -> bottom of the band
        assertEquals(2f, last.y, 0.001f) // max value -> top of the band
    }

    // --- Test 8 ---

    @Test
    fun `degenerate canvas height is coerced instead of going negative`() {
        // height - 2*inset = 1 - 4 = -3, coerced to the 1f floor.
        val result = sparklinePointOffset(
            index = 0, value = 5f, pointCount = 2, min = 0f, max = 10f, width = 10f, height = 1f, inset = 2f,
        )

        assertEquals(2f + 1f - (5f / 10f) * 1f, result.y, 0.001f)
    }

    // --- effectiveSparklineRange ---
    //
    // Backlog item (Sweep 2026-09-28/29): without a meaningful floor, a near-constant PID (e.g.
    // battery voltage sitting at ~12.6V with only sensor noise) auto-scaled to fill the whole
    // tile height on fluctuations too small to mean anything. The floor is a fraction of the
    // series' own magnitude, not a fixed unit -- Sparkline is domain-agnostic and RPM/voltage need
    // very different absolute floors.

    // --- Test 9 ---

    @Test
    fun `a range already wider than the floor is returned unchanged`() {
        val (min, max) = effectiveSparklineRange(min = 10f, max = 30f)

        assertEquals(10f, min, 0.001f)
        assertEquals(30f, max, 0.001f)
    }

    // --- Test 10 ---

    @Test
    fun `a near-flat range is expanded symmetrically around its midpoint`() {
        // magnitude 12.62, default floorFraction 0.05 -> floor 0.631; natural range is only 0.04.
        val (min, max) = effectiveSparklineRange(min = 12.58f, max = 12.62f)

        assertEquals(12.6f - 0.631f / 2f, min, 0.001f)
        assertEquals(12.6f + 0.631f / 2f, max, 0.001f)
        assertEquals(0.631f, max - min, 0.001f)
    }

    // --- Test 11 ---

    @Test
    fun `all-zero values do not divide by zero`() {
        val (min, max) = effectiveSparklineRange(min = 0f, max = 0f)

        assertFalse(min.isNaN())
        assertFalse(max.isNaN())
        assertTrue("expanded range must be strictly positive", max > min)
    }

    // --- Test 12 ---

    @Test
    fun `the floor scales with the series own magnitude, not a fixed unit`() {
        // Same relative noise (0.1 percent of magnitude) at two very different magnitudes: the
        // absolute floor must scale accordingly, not use one fixed absolute threshold for both.
        val (highMin, highMax) = effectiveSparklineRange(min = 99.9f, max = 100f)
        val (lowMin, lowMax) = effectiveSparklineRange(min = 0.999f, max = 1f)

        assertEquals(100f * 0.05f, highMax - highMin, 0.001f)
        assertEquals(1f * 0.05f, lowMax - lowMin, 0.0001f)
    }
}
