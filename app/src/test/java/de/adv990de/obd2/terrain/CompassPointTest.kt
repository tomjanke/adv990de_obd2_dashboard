package de.adv990de.obd2.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Die acht Sektoren zu je 45 Grad: Nord reicht von 337,5 bis 22,5 Grad, jede Grenze gehoert zum naechsten Sektor im Uhrzeigersinn. */
class CompassPointTest {

    @Test
    fun `north covers both sides of zero`() {
        assertEquals(CompassPoint.N, compassPoint(0f))
        assertEquals(CompassPoint.N, compassPoint(22.4f))
        assertEquals(CompassPoint.N, compassPoint(337.5f))
        assertEquals(CompassPoint.N, compassPoint(359.9f))
    }

    @Test
    fun `a sector starts exactly at its lower bound`() {
        assertEquals(CompassPoint.NE, compassPoint(22.5f))
        assertEquals(CompassPoint.NE, compassPoint(67.4f))
        assertEquals(CompassPoint.E, compassPoint(67.5f))
        assertEquals(CompassPoint.NW, compassPoint(337.4f))
    }

    @Test
    fun `the four cardinal directions`() {
        assertEquals(CompassPoint.E, compassPoint(90f))
        assertEquals(CompassPoint.S, compassPoint(180f))
        assertEquals(CompassPoint.W, compassPoint(270f))
    }

    @Test
    fun `the four intercardinal directions`() {
        assertEquals(CompassPoint.NE, compassPoint(45f))
        assertEquals(CompassPoint.SE, compassPoint(135f))
        assertEquals(CompassPoint.SW, compassPoint(225f))
        assertEquals(CompassPoint.NW, compassPoint(315f))
    }

    @Test
    fun `360 degrees is north and negative angles wrap around`() {
        assertEquals(CompassPoint.N, compassPoint(360f))
        assertEquals(CompassPoint.N, compassPoint(-10f))      // 350 Grad
        assertEquals(CompassPoint.NW, compassPoint(-30f))     // 330 Grad
        assertEquals(CompassPoint.E, compassPoint(450f))
    }

    @Test
    fun `no usable angle gives no direction`() {
        assertNull(compassPoint(Float.NaN))
        assertNull(compassPoint(Float.POSITIVE_INFINITY))
        assertNull(compassPoint(Float.NEGATIVE_INFINITY))
    }
}
