package de.adv990de.obd2.transfer

import de.adv990de.obd2.data.DtcHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.StringReader

class DtcHistoryCsvTest {

    private val now = 1_790_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun entry(code: String = "P0301", first: Long = now - 5 * day, last: Long = now - day, pending: Boolean = false) = DtcHistoryEntry(code, first, last, pending)

    private fun parse(vararg lines: String) = parseDtcHistoryCsv(StringReader((listOf("code,first_seen,last_seen,last_was_pending") + lines).joinToString("\n") + "\n"), now)

    @Test
    fun `the header names the contract columns`() {
        assertEquals("code,first_seen,last_seen,last_was_pending\n", dtcHistoryCsvHeader())
    }

    @Test
    fun `entries survive a round trip including the pending flag`() {
        val entries = listOf(entry("P0301"), entry("P0420", pending = true), entry("U0100", first = now - 9 * day, last = now - 9 * day))
        val text = dtcHistoryCsvHeader() + entries.joinToString("") { dtcHistoryCsvLine(it) }

        val parsed = parseDtcHistoryCsv(StringReader(text), now)

        assertEquals(entries, parsed.entries)
        assertEquals(0, parsed.rejected)
    }

    @Test
    fun `the pending flag is written as 1 and 0`() {
        assertEquals("P0301,${now - 5 * day},${now - day},1\n", dtcHistoryCsvLine(entry(pending = true)))
        assertEquals("P0301,${now - 5 * day},${now - day},0\n", dtcHistoryCsvLine(entry(pending = false)))
    }

    @Test
    fun `a row is rejected for a bad code`() {
        for (code in listOf("P03", "X0301", "p0301", "P030G", "P03011", "")) {
            val parsed = parse("$code,${now - 2 * day},${now - day},0")
            assertEquals("code '$code'", 0, parsed.entries.size)
            assertEquals("code '$code'", 1, parsed.rejected)
        }
    }

    @Test
    fun `a row is rejected for a bad time`() {
        assertEquals(1, parse("P0301,0,${now - day},0").rejected)
        assertEquals(1, parse("P0301,${now - 2 * day},0,0").rejected)
        assertEquals(1, parse("P0301,${now - 2 * day},${now + 2 * day},0").rejected)
        assertEquals(1, parse("P0301,${now + 2 * day},${now + 2 * day},0").rejected)
        assertEquals(1, parse("P0301,abc,${now - day},0").rejected)
        assertEquals(1, parse("P0301,${now - 2 * day},1.5,0").rejected)
        assertEquals(1, parse("P0301,,${now - day},0").rejected)
    }

    @Test
    fun `a day ahead of now is still accepted and 2000-01-01 is the earliest time`() {
        assertEquals(1, parse("P0301,$now,${now + day},0").entries.size)
        assertEquals(1, parse("P0301,946684800000,946684800000,0").entries.size)
        assertEquals(1, parse("P0301,946684799999,946684800000,0").rejected)
    }

    @Test
    fun `first seen after last seen is rejected`() {
        val parsed = parse("P0301,${now - day},${now - 2 * day},0")
        assertEquals(0, parsed.entries.size)
        assertEquals(1, parsed.rejected)
    }

    @Test
    fun `the pending flag accepts 1 0 true and false and rejects anything else`() {
        for ((text, expected) in listOf("1" to true, "0" to false, "true" to true, "false" to false, "TRUE" to true)) {
            assertEquals(text, expected, parse("P0301,${now - 2 * day},${now - day},$text").entries.single().lastWasPending)
        }
        assertEquals(1, parse("P0301,${now - 2 * day},${now - day},x").rejected)
        assertEquals(1, parse("P0301,${now - 2 * day},${now - day},").rejected)
    }

    @Test
    fun `duplicate codes in the file are merged and not counted twice`() {
        val parsed = parse(
            "P0301,${now - 5 * day},${now - 3 * day},0",
            "P0301,${now - 9 * day},${now - day},1",
            "P0420,${now - 2 * day},${now - 2 * day},0",
        )

        assertEquals(2, parsed.entries.size)
        assertEquals(DtcHistoryEntry("P0301", now - 9 * day, now - day, true), parsed.entries.first { it.code == "P0301" })
        assertEquals(0, parsed.rejected)
    }

    @Test
    fun `a later duplicate row that does not dominate still merges with the earlier one`() {
        val parsed = parse(
            "P0301,${now - 9 * day},${now - day},1",
            "P0301,${now - 5 * day},${now - 3 * day},0",
        )

        assertEquals(listOf(DtcHistoryEntry("P0301", now - 9 * day, now - day, true)), parsed.entries)
    }

    @Test
    fun `a missing code column is an error`() {
        assertThrows(IllegalArgumentException::class.java) { parseDtcHistoryCsv(StringReader("first_seen,last_seen\n1,2\n"), now) }
        assertThrows(IllegalArgumentException::class.java) { parseDtcHistoryCsv(StringReader(""), now) }
    }

    @Test
    fun `columns are read by name and a BOM and blank lines are tolerated`() {
        val parsed = parseDtcHistoryCsv(StringReader("﻿last_was_pending,last_seen,first_seen,code,extra\n\n1,${now - day},${now - 2 * day},P0301,x\n"), now)

        assertEquals(listOf(DtcHistoryEntry("P0301", now - 2 * day, now - day, true)), parsed.entries)
    }

    @Test
    fun `a row with too few fields is rejected`() {
        assertEquals(1, parse("P0301,${now - day}").rejected)
    }
}
