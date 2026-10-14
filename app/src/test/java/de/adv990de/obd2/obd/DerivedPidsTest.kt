package de.adv990de.obd2.obd

import de.adv990de.obd2.obd.model.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/** Werte, die die App selbst berechnet (kein OBD-Befehl): getrennt von der [PidRegistry], damit sie nie abgefragt oder als Kachel angeboten werden. */
class DerivedPidsTest {

    @Test
    fun `the gear has the stable id and name that the database and the CSV carry`() {
        assertEquals("GEAR", DerivedPids.GEAR.id)
        assertEquals("Gear", DerivedPids.GEAR.name)
        assertEquals("GEAR", DerivedPids.GEAR_ID)
    }

    @Test
    fun `derived values are not OBD PIDs - they stay out of the registry, the tile picker and the discovery`() {
        assertFalse(PidRegistry.all.containsKey(DerivedPids.GEAR.id))
        assertFalse(PidRegistry.all.values.contains(DerivedPids.GEAR))
    }

    @Test
    fun `derived values are looked up by id`() {
        assertSame(DerivedPids.GEAR, DerivedPids.all[DerivedPids.GEAR_ID])
    }

    @Test
    fun `the source names are stored as text in the database and the CSV - they never change`() {
        assertEquals(listOf("ECU", "GPS", "DERIVED"), ValueSource.entries.map { it.name })
    }
}
