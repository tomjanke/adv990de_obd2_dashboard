package de.adv990de.obd2.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Filter der Logbuch-Liste: Alle, Tanken, Oel, Kuehlfluessigkeit und "Wartung" (Kette, Service, Reifen, Kettenkit). */
class LogFilterTest {

    @Test
    fun `all lets every kind through`() {
        assertTrue(LogType.entries.all { LogFilter.All.matches(it) })
    }

    @Test
    fun `fuel, oil and coolant each let exactly their own kind through`() {
        assertEquals(listOf(LogType.FUEL), LogType.entries.filter { LogFilter.Fuel.matches(it) })
        assertEquals(listOf(LogType.OIL), LogType.entries.filter { LogFilter.Oil.matches(it) })
        assertEquals(listOf(LogType.COOLANT), LogType.entries.filter { LogFilter.Coolant.matches(it) })
    }

    @Test
    fun `maintenance is chain care, service, tire change and chain kit change - the six kinds without a consumption`() {
        assertEquals(
            setOf(LogType.CHAIN_SMALL, LogType.CHAIN_LARGE, LogType.SERVICE_SMALL, LogType.SERVICE_LARGE, LogType.TIRE_CHANGE, LogType.CHAIN_KIT_CHANGE),
            LogType.entries.filter { LogFilter.Maintenance.matches(it) }.toSet(),
        )
        assertFalse(LogFilter.Maintenance.matches(LogType.FUEL))
    }

    @Test
    fun `every kind belongs to at least one specific filter - no entry is only visible under All`() {
        val specific = LogFilter.entries - LogFilter.All
        assertTrue(LogType.entries.all { type -> specific.any { it.matches(type) } })
    }

    @Test
    fun `the order of the filters is the order of the chips`() {
        assertEquals(listOf(LogFilter.All, LogFilter.Fuel, LogFilter.Oil, LogFilter.Coolant, LogFilter.Maintenance), LogFilter.entries)
    }
}
