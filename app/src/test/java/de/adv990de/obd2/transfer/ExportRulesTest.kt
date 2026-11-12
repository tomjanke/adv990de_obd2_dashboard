package de.adv990de.obd2.transfer

import de.adv990de.obd2.tripimport.tripImportRowLimit
import de.adv990de.obd2.tripimport.TripFileException
import de.adv990de.obd2.tripimport.readTripCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    // --- Die Fahrten eines Exports gegen die Grenze des Imports ---

    @Test fun fewRowsAreFine() {
        assertEquals(TripExportSize.OK, tripExportSize(0))
        assertEquals(TripExportSize.OK, tripExportSize(224_774))
        assertEquals("ein unsinniger Wert ist keine Warnung", TripExportSize.OK, tripExportSize(-5))
    }

    @Test fun theNoticeBeginsAtExactlyEightyPercentOfTheLimit() {
        assertEquals(TripExportSize.OK, tripExportSize(79, limit = 100))
        assertEquals(TripExportSize.NEAR_LIMIT, tripExportSize(80, limit = 100))
        // Ein Limit, das sich nicht glatt teilt: 80 % von 7 sind 5,6 — 5 Zeilen liegen darunter, 6 darueber.
        assertEquals(TripExportSize.OK, tripExportSize(5, limit = 7))
        assertEquals(TripExportSize.NEAR_LIMIT, tripExportSize(6, limit = 7))
    }

    @Test fun exactlyTheLimitIsStillReadable_oneRowMoreIsNot() {
        assertEquals(TripExportSize.NEAR_LIMIT, tripExportSize(100, limit = 100))
        assertEquals(TripExportSize.OVER_LIMIT, tripExportSize(101, limit = 100))
    }

    @Test fun theDefaultLimitIsTheOneOfTheImport() {
        val limit = tripImportRowLimit().toLong()
        assertEquals(TripExportSize.OK, tripExportSize(limit * 80 / 100 - 1))
        assertEquals(TripExportSize.NEAR_LIMIT, tripExportSize(limit * 80 / 100))
        assertEquals(TripExportSize.NEAR_LIMIT, tripExportSize(limit))
        assertEquals(TripExportSize.OVER_LIMIT, tripExportSize(limit + 1))
        assertEquals(80, TRIP_EXPORT_NOTICE_PERCENT)
    }

    @Test fun hugeRowCountsDoNotOverflow() {
        assertEquals(TripExportSize.OVER_LIMIT, tripExportSize(Int.MAX_VALUE.toLong() * 4))
        assertEquals(TripExportSize.OK, tripExportSize(30_000_000, limit = Int.MAX_VALUE))
        assertEquals(TripExportSize.NEAR_LIMIT, tripExportSize(1_800_000_000, limit = Int.MAX_VALUE))
    }

    /** Die Regel und der Leser muessen dasselbe sagen: was die Seite „noch einlesbar“ nennt, liest [readTripCsv]; was sie „zu viel“ nennt, lehnt er ab. */
    @Test fun theRuleAgreesWithWhatTheImportAccepts() {
        val now = 1_700_000_000_000L
        fun file(rows: Int) = buildString {
            append("timestamp,pid,value\n")
            repeat(rows) { append(now - 1_000_000 + it).append(",010C,1500.0\n") }
        }
        val limit = 10
        assertEquals(TripExportSize.NEAR_LIMIT, tripExportSize(limit.toLong(), limit))
        assertEquals(limit, readTripCsv(file(limit).reader(), now, maxRows = limit).trips.sumOf { it.rows.size })
        assertEquals(TripExportSize.OVER_LIMIT, tripExportSize(limit + 1L, limit))
        val e = assertThrows(TripFileException::class.java) { readTripCsv(file(limit + 1).reader(), now, maxRows = limit) }
        assertEquals(TripFileException.Kind.TOO_MANY_ROWS, e.kind)
    }

    @Test fun thePercentIsRoundedDown() {
        assertEquals(0, tripExportPercent(0, limit = 200))
        assertEquals(80, tripExportPercent(160, limit = 200))
        assertEquals(99, tripExportPercent(199, limit = 200))
        assertEquals(100, tripExportPercent(200, limit = 200))
        assertEquals(106, tripExportPercent(213, limit = 200))
        assertEquals(0, tripExportPercent(-3, limit = 200))
        assertEquals(86, tripExportPercent(tripImportRowLimit() * 86L / 100))
        assertEquals("eine Grenze von 0 teilt nicht durch null", 500, tripExportPercent(5, limit = 0))
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
        assertEquals(256 * mib, PackageFormat.entryLimit("trips.csv"))
        assertEquals(5 * mib, PackageFormat.entryLimit("logbook.csv"))
        assertEquals(320 * mib, PackageFormat.entryLimit("applog.csv"))
        assertEquals(0L, PackageFormat.entryLimit("evil.bin"))
    }
}
