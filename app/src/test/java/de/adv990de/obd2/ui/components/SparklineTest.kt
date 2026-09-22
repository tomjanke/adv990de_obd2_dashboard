package de.adv990de.obd2.ui.components

import org.junit.Assert.assertEquals
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
}
