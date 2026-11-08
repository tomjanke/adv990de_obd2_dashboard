package de.adv990de.obd2.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** org.json parst rekursiv ohne Tiefenlimit: ein `StackOverflowError` ist kein `Exception` und beendete die App. Die Tiefe wird vor dem Parsen gezaehlt. */
class JsonDepthTest {

    @Test
    fun `the two levels of the real files are fine`() {
        assertTrue(jsonDepthWithin("""{"format":1,"values":{"theme":"dark","slot_pids":"a,b,c,d"}}"""))
        assertTrue(jsonDepthWithin("""{"format":1,"fuel_km_since_fill":12.3}"""))
    }

    @Test
    fun `the limit itself passes, one more fails`() {
        assertTrue(jsonDepthWithin("[".repeat(MAX_JSON_DEPTH) + "]".repeat(MAX_JSON_DEPTH)))
        assertFalse(jsonDepthWithin("[".repeat(MAX_JSON_DEPTH + 1) + "]".repeat(MAX_JSON_DEPTH + 1)))
    }

    @Test
    fun `an unclosed deep nesting is rejected without reading to the end`() {
        assertFalse(jsonDepthWithin("[".repeat(100_000)))
    }

    @Test
    fun `brackets inside strings do not count, escaped quotes do not end a string`() {
        assertTrue(jsonDepthWithin("""{"a":"${"[".repeat(1000)}","b":"quote \" ${"{".repeat(1000)}"}"""))
        assertFalse(jsonDepthWithin("{\"a\":\"x\\\\\"" + "[".repeat(MAX_JSON_DEPTH + 1)))
    }

    @Test
    fun `closing brackets lower the depth again`() {
        assertTrue(jsonDepthWithin("[[]]".repeat(1000)))
    }

    @Test
    fun `a settings file nested 50000 deep is an IllegalArgumentException, not a crash`() {
        val e = assertThrows(IllegalArgumentException::class.java) { parseSettingsJson("[".repeat(50_000)) }
        assertEquals("not a settings file: nested too deeply", e.message)
    }

    @Test
    fun `a counters file nested 50000 deep is an IllegalArgumentException, not a crash`() {
        val e = assertThrows(IllegalArgumentException::class.java) { parseCountersJson("[".repeat(50_000)) }
        assertEquals("not a counters file: nested too deeply", e.message)
    }
}
