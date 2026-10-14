package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.PidRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Welche PIDs die Sitzung abfragt: belegte Slots, gefiltert gegen das Steuergeraet, dazu immer Drehzahl und Kuehlmittel. */
class PidSelectionTest {

    private fun ids(
        slots: List<String>,
        supported: Set<String>? = null,
        dynamic: Boolean = false,
    ): List<String> = selectPids(slots, supported, dynamic).map { it.id }

    @Test
    fun `the slots come first, then RPM and coolant temperature`() {
        assertEquals(
            listOf(PidRegistry.SPEED.id, PidRegistry.ENGINE_LOAD.id, PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id),
            ids(listOf(PidRegistry.SPEED.id, PidRegistry.ENGINE_LOAD.id)),
        )
    }

    @Test
    fun `RPM and coolant are queried only once when they are slots too`() {
        assertEquals(
            listOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id),
            ids(listOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id)),
        )
    }

    @Test
    fun `slots the ECU does not report are dropped, RPM and coolant stay`() {
        assertEquals(
            listOf(PidRegistry.SPEED.id, PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id),
            ids(
                slots = listOf(PidRegistry.SPEED.id, PidRegistry.ENGINE_LOAD.id),
                supported = setOf(PidRegistry.SPEED.id),
            ),
        )
    }

    @Test
    fun `an unknown or empty supported set filters nothing`() {
        val slots = listOf(PidRegistry.SPEED.id, PidRegistry.ENGINE_LOAD.id)
        val expected = listOf(PidRegistry.SPEED.id, PidRegistry.ENGINE_LOAD.id, PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id)

        assertEquals("keine Discovery gelaufen", expected, ids(slots, supported = null))
        assertEquals("leere Menge gilt wie unbekannt", expected, ids(slots, supported = emptySet()))
    }

    @Test
    fun `the speed is always queried when the vehicle reports it, slot or not`() {
        assertEquals(
            "ohne Kachel, vom Steuergeraet gemeldet",
            listOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id, PidRegistry.SPEED.id),
            ids(emptyList(), supported = setOf(PidRegistry.SPEED.id, PidRegistry.RPM.id)),
        )
        assertEquals(
            "als Kachel und gemeldet nur einmal",
            listOf(PidRegistry.SPEED.id, PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id),
            ids(listOf(PidRegistry.SPEED.id), supported = setOf(PidRegistry.SPEED.id)),
        )
    }

    @Test
    fun `the speed is not queried on its own when the vehicle does not report it or support is unknown`() {
        val base = listOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id)
        assertEquals("Fahrzeug meldet sie nicht (GPS uebernimmt)", base, ids(emptyList(), supported = setOf(PidRegistry.RPM.id)))
        assertEquals("keine Discovery gelaufen", base, ids(emptyList(), supported = null))
        assertEquals("leere Menge gilt wie unbekannt", base, ids(emptyList(), supported = emptySet()))
    }

    @Test
    fun `the dynamic threshold adds the oil temperature, and only then`() {
        assertEquals(
            listOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id, PidRegistry.ENGINE_OIL_TEMP.id),
            ids(emptyList(), dynamic = true),
        )
        assertEquals(
            listOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id),
            ids(emptyList(), dynamic = false),
        )
    }

    @Test
    fun `unknown slot ids fall away - the list is never empty because RPM and coolant are always there`() {
        assertEquals(
            listOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id),
            ids(listOf("FFFF", "")),
        )
    }
}
