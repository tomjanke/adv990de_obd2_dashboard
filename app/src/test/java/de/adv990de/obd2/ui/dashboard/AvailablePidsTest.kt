package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.DerivedPids
import de.adv990de.obd2.obd.PidRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailablePidsTest {

    private val registrySorted = PidRegistry.all.values.sortedBy { it.name }
    private fun ids(supported: Set<String>?, gps: Boolean) = availablePidsFor(supported, gps).map { it.id }

    @Test
    fun `without a list from the ECU the whole registry is offered, sorted by name, plus the terrain tile`() {
        val expected = registrySorted.map { it.id } + DerivedPids.TERRAIN_ID

        assertEquals(expected, ids(supported = null, gps = false))
        assertEquals(expected, ids(supported = null, gps = true))
    }

    @Test
    fun `an empty list from the ECU counts as no list`() {
        assertEquals(registrySorted.map { it.id } + DerivedPids.TERRAIN_ID, ids(supported = emptySet(), gps = false))
    }

    @Test
    fun `with a list only the supported PIDs are offered, still sorted by name`() {
        val supported = setOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id)

        val offered = ids(supported, gps = false)

        assertEquals(registrySorted.filter { it.id in supported }.map { it.id } + DerivedPids.TERRAIN_ID, offered)
        assertFalse(PidRegistry.SPEED.id in offered)
    }

    @Test
    fun `the speed stays selectable while GPS is on, even when the ECU does not report it`() {
        val supported = setOf(PidRegistry.RPM.id)

        assertTrue(PidRegistry.SPEED.id in ids(supported, gps = true))
        assertFalse(PidRegistry.SPEED.id in ids(supported, gps = false))
    }

    @Test
    fun `GPS never adds a PID other than the speed`() {
        val offered = ids(setOf(PidRegistry.RPM.id), gps = true)

        assertEquals(setOf(PidRegistry.RPM.id, PidRegistry.SPEED.id, DerivedPids.TERRAIN_ID), offered.toSet())
    }

    @Test
    fun `a supported speed is offered without GPS too`() {
        assertTrue(PidRegistry.SPEED.id in ids(setOf(PidRegistry.SPEED.id), gps = false))
    }

    @Test
    fun `the terrain tile is always there, exactly once and last`() {
        for (supported in listOf(null, emptySet(), setOf(PidRegistry.RPM.id))) {
            for (gps in listOf(false, true)) {
                val offered = ids(supported, gps)
                assertEquals(1, offered.count { it == DerivedPids.TERRAIN_ID })
                assertEquals(DerivedPids.TERRAIN_ID, offered.last())
            }
        }
    }
}
