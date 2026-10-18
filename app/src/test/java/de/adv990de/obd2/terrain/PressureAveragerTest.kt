package de.adv990de.obd2.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PressureAveragerTest {

    @Test
    fun `without any sample there is no pressure`() {
        assertNull(PressureAverager().mean(0))
    }

    @Test
    fun `the mean of the samples since the last call`() {
        val a = PressureAverager()
        a.add(0, 1000f); a.add(200, 1002f); a.add(400, 1004f)
        assertEquals(1002f, a.mean(500)!!, 0.001f)
        a.add(1_000, 990f)
        assertEquals(990f, a.mean(1_100)!!, 0.001f)   // die alten Proben sind verbraucht
    }

    @Test
    fun `without a new sample the last mean holds for five seconds`() {
        val a = PressureAverager()
        a.add(0, 1000f)
        a.mean(100)
        assertEquals(1000f, a.mean(5_000)!!, 0.001f)
        assertNull(a.mean(5_001))
    }

    @Test
    fun `a stalled sensor does not freeze the value forever`() {
        val a = PressureAverager()
        a.add(0, 1000f)
        a.mean(0)
        assertNull(a.mean(60_000))
    }

    @Test
    fun `a non finite sample is ignored`() {
        val a = PressureAverager()
        a.add(0, Float.NaN)
        a.add(10, Float.POSITIVE_INFINITY)
        assertNull(a.mean(20))
        a.add(30, 1000f)
        assertEquals(1000f, a.mean(40)!!, 0.001f)
    }
}
