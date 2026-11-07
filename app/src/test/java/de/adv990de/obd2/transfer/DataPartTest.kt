package de.adv990de.obd2.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataPartTest {

    @Test
    fun theImportOrderPutsTheLogbookBeforeTheCounters() {
        assertEquals(
            listOf(DataPart.LOGBOOK, DataPart.TRIPS, DataPart.DTC_HISTORY, DataPart.SETTINGS, DataPart.COUNTERS),
            DataPart.IMPORT_ORDER,
        )
    }

    @Test
    fun theImportOrderHoldsEveryImportablePartOnceAndNeverTheAppLog() {
        assertEquals(DataPart.entries.filter { it.importable }.toSet(), DataPart.IMPORT_ORDER.toSet())
        assertEquals(DataPart.IMPORT_ORDER.size, DataPart.IMPORT_ORDER.toSet().size)
        assertFalse(DataPart.APP_LOG in DataPart.IMPORT_ORDER)
    }

    @Test
    fun everyPackageEntryIsUniqueAndKnown() {
        val names = DataPart.entries.map { it.packageEntry }
        assertEquals(names.size, names.toSet().size)
        assertTrue(PackageFormat.KNOWN_ENTRIES.containsAll(names))
        assertTrue(PackageFormat.MANIFEST in PackageFormat.KNOWN_ENTRIES)
        assertTrue(PackageFormat.TRIPS_META in PackageFormat.KNOWN_ENTRIES)
    }

    @Test
    fun thePackageEntryNamesAreTheContract() {
        assertEquals(
            setOf("manifest.json", "trips_meta.csv", "trips.csv", "logbook.csv", "settings.json", "counters.json", "dtc_history.csv", "applog.csv"),
            PackageFormat.KNOWN_ENTRIES,
        )
    }

    @Test
    fun theDefaultExportNeverHoldsTheAppLogAndIsAvailable() {
        assertFalse(DataPart.APP_LOG in DataPart.DEFAULT_EXPORT)
        assertTrue(DataPart.AVAILABLE.containsAll(DataPart.DEFAULT_EXPORT))
        assertEquals(setOf(DataPart.TRIPS, DataPart.LOGBOOK), DataPart.DEFAULT_EXPORT)
    }

    @Test
    fun phaseOneOffersTripsLogbookAndAppLog() {
        assertEquals(setOf(DataPart.TRIPS, DataPart.LOGBOOK, DataPart.APP_LOG), DataPart.AVAILABLE)
    }
}
