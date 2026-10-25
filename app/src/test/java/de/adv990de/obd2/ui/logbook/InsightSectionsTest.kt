package de.adv990de.obd2.ui.logbook

import de.adv990de.obd2.logbook.LogFilter
import de.adv990de.obd2.logbook.LogType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Welche Abschnitte der Auswertung zu welchem Filter der Logbuch-Liste gehoeren: "Alle" zeigt alles, jeder andere Filter nur, was zu seinen Eintraegen passt. */
class InsightSectionsTest {

    @Test
    fun `all shows the overview and the three cards`() {
        val sections = insightSectionsFor(LogFilter.All)

        assertTrue(sections.overview)
        assertEquals(listOf(LogType.FUEL, LogType.OIL, LogType.COOLANT), sections.cards)
    }

    @Test
    fun `fuel, oil and coolant each show only their own card`() {
        assertEquals(InsightSections(overview = false, cards = listOf(LogType.FUEL)), insightSectionsFor(LogFilter.Fuel))
        assertEquals(InsightSections(overview = false, cards = listOf(LogType.OIL)), insightSectionsFor(LogFilter.Oil))
        assertEquals(InsightSections(overview = false, cards = listOf(LogType.COOLANT)), insightSectionsFor(LogFilter.Coolant))
    }

    @Test
    fun `maintenance shows only the overview`() {
        val sections = insightSectionsFor(LogFilter.Maintenance)

        assertTrue(sections.overview)
        assertTrue(sections.cards.isEmpty())
    }

    @Test
    fun `every filter shows at least one section, and no card is for a kind without a consumption`() {
        for (filter in LogFilter.entries) {
            val sections = insightSectionsFor(filter)
            assertTrue("$filter", sections.overview || sections.cards.isNotEmpty())
            assertTrue("$filter", sections.cards.all { it == LogType.FUEL || it == LogType.OIL || it == LogType.COOLANT })
        }
        assertFalse(insightSectionsFor(LogFilter.Fuel).overview)
    }
}
