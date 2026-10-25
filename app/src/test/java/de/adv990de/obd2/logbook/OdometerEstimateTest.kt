package de.adv990de.obd2.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Der geschaetzte aktuelle Kilometerstand: der hoechste Stand im Logbuch plus die per GPS gezaehlten Kilometer seit diesem Stand. */
class OdometerEstimateTest {

    @Test
    fun `without a logbook there is no estimate`() {
        assertNull(estimateKm(loggedKm = null, gps = GpsOdometer(anchorKm = 40_000, gpsKm = 12f)))
        assertNull(estimateKm(loggedKm = null, gps = null))
    }

    @Test
    fun `without a counter the estimate is the logged reading`() {
        assertEquals(40_000, estimateKm(loggedKm = 40_000, gps = null))
    }

    @Test
    fun `the counter of the same reading is added, rounded to whole kilometres`() {
        assertEquals(40_012, estimateKm(40_000, GpsOdometer(40_000, 12.4f)))
        assertEquals(40_013, estimateKm(40_000, GpsOdometer(40_000, 12.5f)))
        assertEquals(40_000, estimateKm(40_000, GpsOdometer(40_000, 0.4f)))
    }

    @Test
    fun `a counter of another reading is stale and not added`() {
        assertEquals(40_500, estimateKm(40_500, GpsOdometer(anchorKm = 40_000, gpsKm = 120f)))
    }

    @Test
    fun `a negative or non-finite counter counts as nothing`() {
        assertEquals(40_000, estimateKm(40_000, GpsOdometer(40_000, -5f)))
        assertEquals(40_000, estimateKm(40_000, GpsOdometer(40_000, Float.NaN)))
        assertEquals(40_000, estimateKm(40_000, GpsOdometer(40_000, Float.POSITIVE_INFINITY)))
    }

    @Test
    fun `the estimate never goes above the largest reading the logbook accepts`() {
        assertEquals(MAX_ODOMETER_KM, estimateKm(MAX_ODOMETER_KM, GpsOdometer(MAX_ODOMETER_KM, 500f)))
    }

    @Test
    fun `the gps share is what the estimate adds to the logged reading`() {
        assertEquals(12, gpsShareKm(40_000, GpsOdometer(40_000, 12.4f)))
        assertEquals(0, gpsShareKm(40_000, GpsOdometer(39_000, 12.4f)))
        assertEquals(0, gpsShareKm(40_000, null))
        assertEquals(0, gpsShareKm(null, GpsOdometer(40_000, 12.4f)))
    }
}
