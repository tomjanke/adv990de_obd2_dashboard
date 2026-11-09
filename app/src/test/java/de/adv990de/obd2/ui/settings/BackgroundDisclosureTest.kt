package de.adv990de.obd2.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Erklaerung vor der Anfrage nach dem Hintergrund-Standort (Play-Richtlinie „Prominent Disclosure“): was auf die Antwort des Nutzers folgt, entscheidet
 * [backgroundDisclosureOutcome]. Die Regeln: nur „Weiter“ ist eine Zustimmung und loest die System-Anfrage aus; „Nicht jetzt“ (und damit auch Wegtippen und
 * Zurueck, die der Dialog als „Nicht jetzt“ behandelt) fragt nichts an; **jede** Antwort merkt sich, dass gefragt wurde — die Frage kommt hoechstens einmal je
 * Installation (`backgroundLocationPrompted`).
 */
class BackgroundDisclosureTest {

    @Test
    fun `continue is the only answer that requests the system permission`() {
        assertTrue(backgroundDisclosureOutcome(BackgroundDisclosureChoice.CONTINUE).requestPermission)
        assertFalse(backgroundDisclosureOutcome(BackgroundDisclosureChoice.NOT_NOW).requestPermission)
    }

    @Test
    fun `every answer remembers that the question was asked`() {
        BackgroundDisclosureChoice.values().forEach { choice ->
            assertTrue("$choice muss die Frage als gestellt merken", backgroundDisclosureOutcome(choice).markPrompted)
        }
    }

    @Test
    fun `there are exactly two answers - consent can not be inferred from anything else`() {
        // Ein dritter Weg (z. B. „Dialog geschlossen“) muss bewusst entschieden werden: er darf nie wie eine Zustimmung wirken.
        assertTrue(BackgroundDisclosureChoice.values().toSet() == setOf(BackgroundDisclosureChoice.CONTINUE, BackgroundDisclosureChoice.NOT_NOW))
    }
}
