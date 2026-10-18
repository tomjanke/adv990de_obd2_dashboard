package de.adv990de.obd2.ui.dashboard

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Textwahl des Alarmbands als reine Funktion: welche Stelle der Meldung die Zahl (bzw. die Codes) ist und in der Zahlenschrift erscheint.
 * Der Text selbst bleibt EIN Knoten mit genau dem Wortlaut von `alarmMessage` (TalkBack, Tests) — nur eine Spanne bekommt eine eigene Schrift.
 */
class AlarmBannerLogicTest {

    private val mono = SpanStyle(fontWeight = FontWeight.Bold)

    @Test
    fun `the text of the banner is the message, unchanged`() {
        val styled = emphasizeValue("Spannung niedrig: 12,3 V", "12,3", mono)

        assertEquals("Spannung niedrig: 12,3 V", styled.text)
    }

    @Test
    fun `only the number carries the number style, not the words and not the unit`() {
        val message = "Spannung niedrig: 12,3 V"

        val spans = emphasizeValue(message, "12,3", mono).spanStyles

        assertEquals(1, spans.size)
        assertEquals(mono, spans.single().item)
        assertEquals("12,3", message.substring(spans.single().start, spans.single().end))
    }

    @Test
    fun `a whole number in the middle of the words is found`() {
        val message = "Kühlmittel heiß: 97 °C"

        val span = emphasizeValue(message, "97", mono).spanStyles.single()

        assertEquals("97", message.substring(span.start, span.end))
    }

    @Test
    fun `several fault codes are one span, the codes and their separator`() {
        val message = "Neue Fehlercodes: P0301, P0420"

        val span = emphasizeValue(message, "P0301, P0420", mono).spanStyles.single()

        assertEquals("P0301, P0420", message.substring(span.start, span.end))
        assertEquals(message.length, span.end)
    }

    @Test
    fun `the dashes of a missing value are the number too, so the gap looks like a reading`() {
        val message = "Spannung niedrig: -- V"

        val span = emphasizeValue(message, "--", mono).spanStyles.single()

        assertEquals("--", message.substring(span.start, span.end))
    }

    @Test
    fun `a value that is not in the message gets no span and the text stays whole`() {
        // Eine Übersetzung ohne den Platzhalter darf das Band nicht verschlucken: ohne Hervorhebung, aber mit dem ganzen Text.
        val styled = emphasizeValue("Spannung niedrig", "12,3", mono)

        assertEquals("Spannung niedrig", styled.text)
        assertTrue(styled.spanStyles.isEmpty())
    }

    @Test
    fun `an empty value gets no span - indexOf of the empty string would match at zero and mark nothing useful`() {
        val styled = emphasizeValue("Neuer Fehlercode: ", "", mono)

        assertEquals("Neuer Fehlercode: ", styled.text)
        assertTrue(styled.spanStyles.isEmpty())
    }

    @Test
    fun `the first occurrence is the one that is marked`() {
        val message = "12 steht vor 12"

        val span = emphasizeValue(message, "12", mono).spanStyles.single()

        assertEquals(0, span.start)
        assertEquals(2, span.end)
    }

    // --- Die Live-Zahl im Band gilt nur, solange Werte fliessen ("Nur Live zeigt Zahlen", Dashboard-Phasen) ---

    @Test
    fun `only the live phase shows the number in the band`() {
        assertTrue(alarmBannerShowsValue(DashboardPhase.Live))
        assertFalse(alarmBannerShowsValue(DashboardPhase.Stale))      // the number would be up to 30 s old while the tiles show dashes
        assertFalse(alarmBannerShowsValue(DashboardPhase.Waiting))
        assertFalse(alarmBannerShowsValue(DashboardPhase.Offline))
    }
}
