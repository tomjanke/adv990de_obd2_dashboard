package de.adv990de.obd2.ui.trips

import de.adv990de.obd2.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Die vier Bereiche unter dem Menuepunkt "Fahrten": die Fahrten, die zwei Ansichten der Fehlercodes und das Logbuch. Reihenfolge und
 * Beschriftung stehen im Umschalter — "Aktuell" und "Historie" sind die Ansichten der DTC-Seite, ihre Texte bleiben dieselben.
 */
class TripsSectionTest {

    @Test
    fun `the trips come first, then what the vehicle reports now, then what it reported before, then the logbook`() {
        assertEquals(listOf(TripsSection.Trips, TripsSection.Current, TripsSection.History, TripsSection.Logbook), TripsSection.entries.toList())
    }

    @Test
    fun `the labels are the trips, the two views of the DTC page and the logbook`() {
        assertEquals(R.string.trips_section_trips, TripsSection.Trips.labelRes)
        assertEquals(R.string.dtc_tab_current, TripsSection.Current.labelRes)
        assertEquals(R.string.dtc_tab_history, TripsSection.History.labelRes)
        assertEquals(R.string.trips_section_logbook, TripsSection.Logbook.labelRes)
    }

    @Test
    fun `only the history of the fault codes shows the fault history`() {
        assertEquals(false, TripsSection.Trips.showsFaultHistory)
        assertEquals(false, TripsSection.Current.showsFaultHistory)
        assertEquals(true, TripsSection.History.showsFaultHistory)
        assertEquals(false, TripsSection.Logbook.showsFaultHistory)
    }

    @Test
    fun `only the two views of the DTC page are about fault codes - not the trips and not the logbook`() {
        assertEquals(listOf(false, true, true, false), TripsSection.entries.map { it.isFaultCodes })
    }
}
