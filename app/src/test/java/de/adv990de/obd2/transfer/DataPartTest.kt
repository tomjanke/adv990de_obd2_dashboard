package de.adv990de.obd2.transfer

import de.adv990de.obd2.track.TrackImportItem
import de.adv990de.obd2.track.TrackImportPlan
import de.adv990de.obd2.track.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataPartTest {

    @Test
    fun theImportOrderPutsTheLogbookBeforeTheCounters() {
        assertEquals(
            listOf(DataPart.LOGBOOK, DataPart.TRIPS, DataPart.TRACKS, DataPart.DTC_HISTORY, DataPart.SETTINGS, DataPart.COUNTERS),
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
            setOf("manifest.json", "trips_meta.csv", "trips.csv", "tracks.gpx", "logbook.csv", "settings.json", "counters.json", "dtc_history.csv", "applog.csv"),
            PackageFormat.KNOWN_ENTRIES,
        )
    }

    @Test
    fun theDefaultExportNeverHoldsTheAppLogAndIsAvailable() {
        assertFalse(DataPart.APP_LOG in DataPart.DEFAULT_EXPORT)
        assertTrue(DataPart.AVAILABLE.containsAll(DataPart.DEFAULT_EXPORT))
        assertEquals(setOf(DataPart.TRIPS, DataPart.LOGBOOK, DataPart.SETTINGS, DataPart.COUNTERS, DataPart.DTC_HISTORY), DataPart.DEFAULT_EXPORT)
    }

    @Test
    fun theExportOffersTheSixOldPartsAndNotYetTheTracks() {
        assertEquals(DataPart.entries.toSet() - DataPart.TRACKS, DataPart.AVAILABLE)
    }

    @Test
    fun aTracksPlanWithOnlyDuplicatesHasNothingNew() {
        val dup = TrackImportItem("Old", listOf(TrackPoint(null, 10.0, 20.0, null, null, null)), 0, null, null, duplicate = true)
        val fresh = dup.copy(name = "New", duplicate = false)

        assertFalse(PartPlan.Tracks(TrackImportPlan(listOf(dup), 0, 0)).hasNews)
        assertFalse(PartPlan.Tracks(TrackImportPlan(emptyList(), 0, 0)).hasNews)
        assertTrue(PartPlan.Tracks(TrackImportPlan(listOf(dup, fresh), 0, 0)).hasNews)
    }

    @Test
    fun theTracksAreNeverPreselected() {
        assertFalse(DataPart.TRACKS in DataPart.DEFAULT_EXPORT)
    }

    @Test
    fun theTracksCanBeImportedAlready() {
        assertTrue(DataPart.TRACKS.importable)
        assertEquals("tracks.gpx", DataPart.TRACKS.packageEntry)
        assertEquals(DataPart.entries.filter { it.importable }.toSet(), DataPart.IMPORT_AVAILABLE)
        assertTrue(DataPart.AVAILABLE.filter { it.importable }.all { it in DataPart.IMPORT_AVAILABLE })
    }
}
