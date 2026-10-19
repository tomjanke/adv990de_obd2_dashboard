package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.R
import de.adv990de.obd2.projectRoot
import de.adv990de.obd2.ui.theme.StatusColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Farbe, Vorlesewort und Beschreibung des Lade-/Entlade-Balkens (die Zeichnung selbst prüft `VoltageGaugeDeviceTest`). */
class VoltageGaugeTest {

    // --- Farbe ---------------------------------------------------------------------------------------

    @Test
    fun `charging is the traffic light green, barely charging the yellow, discharging the red`() {
        assertEquals(StatusColors.TrafficLightGreen, voltageGaugeColor(ChargeState.Charging))
        assertEquals(StatusColors.Warning, voltageGaugeColor(ChargeState.Marginal))
        assertEquals(StatusColors.Critical, voltageGaugeColor(ChargeState.Discharging))
    }

    // --- Beschreibung für Screenreader -------------------------------------------------------------

    @Test
    fun `the description of a tile with a state names it after the value`() {
        assertEquals("Control Module Voltage: 13,6 V, charging", sensorCardDescription("Control Module Voltage", "13,6", "V", "charging"))
    }

    @Test
    fun `the description without a state is the old one`() {
        assertEquals("Coolant: 87 °C", sensorCardDescription("Coolant", "87", "°C", stateWord = null))
        assertEquals("Control Module Voltage: -- V", sensorCardDescription("Control Module Voltage", "--", "V", stateWord = null))
    }

    @Test
    fun `each state has its own word`() {
        val res = ChargeState.entries.map { voltageStateRes(it) }
        assertEquals(res.toSet().size, res.size)
        assertEquals(R.string.voltage_state_charging, voltageStateRes(ChargeState.Charging))
        assertEquals(R.string.voltage_state_marginal, voltageStateRes(ChargeState.Marginal))
        assertEquals(R.string.voltage_state_discharging, voltageStateRes(ChargeState.Discharging))
    }

    // --- Strings in beiden Sprachen ------------------------------------------------------------------

    private fun strings(locale: String): Map<String, String> {
        val xml = projectRoot.resolve("app/src/main/res/$locale/strings.xml").readText()
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `the three state words exist in both languages and differ`() {
        val en = strings("values")
        val de = strings("values-de")
        for (name in listOf("voltage_state_charging", "voltage_state_marginal", "voltage_state_discharging")) {
            assertTrue("$name fehlt (en)", en[name].orEmpty().isNotBlank())
            assertTrue("$name fehlt (de)", de[name].orEmpty().isNotBlank())
            assertNotEquals("$name: deutsch ist englisch", en.getValue(name), de.getValue(name))
        }
        assertEquals(listOf("lädt", "lädt kaum", "entlädt"), listOf("voltage_state_charging", "voltage_state_marginal", "voltage_state_discharging").map { de.getValue(it) })
        assertEquals(listOf("charging", "barely charging", "discharging"), listOf("voltage_state_charging", "voltage_state_marginal", "voltage_state_discharging").map { en.getValue(it) })
    }
}
