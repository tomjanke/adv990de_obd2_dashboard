package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.PidDefinition
import org.junit.Assert.assertEquals
import org.junit.Test

/** Welche PIDs nur jeden vierten Zyklus gefragt werden: die Spannung fuer den Spannungsalarm, wenn sie keine Kachel ist. */
class SelectSlowPidsTest {

    private val voltage = PidRegistry.CONTROL_MODULE_VOLTAGE
    private val withVoltage = setOf(PidRegistry.RPM.id, voltage.id)

    private fun slow(
        selected: List<PidDefinition> = listOf(PidRegistry.RPM, PidRegistry.COOLANT_TEMP),
        supported: Set<String>? = withVoltage,
        alarmOn: Boolean = true,
    ): List<PidDefinition> = selectSlowPids(selected, supported, alarmOn)

    @Test
    fun `the voltage is slow-polled when the alarm is on, the vehicle reports it and it is no tile`() {
        assertEquals(listOf(voltage), slow())
    }

    @Test
    fun `nothing is slow-polled when the voltage alarm is off`() {
        assertEquals(emptyList<PidDefinition>(), slow(alarmOn = false))
    }

    @Test
    fun `nothing is slow-polled when the voltage is a tile, it is queried every cycle then`() {
        assertEquals(
            emptyList<PidDefinition>(),
            slow(selected = listOf(voltage, PidRegistry.RPM, PidRegistry.COOLANT_TEMP)),
        )
    }

    @Test
    fun `nothing is slow-polled without a discovery result`() {
        assertEquals("keine Discovery gelaufen", emptyList<PidDefinition>(), slow(supported = null))
        assertEquals("leere Menge gilt wie unbekannt", emptyList<PidDefinition>(), slow(supported = emptySet()))
    }

    @Test
    fun `nothing is slow-polled when the vehicle does not report the voltage`() {
        // Ein Fahrzeug, das 0142 nicht beantwortet, kostete sonst regelmaessig einen vergeblichen Befehl (bis 5 s bei einem Zeitlimit).
        assertEquals(
            emptyList<PidDefinition>(),
            slow(supported = setOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id)),
        )
    }
}
