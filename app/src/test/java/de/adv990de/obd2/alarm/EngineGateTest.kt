package de.adv990de.obd2.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineGateTest {

    @Test
    fun `below 500 rpm the engine is off`() {
        val gate = EngineGate()
        gate.update(0, 499f)
        assertFalse(gate.isRunning())
        assertEquals(0L, gate.runningForMs(10_000))
    }

    @Test
    fun `from 500 rpm the engine runs and the running time counts from the first sample`() {
        val gate = EngineGate()
        gate.update(1_000, 500f)
        gate.update(6_000, 2_000f)
        assertTrue(gate.isRunning())
        assertEquals(5_000L, gate.runningForMs(6_000))
    }

    @Test
    fun `the last rpm is held for RPM_HOLD_MS and then the engine counts as off`() {
        val gate = EngineGate()
        gate.update(0, 2_000f)
        gate.update(EngineGate.RPM_HOLD_MS, null)
        assertTrue("held at exactly the hold time", gate.isRunning())
        gate.update(EngineGate.RPM_HOLD_MS + 1, null)
        assertFalse("off just after it", gate.isRunning())
    }

    @Test
    fun `the hold is twelve seconds - one rpm timeout plus a slow cycle does not stop the engine`() {
        val gate = EngineGate()
        gate.update(0, 2_000f)
        // Ein Zyklus, in dem nur die Drehzahl ins ELM-Zeitlimit (5 s) lief, kommt rund 0,5 + 5 + 0,6 s nach dem vorigen und hat keine Drehzahl.
        gate.update(6_500, null)
        assertTrue("one rpm timeout is no stall", gate.isRunning())
        assertEquals("the running time keeps counting", 6_500L, gate.runningForMs(6_500))
        gate.update(12_000, null)
        assertTrue("held at 12 s", gate.isRunning())
        gate.update(12_001, null)
        assertFalse("two rpm timeouts in a row (12.6 s) count as engine off", gate.isRunning())
    }

    @Test
    fun `a drop to idle below 500 stops the running time`() {
        val gate = EngineGate()
        gate.update(0, 2_000f)
        gate.update(1_000, 300f)
        assertFalse(gate.isRunning())
        gate.update(2_000, 2_000f)
        assertEquals(0L, gate.runningForMs(2_000))      // the count starts over
    }

    @Test
    fun `NaN and infinity are not a reading`() {
        val gate = EngineGate()
        gate.update(0, Float.NaN)
        gate.update(100, Float.POSITIVE_INFINITY)
        assertFalse(gate.isRunning())
    }

    @Test
    fun `reset turns the engine off`() {
        val gate = EngineGate()
        gate.update(0, 2_000f)
        gate.reset()
        assertFalse(gate.isRunning())
    }
}
