package de.adv990de.obd2.ui.trips

import de.adv990de.obd2.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Die drei Bereiche unter dem Menuepunkt "Fahrten": die Fahrten und die zwei Ansichten der Fehlercodes. Reihenfolge und
 * Beschriftung stehen im Umschalter — "Aktuell" und "Historie" sind die Ansichten der DTC-Seite, ihre Texte bleiben dieselben.
 */
class TripsSectionTest {

    @Test
    fun `the trips come first, then what the vehicle reports now, then what it reported before`() {
        assertEquals(listOf(TripsSection.Trips, TripsSection.Current, TripsSection.History), TripsSection.entries.toList())
    }

    @Test
    fun `the labels are the trips and the two views of the DTC page`() {
        assertEquals(R.string.trips_section_trips, TripsSection.Trips.labelRes)
        assertEquals(R.string.dtc_tab_current, TripsSection.Current.labelRes)
        assertEquals(R.string.dtc_tab_history, TripsSection.History.labelRes)
    }

    @Test
    fun `only the history of the fault codes shows the fault history`() {
        assertEquals(false, TripsSection.Trips.showsFaultHistory)
        assertEquals(false, TripsSection.Current.showsFaultHistory)
        assertEquals(true, TripsSection.History.showsFaultHistory)
    }

    @Test
    fun `the trips are the only section that is not about fault codes`() {
        assertEquals(listOf(false, true, true), TripsSection.entries.map { it.isFaultCodes })
    }
}
