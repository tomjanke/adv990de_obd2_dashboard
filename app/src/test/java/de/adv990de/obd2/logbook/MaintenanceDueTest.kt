package de.adv990de.obd2.logbook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Faelligkeit der Wartung (Phase 3): aus dem letzten Eintrag, dem Intervall des Nutzers und dem aktuellen Stand ("faellig in N km"), dazu die Eingabe des Intervalls.
 * Alle Zahlen sind von Hand gerechnet.
 */
class MaintenanceDueTest {

    private fun item(type: LogType = LogType.CHAIN_SMALL, lastKm: Int? = 40_000, currentKm: Int? = 40_300): MaintenanceItem {
        val last = lastKm?.let { logEntry(type, km = it) }
        return MaintenanceItem(
            type = type,
            last = last,
            kmSince = if (last != null && currentKm != null) currentKm - last.odometerKm else null,
            averageSpacingKm = null,
            currentKm = currentKm,
            count = if (last == null) 0 else 1,
        )
    }

    // --- Faelligkeit ---

    @Test
    fun `without an interval nothing is planned`() {
        assertEquals(MaintenanceDue.NotSet, maintenanceDue(item(), intervalKm = null, currentKm = 40_300))
        assertEquals(MaintenanceDue.NotSet, maintenanceDue(item(), intervalKm = 0, currentKm = 40_300))
    }

    @Test
    fun `an interval without an entry has no baseline`() {
        assertEquals(MaintenanceDue.NoBaseline, maintenanceDue(item(lastKm = null), intervalKm = 1_000, currentKm = 40_300))
    }

    @Test
    fun `without a current reading there is no baseline either`() {
        assertEquals(MaintenanceDue.NoBaseline, maintenanceDue(item(currentKm = null), intervalKm = 1_000, currentKm = null))
    }

    @Test
    fun `the remaining distance is last entry plus interval minus the current reading`() {
        // 40 000 + 1 000 - 40 300 = 700
        assertEquals(MaintenanceDue.Planned(700, DueState.Ok), maintenanceDue(item(), intervalKm = 1_000, currentKm = 40_300))
    }

    @Test
    fun `an interval is due soon within the last tenth`() {
        // 1 000 km Intervall: ab 100 km Rest "bald"
        assertEquals(MaintenanceDue.Planned(101, DueState.Ok), maintenanceDue(item(), 1_000, 40_899))
        assertEquals(MaintenanceDue.Planned(100, DueState.Soon), maintenanceDue(item(), 1_000, 40_900))
        assertEquals(MaintenanceDue.Planned(1, DueState.Soon), maintenanceDue(item(), 1_000, 40_999))
    }

    @Test
    fun `it is overdue from zero remaining kilometres on`() {
        assertEquals(MaintenanceDue.Planned(0, DueState.Overdue), maintenanceDue(item(), 1_000, 41_000))
        assertEquals(MaintenanceDue.Planned(-250, DueState.Overdue), maintenanceDue(item(), 1_000, 41_250))
    }

    @Test
    fun `the soon window is a tenth of the interval, at least one kilometre`() {
        // Intervall 50 km: ein Zehntel waeren 5; Intervall 5 km (nur ueber die Grenzen der Eingabe hinaus denkbar): mindestens 1
        assertEquals(MaintenanceDue.Planned(5, DueState.Soon), maintenanceDue(item(lastKm = 1_000), 50, 1_045))
        assertEquals(MaintenanceDue.Planned(6, DueState.Ok), maintenanceDue(item(lastKm = 1_000), 50, 1_044))
        assertEquals(MaintenanceDue.Planned(1, DueState.Soon), maintenanceDue(item(lastKm = 1_000), 5, 1_004))
        assertEquals(MaintenanceDue.Planned(2, DueState.Ok), maintenanceDue(item(lastKm = 1_000), 5, 1_003))
    }

    @Test
    fun `the interval counts from the last entry of the kind, not from the first`() {
        val twoEntries = item(lastKm = 41_000)

        assertEquals(MaintenanceDue.Planned(700, DueState.Ok), maintenanceDue(twoEntries, 1_000, 41_300))
    }

    @Test
    fun `large numbers do not overflow`() {
        assertEquals(MaintenanceDue.Planned(100_000, DueState.Ok), maintenanceDue(item(lastKm = 999_999), 100_000, 999_999))
    }

