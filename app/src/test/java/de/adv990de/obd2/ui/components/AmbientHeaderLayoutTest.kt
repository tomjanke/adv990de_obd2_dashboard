package de.adv990de.obd2.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Ambient-Statuszeile hat drei Plaetze: Datum links, Oel-Timer in der Mitte, Aussentemperatur rechts. Die Mitte steht exakt mittig
 * (beide Seiten gleich breit); passt das volle Datum nicht in seine Seite, zeigt die Zeile nur die Uhrzeit.
 */
class AmbientHeaderLayoutTest {

    @Test
    fun `a narrow portrait row cannot hold the full date next to the timer - only the time is shown`() {
        // ~380 dp Zeile, volles Datum ~265 dp, Timer ~140 dp: je Seite bleiben 120 dp
        assertTrue(ambientUsesCompactHeader(availableWidth = 380, fullHeaderWidth = 265, centreWidth = 140, minGap = 8))
    }

    @Test
    fun `a wide landscape row keeps the full date`() {
        assertFalse(ambientUsesCompactHeader(availableWidth = 800, fullHeaderWidth = 265, centreWidth = 140, minGap = 8))
    }

    @Test
    fun `the date that exactly fills its side still fits - one pixel more does not`() {
        // Seite = (available - centre) / 2 - gap = (500 - 100) / 2 - 10 = 190
        assertFalse(ambientUsesCompactHeader(availableWidth = 500, fullHeaderWidth = 190, centreWidth = 100, minGap = 10))
        assertTrue(ambientUsesCompactHeader(availableWidth = 500, fullHeaderWidth = 191, centreWidth = 100, minGap = 10))
    }

    @Test
    fun `a centre that is wider than the row leaves no room for the date at all`() {
        assertTrue(ambientUsesCompactHeader(availableWidth = 100, fullHeaderWidth = 10, centreWidth = 200, minGap = 8))
    }

    @Test
    fun `the oil label comes before the timer`() {
        assertEquals("Öl: 6:12", ambientOilText(label = "Öl:", remainingMillis = 372_000L, locale = java.util.Locale.GERMANY))
        assertEquals("Oil: 10:00", ambientOilText(label = "Oil:", remainingMillis = 600_000L, locale = java.util.Locale.US))
    }
}
