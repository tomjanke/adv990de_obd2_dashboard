package de.adv990de.obd2.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Geometrie des Balkendiagramms (rein, ohne Compose): Skala ab 0, die juengsten 24 Balken, der juengste rechts, die Linie des Durchschnitts. */
class BarChartLayoutTest {

    private fun layout(values: List<Float>, average: Float? = null, width: Float = 240f, height: Float = 100f) = barChartLayout(values, average, width, height)

    @Test
    fun `no values give no bars and no average line without an average`() {
        val result = layout(emptyList())

        assertTrue(result.bars.isEmpty())
        assertTrue(result.shownValues.isEmpty())
        assertNull(result.averageY)
    }

    @Test
    fun `the scale starts at zero, every bar sits on the baseline`() {
        val result = layout(listOf(3f, 5f, 4f), height = 100f)

        result.bars.forEach { assertEquals(100f, it.bottom, 1e-4f) }
        assertEquals(100f, result.baselineY, 1e-4f)
    }

    @Test
    fun `the scale ends above the highest value`() {
        val result = layout(listOf(3f, 5f, 4f), height = 100f)

        assertTrue(result.axisMax > 5f)
        assertTrue(result.bars.all { it.top > 0f })
    }

    @Test
    fun `bar heights are proportional to the values`() {
        val result = layout(listOf(2f, 4f, 1f), height = 120f)
        val heights = result.bars.map { it.bottom - it.top }

        assertEquals(heights[1], heights[0] * 2f, 1e-3f)
        assertEquals(heights[0], heights[2] * 2f, 1e-3f)
    }

    @Test
    fun `the newest value is the rightmost bar and the order is oldest to newest from left to right`() {
        val result = layout(listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f), width = 240f)

        assertEquals(result.bars.sortedBy { it.left }, result.bars)
        assertTrue(result.bars.last().right <= 240f)
        assertTrue(result.bars.first().left >= 0f)
    }

    @Test
    fun `at most the newest 24 values are shown`() {
        val values = (1..30).map { it.toFloat() }
        val result = layout(values)

        assertEquals(24, result.bars.size)
        assertEquals((7..30).map { it.toFloat() }, result.shownValues)
    }

    @Test
    fun `24 bars use the whole width`() {
        val result = layout((1..24).map { 1f }, width = 240f)

        assertEquals(0f, result.bars.first().left, 10f)
        assertEquals(240f, result.bars.last().right, 10f)
    }

    @Test
    fun `few values keep a narrow bar and sit at the right end`() {
        val one = layout(listOf(5f), width = 240f)

        assertTrue(one.bars.single().right - one.bars.single().left < 60f)
        assertEquals(240f, one.bars.single().right, 10f)
    }

    @Test
    fun `bars do not touch each other`() {
        val result = layout((1..10).map { it.toFloat() })

        result.bars.zipWithNext().forEach { (a, b) -> assertTrue(b.left > a.right) }
    }

    @Test
    fun `the average line sits at its value on the same scale as the bars`() {
        val result = layout(listOf(2f, 4f), average = 3f, height = 100f)
        val barOfFour = result.bars[1]
        val averageHeight = 100f - result.averageY!!

        // 3 ist drei Viertel von 4: die Linie steht auf drei Viertel der Hoehe des hoechsten Balkens
        assertEquals((barOfFour.bottom - barOfFour.top) * 0.75f, averageHeight, 1e-3f)
    }

    @Test
    fun `an average above the highest value is still inside the scale`() {
        val result = layout(listOf(2f, 3f), average = 10f, height = 100f)

        assertTrue(result.averageY!! > 0f)
        assertTrue(result.axisMax > 10f)
    }

    @Test
    fun `all values equal`() {
        val result = layout(listOf(7f, 7f, 7f), average = 7f)

        assertEquals(1, result.bars.map { it.top }.toSet().size)
        assertEquals(result.bars.first().top, result.averageY!!, 1e-3f)
    }

    @Test
    fun `values of zero give bars without height and no division by zero`() {
        val result = layout(listOf(0f, 0f), average = 0f, height = 100f)

        result.bars.forEach { assertEquals(it.bottom, it.top, 1e-4f) }
        assertEquals(100f, result.averageY!!, 1e-4f)
    }

    @Test
    fun `a negative value is drawn without height`() {
        val result = layout(listOf(-3f, 4f), height = 100f)

        assertEquals(result.bars[0].bottom, result.bars[0].top, 1e-4f)
    }
}
