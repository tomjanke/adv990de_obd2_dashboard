package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.model.PidDefinition
import de.adv990de.obd2.obd.PidRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Collator
import java.util.Locale

/**
 * Der Slot-Picker war nach dem ENGLISCHEN PID-Namen sortiert (`PidRegistry.all.values.sortedBy { it.name }`), angezeigt wird aber
 * der uebersetzte Name — auf einem deutschen Geraet stand die Liste scheinbar ungeordnet (UID-15). Sortiert wird jetzt nach dem,
 * was der Nutzer liest.
 */
class SlotPickerOrderTest {

    private val german = Collator.getInstance(Locale.GERMANY)

    private fun pid(id: String) = PidRegistry.all.getValue(id)

    @Test
    fun `the list is ordered by the displayed label, not by the English name`() {
        val labels = mapOf(
            PidRegistry.RPM.id to "Drehzahl",
            PidRegistry.COOLANT_TEMP.id to "Kuehlmittel",
            PidRegistry.SPEED.id to "Geschwindigkeit",
        )
        val sorted = sortByDisplayLabel(listOf(PidRegistry.RPM, PidRegistry.COOLANT_TEMP, PidRegistry.SPEED), { labels.getValue(it.id) }, german)

        assertEquals(listOf("Drehzahl", "Geschwindigkeit", "Kuehlmittel"), sorted.map { labels.getValue(it.id) })
    }

    @Test
    fun `an umlaut sorts with its base letter - not after Z`() {
        val labels = mapOf(PidRegistry.RPM.id to "Zuendung", PidRegistry.SPEED.id to "Oeltemperatur", PidRegistry.COOLANT_TEMP.id to "\u00d6ldruck")
        val pids = listOf(PidRegistry.RPM, PidRegistry.SPEED, PidRegistry.COOLANT_TEMP)

        val sorted = sortByDisplayLabel(pids, { labels.getValue(it.id) }, german).map { labels.getValue(it.id) }

        // Ein Vergleich nach Codepunkten setzte "\u00d6ldruck" hinter "Zuendung".
        assertEquals(listOf("Oeltemperatur", "\u00d6ldruck", "Zuendung"), sorted)
    }

    @Test
    fun `case does not decide the order`() {
        val labels = mapOf(PidRegistry.RPM.id to "abc", PidRegistry.SPEED.id to "Abd", PidRegistry.COOLANT_TEMP.id to "ABB")
        val pids = listOf(PidRegistry.RPM, PidRegistry.SPEED, PidRegistry.COOLANT_TEMP)

        val sorted = sortByDisplayLabel(pids, { labels.getValue(it.id) }, german).map { labels.getValue(it.id) }

        // Buchstabe fuer Buchstabe: abb < abc < abd, unabhaengig von der Gross-/Kleinschreibung.
        assertEquals(listOf("ABB", "abc", "Abd"), sorted)
    }

    @Test
    fun `equal labels keep their incoming order - the sort is stable`() {
        val labels = mapOf(PidRegistry.RPM.id to "Gleich", PidRegistry.SPEED.id to "Gleich")
        val sorted = sortByDisplayLabel(listOf(PidRegistry.SPEED, PidRegistry.RPM), { labels.getValue(it.id) }, german)

        assertEquals(listOf(PidRegistry.SPEED, PidRegistry.RPM), sorted)
    }

    @Test
    fun `an empty list stays empty and the input is not modified`() {
        assertEquals(emptyList<PidDefinition>(), sortByDisplayLabel(emptyList(), { it.id }, german))
        val input = listOf(PidRegistry.SPEED, PidRegistry.RPM)
        sortByDisplayLabel(input, { it.id }, german)
        assertEquals(listOf(PidRegistry.SPEED, PidRegistry.RPM), input)
    }
}
