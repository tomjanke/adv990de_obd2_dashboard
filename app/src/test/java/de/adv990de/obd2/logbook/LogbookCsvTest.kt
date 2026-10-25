package de.adv990de.obd2.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class LogbookCsvTest {

    private val header = "date,type,odometer_km,amount_ml,full_tank,cost_eur,note,brand\n"

    private fun parse(text: String) = parseLogbookCsv(text, TEST_TODAY)

    private fun single(row: String): ParsedLogbook = parse(header + row)

    // ── Schreiben ─────────────────────────────────────────────────────────────

    @Test
    fun `the header names the eight columns`() {
        assertEquals(header, logbookCsvHeader())
        assertEquals(listOf("date", "type", "odometer_km", "amount_ml", "full_tank", "cost_eur", "note", "brand"), LOGBOOK_COLUMNS.toList())
    }

    @Test
    fun `a fill is written with the date, the amount in ml and the cost in euros with a point`() {
        val fill = logEntry(date = LocalDate.of(2026, 10, 2), km = 40_362, amountMl = 14_870, costCents = 3_315)
        assertEquals("2026-10-02,FUEL,40362,14870,1,33.15,,\n", logbookCsvLine(fill))
    }

    @Test
    fun `a partial fill is written with full_tank 0, an oil entry without full_tank and cost`() {
        assertEquals("2026-10-20,FUEL,1000,5000,0,,,\n", logbookCsvLine(logEntry(amountMl = 5_000, fullTank = false)))
        assertEquals("2024-05-14,OIL,27150,200,,,,\n", logbookCsvLine(logEntry(type = LogType.OIL, date = LocalDate.of(2024, 5, 14), km = 27_150, amountMl = 200)))
        assertEquals("2026-10-20,SERVICE_LARGE,1000,,,,,\n", logbookCsvLine(logEntry(type = LogType.SERVICE_LARGE)))
    }

    @Test
    fun `the cost keeps two decimals, also for small amounts`() {
        assertEquals("14.70", logbookCsvLine(logEntry(costCents = 1_470)).split(",")[5])
        assertEquals("15.00", logbookCsvLine(logEntry(costCents = 1_500)).split(",")[5])
        assertEquals("0.05", logbookCsvLine(logEntry(costCents = 5)).split(",")[5])
    }

    @Test
    fun `a note with a comma, a quote or a line break is quoted`() {
        assertEquals("2026-10-20,CHAIN_SMALL,1000,,,,\"a,b\",\n", logbookCsvLine(logEntry(type = LogType.CHAIN_SMALL, note = "a,b")))
        assertEquals("2026-10-20,CHAIN_SMALL,1000,,,,\"say \"\"hi\"\"\",\n", logbookCsvLine(logEntry(type = LogType.CHAIN_SMALL, note = "say \"hi\"")))
        assertEquals("2026-10-20,CHAIN_SMALL,1000,,,,\"x\ny\",\n", logbookCsvLine(logEntry(type = LogType.CHAIN_SMALL, note = "x\ny")))
    }

    @Test
    fun `a tire change is written with the brand in the last column, a chain kit change like chain care`() {
        assertEquals("2026-10-20,TIRE_CHANGE,1000,,,,,Michelin Anakee\n", logbookCsvLine(logEntry(type = LogType.TIRE_CHANGE)))
        assertEquals("2026-10-20,CHAIN_KIT_CHANGE,1000,,,,,\n", logbookCsvLine(logEntry(type = LogType.CHAIN_KIT_CHANGE)))
    }

    @Test
    fun `a brand with a comma or a quote is quoted`() {
        assertEquals(
            "2026-10-20,TIRE_CHANGE,1000,,,,,\"Pirelli \"\"Scorpion\"\", vorne\"\n",
            logbookCsvLine(logEntry(type = LogType.TIRE_CHANGE, brand = "Pirelli \"Scorpion\", vorne")),
        )
    }

    @Test
    fun `the file name carries the export time in UTC down to the millisecond`() {
        assertEquals("logbook_20261005T143005123Z.csv", logbookFileName(nowMillis = 1_791_210_605_123L))
        assertTrue(logbookFileName(1_000L) != logbookFileName(1_001L))
    }

    // ── Rundlauf ──────────────────────────────────────────────────────────────

    @Test
    fun `what is written is read back unchanged, for all nine kinds and awkward notes and brands`() {
        val entries = listOf(
            logEntry(LogType.FUEL, LocalDate.of(2026, 10, 2), 40_362, 14_870, true, 3_315, "voll, Shell"),
            logEntry(LogType.FUEL, LocalDate.of(2026, 10, 9), 40_500, 5_000, false, 1_099, null),
            logEntry(LogType.OIL, LocalDate.of(2026, 9, 24), 40_171, 150, null, null, "say \"hi\""),
            logEntry(LogType.COOLANT, LocalDate.of(2026, 9, 2), 39_851, 50, null, null, "zwei\nZeilen"),
            logEntry(LogType.CHAIN_SMALL, LocalDate.of(2026, 8, 1), 39_000, null, null, null, null),
            logEntry(LogType.CHAIN_LARGE, LocalDate.of(2026, 7, 1), 38_000, null, null, null, "Kettenspray"),
            logEntry(LogType.SERVICE_SMALL, LocalDate.of(2026, 6, 1), 37_000, null, null, null, null),
            logEntry(LogType.SERVICE_LARGE, LocalDate.of(2026, 5, 1), 36_000, null, null, null, "Ventile"),
            logEntry(LogType.TIRE_CHANGE, LocalDate.of(2026, 4, 1), 35_000, null, null, null, "hinten", brand = "Pirelli \"Scorpion\", 150/70"),
            logEntry(LogType.CHAIN_KIT_CHANGE, LocalDate.of(2026, 3, 1), 34_000, null, null, null, "Kette, Ritzel, Kettenrad"),
        )
        val text = logbookCsvHeader() + entries.joinToString("") { logbookCsvLine(it) }

        val parsed = parse(text)

        assertEquals(emptyList<RowError>(), parsed.errors)
        assertEquals(entries, parsed.entries.map { it.entry })
    }

    // ── Der Leser (RFC 4180) ──────────────────────────────────────────────────

    @Test
    fun `the reader splits simple, quoted and escaped fields`() {
        val rows = parseCsv("a,b,c\n\"x,y\",\"say \"\"hi\"\"\",z\n").map { it.fields }
        assertEquals(listOf(listOf("a", "b", "c"), listOf("x,y", "say \"hi\"", "z")), rows)
    }

    @Test
    fun `a quote in the middle of an unquoted field is plain text`() {
        assertEquals(listOf(listOf("5\" bolt", "x")), parseCsv("5\" bolt,x\n").map { it.fields })
    }

    @Test
    fun `a line break inside quotes stays in the field, the next record has the right line number`() {
        val records = parseCsv("a,b\n\"one\ntwo\",x\nlast,y\n")
        assertEquals(listOf("one\ntwo", "x"), records[1].fields)
        assertEquals(listOf(1, 2, 4), records.map { it.line })
    }

    @Test
    fun `windows line ends, a missing final line end and a byte order mark are fine`() {
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), parseCsv("a,b\r\nc,d").map { it.fields })
        assertEquals(listOf(listOf("a", "b")), parseCsv("\uFEFFa,b\n").map { it.fields })
    }

    @Test
    fun `empty fields stay empty and a blank line is a record with one empty field`() {
        assertEquals(listOf(listOf("a", "", "c")), parseCsv("a,,c\n").map { it.fields })
        assertEquals(listOf(listOf("a"), listOf(""), listOf("b")), parseCsv("a\n\nb\n").map { it.fields })
        assertEquals(listOf(listOf("a", "")), parseCsv("a,\n").map { it.fields })
    }

    @Test
    fun `a quote that is never closed is an error, not a silent truncation`() {
        try {
            parseCsv("a,b\n\"never closed,x\n")
            fail("an open quote must be reported")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("2"))
        }
    }

    // ── Lesen: Kopf und Spalten ───────────────────────────────────────────────

    @Test
    fun `the columns are found by name - any order, unknown ones are ignored`() {
        val text = "note,odometer_km,type,extra,date\nhi,1000,CHAIN_SMALL,zzz,2026-10-01\n"
        val parsed = parse(text)
        assertEquals(emptyList<RowError>(), parsed.errors)
        assertEquals(listOf(logEntry(LogType.CHAIN_SMALL, LocalDate.of(2026, 10, 1), 1_000, null, null, null, "hi")), parsed.entries.map { it.entry })
    }

    @Test
    fun `the optional columns may be missing altogether`() {
        val parsed = parse("date,type,odometer_km\n2026-10-01,SERVICE_SMALL,1000\n")
        assertEquals(listOf(logEntry(LogType.SERVICE_SMALL, LocalDate.of(2026, 10, 1), 1_000)), parsed.entries.map { it.entry })
    }

    @Test
    fun `a missing required column is reported by name, an empty file too`() {
        for (missing in listOf("date", "type", "odometer_km")) {
            val columns = listOf("date", "type", "odometer_km").filter { it != missing }
            try {
                parse(columns.joinToString(",") + "\n")
                fail("missing $missing must fail")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message, e.message!!.contains(missing))
            }
        }
        try { parse(""); fail("an empty file must fail") } catch (_: IllegalArgumentException) { }
        try { parse("\n\n"); fail("a file of blank lines must fail") } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `blank lines between the rows are skipped`() {
        val parsed = parse(header + "\n2026-10-01,CHAIN_SMALL,1000,,,,\n\n\n2026-10-02,CHAIN_LARGE,1100,,,,\n\n")
        assertEquals(emptyList<RowError>(), parsed.errors)
        assertEquals(2, parsed.entries.size)
        assertEquals(listOf(3, 6), parsed.entries.map { it.line })
    }

    // ── Lesen: Werte ──────────────────────────────────────────────────────────

    private fun reasonsOf(row: String): List<ImportReason> = single(row).errors.single().reasons

    @Test
    fun `an unreadable value is rejected with the column and the line number`() {
        assertEquals(listOf<ImportReason>(ImportReason.BadValue("date")), reasonsOf("2026-13-01,CHAIN_SMALL,1000,,,,"))
        assertEquals(listOf<ImportReason>(ImportReason.BadValue("date")), reasonsOf("01.10.2026,CHAIN_SMALL,1000,,,,"))
        assertEquals(listOf<ImportReason>(ImportReason.BadValue("type")), reasonsOf("2026-10-01,BRAKES,1000,,,,"))
        assertEquals(listOf<ImportReason>(ImportReason.BadValue("odometer_km")), reasonsOf("2026-10-01,CHAIN_SMALL,12a,,,,"))
        assertEquals(listOf<ImportReason>(ImportReason.BadValue("odometer_km")), reasonsOf("2026-10-01,CHAIN_SMALL,1000.5,,,,"))
        assertEquals(listOf<ImportReason>(ImportReason.BadValue("amount_ml")), reasonsOf("2026-10-01,OIL,1000,\"150,5\",,,"))
        assertEquals(listOf<ImportReason>(ImportReason.BadValue("full_tank")), reasonsOf("2026-10-01,FUEL,1000,10000,yes,,"))
        assertEquals(2, single("2026-13-01,CHAIN_SMALL,1000,,,,").errors.single().line)
    }

    @Test
    fun `several unreadable values in one row are all reported`() {
        val reasons = reasonsOf("nope,BRAKES,abc,,,,")
        assertEquals(setOf<ImportReason>(ImportReason.BadValue("date"), ImportReason.BadValue("type"), ImportReason.BadValue("odometer_km")), reasons.toSet())
    }

    @Test
    fun `the cost is read with a point or a comma, one or two decimals`() {
        fun cost(field: String) = single("2026-10-01,FUEL,1000,10000,1,$field,").entries.single().entry.costCents
        assertEquals(1_475, cost("14.75"))
        assertEquals(1_475, cost("\"14,75\""))
        assertEquals(1_470, cost("14.7"))
        assertEquals(1_500, cost("15"))
        assertEquals(1_500, cost("15.00"))
        assertEquals(5, cost("0.05"))
        assertNull(cost(""))
    }

    @Test
    fun `a cost with three decimals or a thousands separator is rejected`() {
        assertEquals(listOf<ImportReason>(ImportReason.BadValue("cost_eur")), reasonsOf("2026-10-01,FUEL,1000,10000,1,14.755,"))
        assertEquals(listOf<ImportReason>(ImportReason.BadValue("cost_eur")), reasonsOf("2026-10-01,FUEL,1000,10000,1,\"1.234,50\","))
        assertEquals(listOf<ImportReason>(ImportReason.BadValue("cost_eur")), reasonsOf("2026-10-01,FUEL,1000,10000,1,abc,"))
    }

    @Test
    fun `the rules of the form apply to every row - an error rejects the row, a warning is kept`() {
        assertEquals(listOf<ImportReason>(ImportReason.Rule(LogIssue.OdometerRange)), reasonsOf("2026-10-01,CHAIN_SMALL,1000000,,,,"))
        assertEquals(listOf<ImportReason>(ImportReason.Rule(LogIssue.DateInFuture)), reasonsOf("2026-10-21,CHAIN_SMALL,1000,,,,"))
        assertEquals(listOf<ImportReason>(ImportReason.Rule(LogIssue.AmountMissing)), reasonsOf("2026-10-01,OIL,1000,,,,"))
        assertEquals(listOf<ImportReason>(ImportReason.Rule(LogIssue.CostRange)), reasonsOf("2026-10-01,FUEL,1000,10000,1,-3.00,"))
        assertEquals(listOf<ImportReason>(ImportReason.Rule(LogIssue.CostRange)), reasonsOf("2026-10-01,OIL,1000,150,,5.00,"))
        val priced = single("2026-10-01,FUEL,1000,10000,1,4.90,")
        assertEquals(emptyList<RowError>(), priced.errors)
        assertEquals(listOf<LogIssue.Warning>(LogIssue.PriceImplausible(49)), priced.entries.single().warnings)
    }

    @Test
    fun `an empty full_tank means full for a fill and nothing for the other kinds`() {
        assertEquals(true, single("2026-10-01,FUEL,1000,10000,,,").entries.single().entry.fullTank)
        assertEquals(false, single("2026-10-01,FUEL,1000,10000,0,,").entries.single().entry.fullTank)
        assertNull(single("2026-10-01,OIL,1000,150,1,,").entries.single().entry.fullTank)
    }

    @Test
    fun `the amount of chain care and service is dropped, an empty note is no note`() {
        val entry = single("2026-10-01,CHAIN_LARGE,1000,999,,,   ").entries.single().entry
        assertNull(entry.amountMl)
        assertNull(entry.note)
    }

    @Test
    fun `good rows survive next to a rejected one`() {
        val parsed = parse(header + "2026-10-01,CHAIN_SMALL,1000,,,,\nbroken,CHAIN_SMALL,1000,,,,\n2026-10-03,CHAIN_LARGE,1200,,,,\n")
        assertEquals(listOf(2, 4), parsed.entries.map { it.line })
        assertEquals(listOf(3), parsed.errors.map { it.line })
    }

    // ── Lesen: Fabrikat und die beiden Wechsel ────────────────────────────────

    @Test
    fun `a tire change is read with its brand, a chain kit change without one`() {
        val tire = single("2026-10-01,TIRE_CHANGE,1000,,,,hinten,  Metzeler Karoo 4  ").entries.single().entry
        assertEquals(LogType.TIRE_CHANGE, tire.type)
        assertEquals("Metzeler Karoo 4", tire.brand)
        assertEquals("hinten", tire.note)
        assertNull(tire.amountMl)
        val kit = single("2026-10-01,CHAIN_KIT_CHANGE,1000,,,,,").entries.single().entry
        assertEquals(LogType.CHAIN_KIT_CHANGE, kit.type)
        assertNull(kit.brand)
    }

    @Test
    fun `a tire change without a brand is rejected, so is a brand on any other kind`() {
        assertEquals(listOf<ImportReason>(ImportReason.Rule(LogIssue.BrandMissing)), reasonsOf("2026-10-01,TIRE_CHANGE,1000,,,,,"))
        assertEquals(listOf<ImportReason>(ImportReason.Rule(LogIssue.BrandMissing)), reasonsOf("2026-10-01,TIRE_CHANGE,1000,,,,,   "))
        assertEquals(listOf<ImportReason>(ImportReason.Rule(LogIssue.BrandNotAllowed)), reasonsOf("2026-10-01,SERVICE_SMALL,1000,,,,,Michelin"))
        assertEquals(listOf<ImportReason>(ImportReason.Rule(LogIssue.BrandTooLong)), reasonsOf("2026-10-01,TIRE_CHANGE,1000,,,,,${"x".repeat(81)}"))
    }

    @Test
    fun `a file from before the brand column is still read, and its tire change is rejected`() {
        val old = "date,type,odometer_km,amount_ml,full_tank,cost_eur,note\n"
        assertEquals(emptyList<RowError>(), parse(old + "2026-10-01,CHAIN_KIT_CHANGE,1000,,,,\n").errors)
        assertEquals(
            listOf<ImportReason>(ImportReason.Rule(LogIssue.BrandMissing)),
            parse(old + "2026-10-01,TIRE_CHANGE,1000,,,,\n").errors.single().reasons,
        )
    }

    // ── Grenze ────────────────────────────────────────────────────────────────

    @Test
    fun `at most 20000 rows are read`() {
        val row = "2026-10-01,CHAIN_SMALL,1000,,,,\n"
        assertEquals(MAX_IMPORT_ROWS, parse(header + row.repeat(MAX_IMPORT_ROWS)).entries.size)
        try {
            parse(header + row.repeat(MAX_IMPORT_ROWS + 1))
            fail("one row more than the limit must fail")
        } catch (_: IllegalArgumentException) { }
    }
}
