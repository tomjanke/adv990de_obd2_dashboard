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
    fun `one absent read does not make a known code new`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(listOf(c("P0301")), isBaseline = true)
        assertTrue(watcher.onRead(emptyList(), false).isEmpty())
        assertTrue(watcher.onRead(listOf(c("P0301")), false).isEmpty())
        assertTrue(watcher.onRead(listOf(c("P0301")), false).isEmpty())      // back and still known: no finding
    }

    @Test
    fun `a code absent in two successive reads is forgotten and needs two sightings to come back`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(listOf(c("P0301")), isBaseline = true)
        assertTrue(watcher.onRead(emptyList(), false).isEmpty())
        assertTrue(watcher.onRead(emptyList(), false).isEmpty())             // second absence: forgotten
        assertTrue(watcher.onRead(listOf(c("P0301")), false).isEmpty())
        assertEquals(listOf(FaultFinding("P0301", false)), watcher.onRead(listOf(c("P0301")), false))
    }

    @Test
    fun `a failed read does not count as an absence`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(listOf(c("P0301")), isBaseline = true)
        assertTrue(watcher.onRead(emptyList(), false).isEmpty())             // absent once
        assertTrue(watcher.onRead(null, false).isEmpty())                    // neutral: not a second absence
        assertTrue(watcher.onRead(listOf(c("P0301")), false).isEmpty())      // back: the absence count starts over
        assertTrue(watcher.onRead(listOf(c("P0301")), false).isEmpty())      // still known, no finding
    }

    @Test
    fun `a code absent once, a failed read, then absent again is dropped and found after two sightings`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(listOf(c("P0301")), isBaseline = true)
        assertTrue(watcher.onRead(emptyList(), false).isEmpty())             // absent once
        assertTrue(watcher.onRead(null, false).isEmpty())                    // a failed read changes nothing
        assertTrue(watcher.onRead(emptyList(), false).isEmpty())             // absent in a second successful read: dropped
        assertTrue(watcher.onRead(listOf(c("P0301")), false).isEmpty())      // first sighting after the drop
        assertEquals(listOf(FaultFinding("P0301", false)), watcher.onRead(listOf(c("P0301")), false))
    }

    @Test
    fun `a new baseline starts the absence count again`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(listOf(c("P0301")), isBaseline = true)
        assertTrue(watcher.onRead(emptyList(), false).isEmpty())             // absent once
        assertTrue(watcher.onRead(listOf(c("P0301")), isBaseline = true).isEmpty())
        assertTrue(watcher.onRead(emptyList(), false).isEmpty())             // absent once again, not twice
        assertTrue(watcher.onRead(listOf(c("P0301")), false).isEmpty())
        assertTrue(watcher.onRead(listOf(c("P0301")), false).isEmpty())      // still known: no finding
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
    fun `a failed read at connect forgets the earlier connection`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(listOf(c("P0301")), isBaseline = true)
        assertTrue(watcher.onRead(null, isBaseline = true).isEmpty())
        assertTrue(watcher.onRead(listOf(c("P0301"), c("P0420")), false).isEmpty())   // the first success is the new baseline
        assertTrue(watcher.onRead(listOf(c("P0301"), c("P0420")), false).isEmpty())   // nothing is new against it
    }

    @Test
    fun `a pending sighting does not confirm when the code is confirmed in the next read`() {
        val watcher = FaultCodeWatcher()
        watcher.onRead(emptyList(), isBaseline = true)
        assertTrue(watcher.onRead(listOf(p("P0171")), false).isEmpty())
        assertTrue(watcher.onRead(listOf(c("P0171")), false).isEmpty())      // a new state is a new candidate, not a confirmation
        assertEquals(listOf(FaultFinding("P0171", false)), watcher.onRead(listOf(c("P0171")), false))
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
