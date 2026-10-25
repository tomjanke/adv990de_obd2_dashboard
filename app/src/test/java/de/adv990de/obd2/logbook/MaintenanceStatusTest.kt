package de.adv990de.obd2.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Die Uebersicht der Wartung (Entwurf `2026-10-20-logbook-design.md`): je Art der letzte Eintrag, die Kilometer seit dann (gegen den hoechsten Stand aller Eintraege) und der mittlere
 * Abstand zwischen den Eintraegen.
 */
class MaintenanceStatusTest {

    private fun item(items: List<MaintenanceItem>, type: LogType) = items.single { it.type == type }

    @Test
    fun `there is one item for each of the six kinds, in the order of the chips, also without entries`() {
        val items = maintenanceStatus(emptyList())

        assertEquals(
            listOf(LogType.CHAIN_SMALL, LogType.CHAIN_LARGE, LogType.SERVICE_SMALL, LogType.SERVICE_LARGE, LogType.TIRE_CHANGE, LogType.CHAIN_KIT_CHANGE),
            items.map { it.type },
        )
        items.forEach {
            assertNull(it.last)
            assertNull(it.kmSince)
            assertNull(it.averageSpacingKm)
            assertNull(it.currentKm)
            assertEquals(0, it.count)
        }
    }

    @Test
    fun `the kilometres since are counted from the highest odometer reading of any entry`() {
        val entries = listOf(
            logEntry(LogType.CHAIN_SMALL, km = 40_000),
            logEntry(LogType.FUEL, km = 41_250),
            logEntry(LogType.OIL, km = 41_100, amountMl = 100),
        )
        val items = maintenanceStatus(entries)

        assertEquals(41_250, item(items, LogType.CHAIN_SMALL).currentKm)
        assertEquals(1_250, item(items, LogType.CHAIN_SMALL).kmSince)
        assertEquals(40_000, item(items, LogType.CHAIN_SMALL).last!!.odometerKm)
    }

    @Test
    fun `the last entry of a kind is the one with the highest odometer reading`() {
        val entries = listOf(
            logEntry(LogType.SERVICE_LARGE, km = 30_000, id = 1),
            logEntry(LogType.SERVICE_LARGE, km = 42_000, id = 2),
            logEntry(LogType.SERVICE_LARGE, km = 36_000, id = 3),
        )
        val service = item(maintenanceStatus(entries), LogType.SERVICE_LARGE)

        assertEquals(2L, service.last!!.id)
        assertEquals(0, service.kmSince)
        assertEquals(3, service.count)
    }

    @Test
    fun `the mean spacing needs two entries and is the distance covered divided by the gaps`() {
        val one = item(maintenanceStatus(listOf(logEntry(LogType.CHAIN_LARGE, km = 30_000))), LogType.CHAIN_LARGE)
        val three = item(
            maintenanceStatus(listOf(logEntry(LogType.CHAIN_LARGE, km = 30_000), logEntry(LogType.CHAIN_LARGE, km = 31_000), logEntry(LogType.CHAIN_LARGE, km = 34_000))),
            LogType.CHAIN_LARGE,
        )

        assertNull(one.averageSpacingKm)
        // (34 000 - 30 000) km ueber zwei Abstaende
        assertEquals(2_000.0, three.averageSpacingKm!!, 1e-9)
    }

    @Test
    fun `two entries already give a mean spacing`() {
        val two = item(maintenanceStatus(listOf(logEntry(LogType.SERVICE_SMALL, km = 30_000), logEntry(LogType.SERVICE_SMALL, km = 31_000))), LogType.SERVICE_SMALL)

        assertEquals(1_000.0, two.averageSpacingKm!!, 1e-9)
    }

    @Test
    fun `a kind without an entry has no kilometres since, while the current reading is still known`() {
        val items = maintenanceStatus(listOf(logEntry(LogType.FUEL, km = 5_000)))

        assertNull(item(items, LogType.TIRE_CHANGE).kmSince)
        assertEquals(5_000, item(items, LogType.TIRE_CHANGE).currentKm)
    }

    @Test
    fun `the tire change carries its make through the last entry`() {
        val entries = listOf(
            logEntry(LogType.TIRE_CHANGE, km = 20_000, brand = "Alt", date = LocalDate.of(2025, 5, 1)),
            logEntry(LogType.TIRE_CHANGE, km = 37_000, brand = "Michelin Anakee", date = LocalDate.of(2026, 5, 1)),
        )

        assertEquals("Michelin Anakee", item(maintenanceStatus(entries), LogType.TIRE_CHANGE).last!!.brand)
    }

    @Test
    fun `the kinds are counted separately`() {
        val entries = listOf(
            logEntry(LogType.CHAIN_SMALL, km = 1_000),
            logEntry(LogType.CHAIN_SMALL, km = 2_000),
            logEntry(LogType.CHAIN_LARGE, km = 1_500),
        )
        val items = maintenanceStatus(entries)

        assertEquals(2, item(items, LogType.CHAIN_SMALL).count)
        assertEquals(1, item(items, LogType.CHAIN_LARGE).count)
        assertEquals(0, item(items, LogType.SERVICE_SMALL).count)
    }
}
