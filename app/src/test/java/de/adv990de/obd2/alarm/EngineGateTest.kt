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
    fun `the last rpm is held for five seconds and then the engine counts as off`() {
        val gate = EngineGate()
        gate.update(0, 2_000f)
        gate.update(5_000, null)
        assertTrue(gate.isRunning())
        gate.update(5_001, null)
        assertFalse(gate.isRunning())
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