    // --- Rows ---

    @Test
    fun `one row per item with its interval and due state, the estimate as the current reading`() {
        val items = listOf(
            item(LogType.CHAIN_SMALL, lastKm = 40_000),
            item(LogType.SERVICE_LARGE, lastKm = 30_000),
            item(LogType.TIRE_CHANGE, lastKm = null),
        )
        val rows = maintenanceRows(items, mapOf(LogType.CHAIN_SMALL to 1_000, LogType.TIRE_CHANGE to 20_000), estimatedKm = 40_950)

        assertEquals(listOf(LogType.CHAIN_SMALL, LogType.SERVICE_LARGE, LogType.TIRE_CHANGE), rows.map { it.item.type })
        assertEquals(MaintenanceDue.Planned(50, DueState.Soon), rows[0].due)
        assertEquals(1_000, rows[0].intervalKm)
        assertEquals(MaintenanceDue.NotSet, rows[1].due)
        assertEquals(null, rows[1].intervalKm)
        assertEquals(MaintenanceDue.NoBaseline, rows[2].due)
        // vor wie vielen Kilometern: der geschaetzte Stand minus der Stand des letzten Eintrags; ohne Eintrag nichts
        assertEquals(950, rows[0].kmSince)
        assertEquals(10_950, rows[1].kmSince)
        assertEquals(null, rows[2].kmSince)
    }

    @Test
    fun `without an estimate the logged reading of the item is used`() {
        val rows = maintenanceRows(listOf(item(lastKm = 40_000, currentKm = 40_300)), mapOf(LogType.CHAIN_SMALL to 1_000), estimatedKm = null)

        assertEquals(MaintenanceDue.Planned(700, DueState.Ok), rows.single().due)
        assertEquals(300, rows.single().kmSince)
    }

    @Test
    fun `an estimate below the logged reading is not used`() {
        val rows = maintenanceRows(listOf(item(lastKm = 40_000, currentKm = 40_300)), mapOf(LogType.CHAIN_SMALL to 1_000), estimatedKm = 40_100)

        assertEquals(MaintenanceDue.Planned(700, DueState.Ok), rows.single().due)
        assertEquals(300, rows.single().kmSince)
    }

    // --- Eingabe ---

    @Test
    fun `an empty or blank field clears the interval`() {
        assertEquals(MaintenanceIntervalInput.Cleared, parseMaintenanceInterval(""))
        assertEquals(MaintenanceIntervalInput.Cleared, parseMaintenanceInterval("   "))
    }

    @Test
    fun `a whole number inside the limits is valid`() {
        assertEquals(MaintenanceIntervalInput.Valid(1_000), parseMaintenanceInterval("1000"))
        assertEquals(MaintenanceIntervalInput.Valid(1_000), parseMaintenanceInterval(" 1000 "))
        assertEquals(MaintenanceIntervalInput.Valid(50), parseMaintenanceInterval("50"))
        assertEquals(MaintenanceIntervalInput.Valid(100_000), parseMaintenanceInterval("100000"))
        assertEquals(MaintenanceIntervalInput.Valid(500), parseMaintenanceInterval("0500"))
    }

    @Test
    fun `just outside the limits is invalid`() {
        assertEquals(MaintenanceIntervalInput.Invalid, parseMaintenanceInterval("49"))
        assertEquals(MaintenanceIntervalInput.Invalid, parseMaintenanceInterval("100001"))
        assertEquals(MaintenanceIntervalInput.Invalid, parseMaintenanceInterval("0"))
    }

    @Test
    fun `decimals, signs, units, other digits and too many characters are invalid`() {
        for (bad in listOf("1000,5", "1000.5", "-1000", "+1000", "1000 km", "10 00", "abc", "٣٢٠", "1e3", "0000500", "9999999999999999")) {
            assertEquals("'$bad'", MaintenanceIntervalInput.Invalid, parseMaintenanceInterval(bad))
        }
    }

    @Test
    fun `the limits are the documented ones`() {
        assertEquals(50, MaintenanceIntervalLimits.MIN_KM)
        assertEquals(100_000, MaintenanceIntervalLimits.MAX_KM)
    }
}
