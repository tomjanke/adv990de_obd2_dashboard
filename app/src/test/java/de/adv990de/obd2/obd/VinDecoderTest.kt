package de.adv990de.obd2.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VinDecoderTest {

    private val vin = "WP0ZZZ99ZTS392124"

    // ── Antwortformen echter Adapter (ElmController liefert alle Zeilen, mit Leerzeichen verbunden) ──

    @Test
    fun `decodes a CAN multi-frame answer with length line and frame markers`() {
        // "014" = 20 Bytes, dann "0:" / "1:" / "2:" je Folgeframe; 49 02 01 = Modus, PID, Anzahl der Datenelemente.
        val raw = "014 0: 49 02 01 57 50 30 1: 5A 5A 5A 39 39 5A 54 2: 53 33 39 32 31 32 34"
        assertEquals(vin, VinDecoder.decode(raw))
    }

    @Test
    fun `decodes a K-line answer with a header and zero padding on every line`() {
        // Je Zeile 49 02 <Nr> + 4 Datenbytes; die ersten drei Bytes sind Auffuellung (00).
        val raw = "49 02 01 00 00 00 57 49 02 02 50 30 5A 5A 49 02 03 5A 39 39 5A " +
            "49 02 04 54 53 33 39 49 02 05 32 31 32 34"
        assertEquals(vin, VinDecoder.decode(raw))
    }

    @Test
    fun `decodes an answer without spaces (ATS0)`() {
        val raw = "014 0:490201575030 1:5A5A5A39395A54 2:53333932313234"
        assertEquals(vin, VinDecoder.decode(raw))
    }

    @Test
    fun `decodes a single line answer without length line or markers`() {
        val raw = "49 02 01 57 50 30 5A 5A 5A 39 39 5A 54 53 33 39 32 31 32 34"
        assertEquals(vin, VinDecoder.decode(raw))
    }

    @Test
    fun `ignores the search banner and bus init text in front of the answer`() {
        assertEquals(vin, VinDecoder.decode("SEARCHING... 014 0: 49 02 01 57 50 30 1: 5A 5A 5A 39 39 5A 54 2: 53 33 39 32 31 32 34"))
        assertEquals(vin, VinDecoder.decode("BUS INIT: OK 49 02 01 00 00 00 57 49 02 02 50 30 5A 5A 49 02 03 5A 39 39 5A 49 02 04 54 53 33 39 49 02 05 32 31 32 34"))
    }

    @Test
    fun `accepts lower case hex`() {
        assertEquals(vin, VinDecoder.decode("014 0: 49 02 01 57 50 30 1: 5a 5a 5a 39 39 5a 54 2: 53 33 39 32 31 32 34"))
    }

    @Test
    fun `two ECUs answering with the same VIN give one VIN`() {
        val one = "49 02 01 57 50 30 5A 5A 5A 39 39 5A 54 53 33 39 32 31 32 34"
        assertEquals(vin, VinDecoder.decode("$one $one"))
    }

    // ── Alles, was keine FIN ist, wird null — nie ein Hexdump oder Fehlertext ───────────────────────

    @Test
    fun `adapter and ECU error texts give null`() {
        for (text in listOf("NO DATA", "UNABLE TO CONNECT", "SEARCHING... UNABLE TO CONNECT", "CAN ERROR", "?", "STOPPED", "BUS INIT: ...ERROR", "")) {
            assertNull("'$text'", VinDecoder.decode(text))
        }
    }

    @Test
    fun `an answer to another request gives null`() {
        assertNull(VinDecoder.decode("41 0C 1A F8"))
        assertNull(VinDecoder.decode("43 01 33"))
    }

    @Test
    fun `a wrong length gives null`() {
        // 16 statt 17 Zeichen
        assertNull(VinDecoder.decode("49 02 01 57 50 30 5A 5A 5A 39 39 5A 54 53 33 39 32 31 32"))
        // 18 Zeichen, kein Vielfaches von 17
        assertNull(VinDecoder.decode("49 02 01 57 50 30 5A 5A 5A 39 39 5A 54 53 33 39 32 31 32 34 35"))
    }

    @Test
    fun `non printable or non alphanumeric data gives null`() {
        // 0x07 mitten in den Daten
        assertNull(VinDecoder.decode("49 02 01 57 50 30 5A 5A 5A 39 39 5A 54 53 07 39 32 31 32 34"))
        // Leerzeichen (0x20) statt Buchstabe
        assertNull(VinDecoder.decode("49 02 01 57 50 30 5A 5A 5A 39 39 5A 54 53 33 39 32 31 20 34"))
    }

    @Test
    fun `an unknown word in the answer gives null instead of guessing`() {
        assertNull(VinDecoder.decode("HELLO 49 02 01 57 50 30 5A 5A 5A 39 39 5A 54 53 33 39 32 31 32 34"))
    }

    @Test
    fun `two different VINs from two ECUs give null`() {
        val other = "49 02 01 57 50 30 5A 5A 5A 39 39 5A 54 53 33 39 32 31 32 35"
        val one = "49 02 01 57 50 30 5A 5A 5A 39 39 5A 54 53 33 39 32 31 32 34"
        assertNull(VinDecoder.decode("$one $other"))
    }

    @Test
    fun `a header without data gives null`() {
        assertNull(VinDecoder.decode("49 02 01"))
        assertNull(VinDecoder.decode("49 02"))
    }

    // ── Gueltigkeit gespeicherter Werte (aeltere Fahrten tragen den Rohtext) ───────────────────────

    @Test
    fun `isValidVin accepts exactly 17 upper case letters and digits`() {
        assertTrue(VinDecoder.isValidVin("WP0ZZZ99ZTS392124"))
        assertTrue(VinDecoder.isValidVin("1HGBH41JXMN109186"))
    }

    @Test
    fun `isValidVin rejects raw adapter text, wrong lengths and lower case`() {
        for (text in listOf("NO DATA", "49 02 01 57 50 30", "014 0: 49 02 01", "WP0ZZZ99ZTS39212", "WP0ZZZ99ZTS3921245", "wp0zzz99zts392124", "")) {
            assertFalse("'$text'", VinDecoder.isValidVin(text))
        }
    }
}
