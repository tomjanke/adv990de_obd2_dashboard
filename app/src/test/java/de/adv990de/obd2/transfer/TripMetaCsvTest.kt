package de.adv990de.obd2.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/** `trips_meta.csv`: FIN und Fehlercodes je Fahrt, drei Zustaende der Codes (nie ein "keine Codes" aus Unklarem). */
class TripMetaCsvTest {

    private val vin = "TESTVIN0000000001"

    private fun parse(text: String) = parseTripMetaCsv(StringReader(text))

    @Test
    fun `the header names the four columns of the contract`() {
        assertEquals("session,vin,dtcs_recorded,dtc_codes\n", tripMetaCsvHeader())
        assertEquals(listOf("session", "vin", "dtcs_recorded", "dtc_codes"), TRIP_META_COLUMNS.toList())
    }

    @Test
    fun `a trip with a VIN and codes survives the round trip`() {
        val meta = TripMeta(vin, "P0301,P0420?")
        val parsed = parse(tripMetaCsvHeader() + tripMetaCsvLine("7", meta))
        assertEquals(mapOf("7" to meta), parsed.byTrip)
        assertEquals(0, parsed.invalidVins)
        assertEquals(0, parsed.rejectedRows)
    }

    @Test
    fun `codes read and none found stay none found and not unrecorded`() {
        val meta = TripMeta(vin, "")
        val parsed = parse(tripMetaCsvHeader() + tripMetaCsvLine("7", meta))
        assertEquals("", parsed.byTrip.getValue("7").dtcCodes)
    }

    @Test
    fun `codes not recorded stay not recorded`() {
        val meta = TripMeta(vin, null)
        val line = tripMetaCsvLine("7", meta)
        assertEquals("7,$vin,0,\n", line)
        assertNull(parse(tripMetaCsvHeader() + line).byTrip.getValue("7").dtcCodes)
    }

    @Test
    fun `a VIN without codes and codes without a VIN both survive`() {
        val text = tripMetaCsvHeader() + tripMetaCsvLine("1", TripMeta(vin, null)) + tripMetaCsvLine("2", TripMeta(null, "P0301"))
        val parsed = parse(text)
        assertEquals(TripMeta(vin, null), parsed.byTrip.getValue("1"))
        assertEquals(TripMeta(null, "P0301"), parsed.byTrip.getValue("2"))
    }

    @Test
    fun `an empty meta is told apart from a recorded one`() {
        assertTrue(TripMeta(null, null).isEmpty)
        assertFalse(TripMeta(null, "").isEmpty)
        assertFalse(TripMeta(vin, null).isEmpty)
    }

    @Test
    fun `an invalid VIN is dropped and counted but the row and its codes stay`() {
        for (bad in listOf("TESTVIN000000001", "testvin0000000001", "TESTVIN-000000001", "TESTVIN00000000012")) {
            val parsed = parse("session,vin,dtcs_recorded,dtc_codes\n7,$bad,1,P0301\n")
            assertEquals("for $bad", TripMeta(null, "P0301"), parsed.byTrip.getValue("7"))
            assertEquals("for $bad", 1, parsed.invalidVins)
            assertEquals(0, parsed.rejectedRows)
        }
    }

    @Test
    fun `dtcs_recorded 0 ignores a code text`() {
        val parsed = parse("session,vin,dtcs_recorded,dtc_codes\n7,,0,P0301\n")
        assertNull(parsed.byTrip.getValue("7").dtcCodes)
    }

    @Test
    fun `anything but 1 in dtcs_recorded means not recorded`() {
        for (flag in listOf("x", "", "2", "true", " ")) {
            val parsed = parse("session,vin,dtcs_recorded,dtc_codes\n7,$vin,$flag,P0301\n")
            assertNull("flag '$flag'", parsed.byTrip.getValue("7").dtcCodes)
        }
    }

    @Test
    fun `invalid codes fall away and the rest stays`() {
        val parsed = parse("session,vin,dtcs_recorded,dtc_codes\n7,,1,\"P0301,XYZ,p0420,P0420?\"\n")
        assertEquals("P0301,P0420?", parsed.byTrip.getValue("7").dtcCodes)
    }

    @Test
    fun `only invalid codes mean read and none found`() {
        val parsed = parse("session,vin,dtcs_recorded,dtc_codes\n7,,1,garbage\n")
        assertEquals("", parsed.byTrip.getValue("7").dtcCodes)
    }

    @Test
    fun `an empty session is rejected`() {
        val parsed = parse("session,vin,dtcs_recorded,dtc_codes\n,$vin,1,\n  ,$vin,1,\n8,$vin,1,\n")
        assertEquals(setOf("8"), parsed.byTrip.keys)
        assertEquals(2, parsed.rejectedRows)
    }

    @Test
    fun `the first row of a duplicated session wins`() {
        val parsed = parse("session,vin,dtcs_recorded,dtc_codes\n7,$vin,1,P0301\n7,,0,\n")
        assertEquals(TripMeta(vin, "P0301"), parsed.byTrip.getValue("7"))
        assertEquals(1, parsed.duplicateRows)
    }

    @Test
    fun `the session is trimmed`() {
        assertEquals(setOf("7"), parse("session,vin\n 7 ,$vin\n").byTrip.keys)
    }

    @Test
    fun `optional columns may be missing`() {
        assertEquals(TripMeta(vin, null), parse("session,vin\n7,$vin\n").byTrip.getValue("7"))
        assertEquals(TripMeta(null, null), parse("session\n7\n").byTrip.getValue("7"))
        assertEquals(TripMeta(null, ""), parse("session,dtcs_recorded\n7,1\n").byTrip.getValue("7"))
    }

    @Test
    fun `columns are read by name in any order and unknown ones are ignored`() {
        val parsed = parse("dtc_codes,extra,session,dtcs_recorded,vin\nP0301,zzz,7,1,$vin\n")
        assertEquals(TripMeta(vin, "P0301"), parsed.byTrip.getValue("7"))
    }

    @Test
    fun `a missing session column is an error`() {
        assertThrows(IllegalArgumentException::class.java) { parse("vin,dtcs_recorded\n$vin,1\n") }
        assertThrows(IllegalArgumentException::class.java) { parse("") }
    }

    @Test
    fun `a field in quotes may carry a comma and a BOM is skipped`() {
        val parsed = parse("﻿session,vin,dtcs_recorded,dtc_codes\n7,$vin,1,\"P0301,P0420?\"\n")
        assertEquals("P0301,P0420?", parsed.byTrip.getValue("7").dtcCodes)
    }

    @Test
    fun `blank lines are skipped`() {
        val parsed = parse("session,vin\n\n7,$vin\n\n")
        assertEquals(1, parsed.byTrip.size)
        assertEquals(0, parsed.rejectedRows)
    }
}
