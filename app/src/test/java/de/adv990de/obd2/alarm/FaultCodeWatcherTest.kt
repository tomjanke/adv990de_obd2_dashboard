package de.adv990de.obd2.alarm

import de.adv990de.obd2.obd.model.DtcEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultCodeWatcherTest {

    private fun c(code: String) = DtcEntry(code, isPending = false)
    private fun p(code: String) = DtcEntry(code, isPending = true)

    @Test
    fun `the read on connect is the baseline and reports nothing`() {
        val watcher = FaultCodeWatcher()
        assertTrue(watcher.onRead(listOf(c("P0301")), isBaseline = true).isEmpty())
        assertTrue(watcher.onRead(listOf(c("P0301")), isBaseline = false).isEmpty())
    }

    @Test
    fun `a new code needs two consecutive reads`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(listOf(c("P0301")), isBaseline = true)
        assertTrue(watcher.onRead(listOf(c("P0301"), c("P0420")), false).isEmpty())
        assertEquals(listOf(FaultFinding("P0420", false)), watcher.onRead(listOf(c("P0301"), c("P0420")), false))
    }

    @Test
    fun `a code seen once and gone again is no finding`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(emptyList(), isBaseline = true)
        assertTrue(watcher.onRead(listOf(c("P0420")), false).isEmpty())
        assertTrue(watcher.onRead(emptyList(), false).isEmpty())
        assertTrue(watcher.onRead(listOf(c("P0420")), false).isEmpty())      // the count starts over
    }

    @Test
    fun `a finding is reported once`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(emptyList(), isBaseline = true)
        watcher.onRead(listOf(c("P0420")), false)
        assertEquals(1, watcher.onRead(listOf(c("P0420")), false).size)
        assertTrue(watcher.onRead(listOf(c("P0420")), false).isEmpty())
    }

    @Test
    fun `an unknown baseline makes the first successful read the baseline`() {
        val watcher = FaultCodeWatcher()
        assertTrue(watcher.onRead(null, isBaseline = true).isEmpty())
        assertTrue(watcher.onRead(listOf(c("P0301")), false).isEmpty())      // becomes the baseline silently
        assertTrue(watcher.onRead(listOf(c("P0301"), c("P0420")), false).isEmpty())
        assertEquals(listOf(FaultFinding("P0420", false)), watcher.onRead(listOf(c("P0301"), c("P0420")), false))
    }

    @Test
    fun `a failed read changes nothing`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(emptyList(), isBaseline = true)
        watcher.onRead(listOf(c("P0420")), false)
        assertTrue(watcher.onRead(null, false).isEmpty())
        assertEquals(1, watcher.onRead(listOf(c("P0420")), false).size)      // still the second consecutive success
    }

    @Test
    fun `a pending code that becomes confirmed is new again`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(listOf(p("P0301")), isBaseline = true)
        assertTrue(watcher.onRead(listOf(c("P0301")), false).isEmpty())
        assertEquals(listOf(FaultFinding("P0301", false)), watcher.onRead(listOf(c("P0301")), false))
    }

    @Test
    fun `a new pending code is reported as pending`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(emptyList(), isBaseline = true)
        watcher.onRead(listOf(p("P0171")), false)
        assertEquals(listOf(FaultFinding("P0171", true)), watcher.onRead(listOf(p("P0171")), false))
    }

    @Test
    fun `a known code that disappears and returns is new again`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(listOf(c("P0301")), isBaseline = true)
        watcher.onRead(emptyList(), false)
        assertTrue(watcher.onRead(listOf(c("P0301")), false).isEmpty())
        assertEquals(1, watcher.onRead(listOf(c("P0301")), false).size)
    }

    @Test
    fun `strings that are no fault code are ignored`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(emptyList(), isBaseline = true)
        watcher.onRead(listOf(c("XYZ"), c("P03011"), c("p0301")), false)
        assertTrue(watcher.onRead(listOf(c("XYZ"), c("P03011"), c("p0301")), false).isEmpty())
    }

    @Test
    fun `several findings come sorted by code`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(emptyList(), isBaseline = true)
        watcher.onRead(listOf(c("P0420"), c("P0301")), false)
        assertEquals(listOf("P0301", "P0420"), watcher.onRead(listOf(c("P0420"), c("P0301")), false).map { it.code })
    }

    @Test
    fun `reset forgets the baseline`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(listOf(c("P0301")), isBaseline = true)
        watcher.reset()
        assertTrue(watcher.onRead(listOf(c("P0301"), c("P0420")), false).isEmpty())   // first read after reset = new baseline
        assertTrue(watcher.onRead(listOf(c("P0301"), c("P0420")), false).isEmpty())
    }
}
