package de.adv990de.obd2.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExportRulesTest {

    @Test fun csvPartOfEachCsvKind() {
        for (part in listOf(DataPart.TRIPS, DataPart.LOGBOOK, DataPart.APP_LOG)) assertEquals(part, csvPartOf(setOf(part)))
    }

    @Test fun csvPartOfNeedsExactlyOne() {
        assertNull(csvPartOf(setOf(DataPart.TRIPS, DataPart.LOGBOOK)))
        assertNull(csvPartOf(emptySet()))
    }

    @Test fun csvPartOfIgnoresNonCsvParts() {
        assertNull(csvPartOf(setOf(DataPart.SETTINGS)))
        assertNull(csvPartOf(setOf(DataPart.TRIPS, DataPart.SETTINGS)))
        assertNull(csvPartOf(setOf(DataPart.COUNTERS)))
        assertNull(csvPartOf(setOf(DataPart.DTC_HISTORY)))
    }

    @Test fun effectiveFormatFallsBackToPackage() {
        assertEquals(ExportFormat.PACKAGE, effectiveFormat(setOf(DataPart.TRIPS, DataPart.LOGBOOK), ExportFormat.CSV))
        assertEquals(ExportFormat.PACKAGE, effectiveFormat(setOf(DataPart.TRIPS, DataPart.SETTINGS), ExportFormat.CSV))
        assertEquals(ExportFormat.PACKAGE, effectiveFormat(emptySet(), ExportFormat.CSV))
    }

    @Test fun effectiveFormatKeepsCsvForOnePart() {
        assertEquals(ExportFormat.CSV, effectiveFormat(setOf(DataPart.LOGBOOK), ExportFormat.CSV))
    }

    @Test fun effectiveFormatKeepsPackage() {
        assertEquals(ExportFormat.PACKAGE, effectiveFormat(setOf(DataPart.LOGBOOK), ExportFormat.PACKAGE))
        assertEquals(ExportFormat.PACKAGE, effectiveFormat(setOf(DataPart.TRIPS, DataPart.LOGBOOK), ExportFormat.PACKAGE))
    }

    @Test fun fileNameFromAFixedMoment() {
        // 2026-11-07T12:00:00.123Z
        val ms = java.time.Instant.parse("2026-11-07T12:00:00.123Z").toEpochMilli()
        assertEquals("obd-dashboard_20261107T120000123Z.zip", PackageFormat.fileName(ms))
    }

    @Test fun entryLimits() {
        val kib = 1024L
        val mib = 1024L * kib
        assertEquals(256 * kib, PackageFormat.entryLimit("manifest.json"))
        assertEquals(256 * kib, PackageFormat.entryLimit("settings.json"))
        assertEquals(256 * kib, PackageFormat.entryLimit("counters.json"))
        assertEquals(256 * kib, PackageFormat.entryLimit("dtc_history.csv"))
        assertEquals(4 * mib, PackageFormat.entryLimit("trips_meta.csv"))
        assertEquals(96 * mib, PackageFormat.entryLimit("trips.csv"))
        assertEquals(5 * mib, PackageFormat.entryLimit("logbook.csv"))
        assertEquals(128 * mib, PackageFormat.entryLimit("applog.csv"))
        assertEquals(0L, PackageFormat.entryLimit("evil.bin"))
    }
}
