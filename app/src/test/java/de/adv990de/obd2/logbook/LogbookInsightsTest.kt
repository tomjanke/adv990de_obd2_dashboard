package de.adv990de.obd2.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alle Auswertungen des Logbuchs in einem Aufruf, und die abgeleiteten Werte je Listenzeile. */
class LogbookInsightsTest {

    private fun fuel(km: Int, ml: Int, full: Boolean? = true, id: Long) = logEntry(LogType.FUEL, km = km, amountMl = ml, fullTank = full, id = id, date = TEST_TODAY.minusDays((100_000 - km).toLong()))
    private fun fluid(type: LogType, km: Int, ml: Int, id: Long) = logEntry(type, km = km, amountMl = ml, id = id, date = TEST_TODAY.minusDays((100_000 - km).toLong()))

    @Test
    fun `an empty logbook has empty reports and no derived values`() {
        val insights = logbookInsights(emptyList())

        assertNull(insights.fuel.summary)
        assertNull(insights.oil.summary)
        assertNull(insights.coolant.summary)
        assertTrue(insights.consumption.isEmpty())
        assertEquals(6, insights.maintenance.size)
    }

    @Test
    fun `the closing fill-up of an interval shows the consumption, the first and partial ones do not`() {
        val insights = logbookInsights(
            listOf(fuel(1_000, 10_000, id = 1), fuel(1_100, 5_000, full = false, id = 2), fuel(1_200, 10_000, id = 3), fuel(1_400, 16_000, id = 4)),
        )

        assertNull(insights.consumption[1L])
        assertNull(insights.consumption[2L])
        assertEquals(EntryConsumption.Fuel(7.5), insights.consumption[3L])
        assertEquals(EntryConsumption.Fuel(8.0), insights.consumption[4L])
    }

    @Test
    fun `oil and coolant entries show their consumption per 1000 km, the first of each kind does not`() {
        val insights = logbookInsights(
            listOf(
                fluid(LogType.OIL, 10_000, 100, id = 1),
                fluid(LogType.COOLANT, 10_100, 200, id = 2),
                fluid(LogType.OIL, 10_500, 50, id = 3),
                fluid(LogType.COOLANT, 12_100, 100, id = 4),
            ),
        )

        assertNull(insights.consumption[1L])
        assertNull(insights.consumption[2L])
        assertEquals(EntryConsumption.Fluid(100.0), insights.consumption[3L])
        assertEquals(EntryConsumption.Fluid(50.0), insights.consumption[4L])
    }

    @Test
    fun `an interval that was left out gives no derived value`() {
        val insights = logbookInsights(listOf(fuel(1_000, 10_000, id = 1), fuel(1_000, 8_000, id = 2)))

        assertNull(insights.consumption[2L])
        assertEquals(1, insights.fuel.skipped)
    }
}
