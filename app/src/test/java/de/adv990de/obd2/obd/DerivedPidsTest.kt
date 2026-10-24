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
    fun `the oil bar has the stable id and name that the database and the CSV carry`() {
        assertEquals("OIL_BAR", DerivedPids.OIL_BAR.id)
        assertEquals("Oil bar", DerivedPids.OIL_BAR.name)
        assertEquals("OIL_BAR", DerivedPids.OIL_BAR_ID)
    }

    @Test
    fun `derived values are not OBD PIDs - they stay out of the registry, the tile picker and the discovery`() {
        for (derived in DerivedPids.all.values) {
            assertFalse("${derived.id} gehoert nicht in die Registry", PidRegistry.all.containsKey(derived.id))
            assertFalse("${derived.id} gehoert nicht in die Registry", PidRegistry.all.values.contains(derived))
        }
    }

    @Test
    fun `derived values are looked up by id`() {
        assertSame(DerivedPids.GEAR, DerivedPids.all[DerivedPids.GEAR_ID])
        assertSame(DerivedPids.OIL_BAR, DerivedPids.all[DerivedPids.OIL_BAR_ID])
    }

    @Test
    fun `the derived values are exactly the gear, the oil bar and the values state, with different ids`() {
        assertEquals(setOf("GEAR", "OIL_BAR", "VALUES_STATE"), DerivedPids.all.keys)
    }

    @Test
    fun `the values state has the stable id and name that the database and the CSV carry`() {
        assertEquals("VALUES_STATE", DerivedPids.VALUES_STATE.id)
        assertEquals("Values state", DerivedPids.VALUES_STATE.name)
        assertEquals("VALUES_STATE", DerivedPids.VALUES_STATE_ID)
        assertSame(DerivedPids.VALUES_STATE, DerivedPids.all[DerivedPids.VALUES_STATE_ID])
    }

    @Test
    fun `the source names are stored as text in the database and the CSV - they never change`() {
        assertEquals(listOf("ECU", "GPS", "DERIVED"), ValueSource.entries.map { it.name })
    }

    @Test
    fun `the terrain tile has the stable slot id that the preferences carry`() {
        assertEquals("TERRAIN", DerivedPids.TERRAIN.id)
        assertEquals("TERRAIN", DerivedPids.TERRAIN_ID)
    }

    @Test
    fun `the terrain entry is a slot entry only - never recorded, never queried`() {
        assertFalse("nie aufgezeichnet, also nicht in all (der CSV-Export benennt damit Zeilen)", DerivedPids.all.containsKey(DerivedPids.TERRAIN_ID))
        assertFalse("nie abgefragt, also nicht in der Registry", PidRegistry.all.containsKey(DerivedPids.TERRAIN_ID))
    }
}
