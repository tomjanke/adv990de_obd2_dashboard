package de.adv990de.obd2.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** `counters.json` (Format 1): Runde hin und zurueck, die Grenzen jedes Werts, Anker und Kilometer nur zusammen, und die Anker-Regel `decideOdometer`. */
class CountersFileTest {

    private fun parse(vararg pairs: String) = parseCountersJson("{\"format\":1," + pairs.joinToString(",") + "}")

    @Test
    fun `a full set survives the round trip`() {
        val json = countersToJson(CounterValues(12.3f, 12_345, 4.2f))

        val parsed = parseCountersJson(json)

        assertEquals(12.3f, parsed.values.fuelKmSinceFill)
        assertEquals(12_345, parsed.values.gpsAnchorKm)
        assertEquals(4.2f, parsed.values.gpsKm)
        assertEquals(emptyList<String>(), parsed.rejectedKeys)
    }

    @Test
    fun `the written text has the contract keys in this order, and unset keys are missing`() {
        assertEquals(
            "{\"format\":1,\"fuel_km_since_fill\":12.3,\"logbook_gps_anchor_km\":12345,\"logbook_gps_km\":4.2}",
            countersToJson(CounterValues(12.3f, 12_345, 4.2f)),
        )
        assertEquals("{\"format\":1}", countersToJson(CounterValues(null, null, null)))
        assertEquals("{\"format\":1,\"fuel_km_since_fill\":0.0}", countersToJson(CounterValues(0f, null, null)))
    }

    @Test
    fun `an unusable float is not written`() {
        assertEquals("{\"format\":1}", countersToJson(CounterValues(Float.NaN, null, null)))
        assertEquals("{\"format\":1}", countersToJson(CounterValues(Float.POSITIVE_INFINITY, null, null)))
    }

    @Test
    fun `the bounds of the tank counter`() {
        assertEquals(0f, parse("\"fuel_km_since_fill\":0").values.fuelKmSinceFill)
        assertEquals(1_000_000f, parse("\"fuel_km_since_fill\":1000000").values.fuelKmSinceFill)
        for (bad in listOf("-0.1", "1000001", "\"NaN\"", "\"12\"", "true", "null")) {
            val p = parse("\"fuel_km_since_fill\":$bad")
            assertNull(bad, p.values.fuelKmSinceFill)
            assertEquals(bad, listOf("fuel_km_since_fill"), p.rejectedKeys)
        }
    }

    @Test
    fun `the bounds of the anchor and of the kilometres`() {
        val low = parse("\"logbook_gps_anchor_km\":0", "\"logbook_gps_km\":0")
        assertEquals(0, low.values.gpsAnchorKm)
        assertEquals(0f, low.values.gpsKm)
        val high = parse("\"logbook_gps_anchor_km\":999999", "\"logbook_gps_km\":1000000")
        assertEquals(999_999, high.values.gpsAnchorKm)
        assertEquals(1_000_000f, high.values.gpsKm)
        for (bad in listOf("-1", "1000000", "1.5", "\"5\"")) {
            val p = parse("\"logbook_gps_anchor_km\":$bad", "\"logbook_gps_km\":3")
            assertNull(bad, p.values.gpsAnchorKm)
            assertNull(bad, p.values.gpsKm)
        }
        for (bad in listOf("-0.5", "1000001", "\"NaN\"")) {
            val p = parse("\"logbook_gps_anchor_km\":10", "\"logbook_gps_km\":$bad")
            assertNull(bad, p.values.gpsAnchorKm)
            assertNull(bad, p.values.gpsKm)
        }
    }

    @Test
    fun `a whole number written as a double is an anchor`() {
        val p = parse("\"logbook_gps_anchor_km\":12345.0", "\"logbook_gps_km\":1")

        assertEquals(12_345, p.values.gpsAnchorKm)
    }

    @Test
    fun `an anchor without kilometres drops both and names the key`() {
        val p = parse("\"logbook_gps_anchor_km\":12345")

        assertNull(p.values.gpsAnchorKm)
        assertNull(p.values.gpsKm)
        assertEquals(listOf("logbook_gps_anchor_km"), p.rejectedKeys)
    }

    @Test
    fun `kilometres without an anchor drop both and name the key`() {
        val p = parse("\"logbook_gps_km\":4.2")

        assertNull(p.values.gpsAnchorKm)
        assertNull(p.values.gpsKm)
        assertEquals(listOf("logbook_gps_km"), p.rejectedKeys)
    }

    @Test
    fun `when one of the pair is invalid both are dropped and both are named`() {
        val p = parse("\"logbook_gps_anchor_km\":-1", "\"logbook_gps_km\":4.2")

        assertNull(p.values.gpsAnchorKm)
        assertNull(p.values.gpsKm)
        assertEquals(listOf("logbook_gps_anchor_km", "logbook_gps_km"), p.rejectedKeys)
    }

    @Test
    fun `a bad pair does not take the tank counter with it`() {
        val p = parse("\"fuel_km_since_fill\":7.5", "\"logbook_gps_anchor_km\":-1", "\"logbook_gps_km\":4.2")

        assertEquals(7.5f, p.values.fuelKmSinceFill)
    }

    @Test
    fun `a file without any counter is valid and empty`() {
        val p = parseCountersJson("{\"format\":1}")

        assertNull(p.values.fuelKmSinceFill)
        assertNull(p.values.gpsAnchorKm)
        assertEquals(emptyList<String>(), p.rejectedKeys)
    }

    @Test
    fun `something that is not a JSON object is refused`() {
        assertThrows(IllegalArgumentException::class.java) { parseCountersJson("not json") }
        assertThrows(IllegalArgumentException::class.java) { parseCountersJson("[1,2]") }
    }

    @Test
    fun `the anchor rule`() {
        val values = CounterValues(1f, 40_000, 5f)

        assertEquals(OdometerDecision.APPLY, decideOdometer(values, 40_000))
        assertEquals(OdometerDecision.SKIP_ANCHOR_MISMATCH, decideOdometer(values, 40_001))
        assertEquals(OdometerDecision.SKIP_ANCHOR_MISMATCH, decideOdometer(values, 39_999))
        assertEquals(OdometerDecision.SKIP_NO_LOGBOOK, decideOdometer(values, null))
        assertEquals(OdometerDecision.ABSENT, decideOdometer(CounterValues(1f, null, null), 40_000))
        assertEquals(OdometerDecision.ABSENT, decideOdometer(CounterValues(null, null, null), null))
    }
}
