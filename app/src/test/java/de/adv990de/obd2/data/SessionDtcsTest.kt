package de.adv990de.obd2.data

import de.adv990de.obd2.obd.model.DtcEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Das Format von `sessions.dtcCodes` ist persistierter Zustand: was hier festgehalten ist, aendert sich nie, sonst
 * liest eine neue Version die Fahrten einer alten falsch. Die drei Zustaende sind die eigentliche Aussage: `null` =
 * nicht erfasst (Fahrt vor dieser Version, oder das Auslesen ist gescheitert), leere Liste = gelesen und nichts
 * gefunden, sonst die Codes. Ein "keine Fehler" darf nie fuer ein "nicht erfasst" stehen — das waere eine Entwarnung,
 * die das Fahrzeug nie gegeben hat.
 */
class SessionDtcsTest {

    private fun dtc(code: String, pending: Boolean = false) = DtcEntry(code, pending)

    @Test
    fun `null means not recorded, in both directions`() {
        assertNull(encodeSessionDtcs(null))
        assertNull(decodeSessionDtcs(null))
    }

    @Test
    fun `an empty list means read and none found, and is not null`() {
        assertEquals("", encodeSessionDtcs(emptyList()))
        assertEquals(emptyList<DtcEntry>(), decodeSessionDtcs(""))
    }

    @Test
    fun `codes are separated by commas and a pending code carries a question mark`() {
        assertEquals("P0301,P0420?", encodeSessionDtcs(listOf(dtc("P0301"), dtc("P0420", pending = true))))
        assertEquals("P0171?", encodeSessionDtcs(listOf(dtc("P0171", pending = true))))
    }

    @Test
    fun `what is encoded decodes to the same list, for all four code letters`() {
        val codes = listOf(dtc("P0301"), dtc("C0035", pending = true), dtc("B1000"), dtc("U0100", pending = true))

        assertEquals(codes, decodeSessionDtcs(encodeSessionDtcs(codes)))
    }

    @Test
    fun `the order of the vehicle is kept`() {
        assertEquals("U0100,P0301,B1000", encodeSessionDtcs(listOf(dtc("U0100"), dtc("P0301"), dtc("B1000"))))
    }

    @Test
    fun `a code that is both confirmed and pending counts as confirmed, wherever it comes first`() {
        // Dieselbe Regel wie in der Fehlerhistorie (DtcHistoryDao.recordSeen).
        assertEquals("P0301", encodeSessionDtcs(listOf(dtc("P0301", pending = true), dtc("P0301"))))
        assertEquals("P0301", encodeSessionDtcs(listOf(dtc("P0301"), dtc("P0301", pending = true))))
    }

    @Test
    fun `a code that is pending in every reading stays pending, and a repeated code is stored once`() {
        assertEquals("P0301?", encodeSessionDtcs(listOf(dtc("P0301", pending = true), dtc("P0301", pending = true))))
        assertEquals("P0301,P0420", encodeSessionDtcs(listOf(dtc("P0301"), dtc("P0420"), dtc("P0301"))))
    }

    @Test
    fun `something that is not a fault code is dropped when encoding, so it can never break the format`() {
        val encoded = encodeSessionDtcs(listOf(dtc("P0301"), dtc("P03,1"), dtc("p0301"), dtc("X0301"), dtc("P030"), dtc("P0301?"), dtc(""), dtc("U1234")))

        assertEquals("P0301,U1234", encoded)
    }

    @Test
    fun `decoding skips what is not a fault code and tolerates blanks`() {
        val decoded = decodeSessionDtcs("P0301,, garbage ,P0420?,  ,U1234 ,p0301,P0301??")

        assertEquals(listOf(dtc("P0301"), dtc("P0420", pending = true), dtc("U1234")), decoded)
    }

    @Test
    fun `a text without any valid code decodes to an empty list, not to not recorded`() {
        assertEquals(emptyList<DtcEntry>(), decodeSessionDtcs("garbage"))
    }
}
