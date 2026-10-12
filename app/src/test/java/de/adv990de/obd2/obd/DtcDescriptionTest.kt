package de.adv990de.obd2.obd

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [dtcDescription] is the public, pure form of what used to be a private helper of [DtcParser]: the
 * fault history stores only the code and resolves the text when it is shown, so the description needs
 * no database column and no Hilt binding.
 */
class DtcDescriptionTest {

    @Test
    fun `a known code resolves to its SAE J2012 text`() {
        assertEquals("Cylinder 1 Misfire Detected", dtcDescription("P0301"))
        assertEquals("Catalyst System Efficiency Below Threshold (Bank 1)", dtcDescription("P0420"))
        assertEquals("Incorrect Gear Ratio", dtcDescription("P0730"))
    }

    @Test
    fun `a code outside the known ones is reported as unknown`() {
        assertEquals("Unknown DTC", dtcDescription("P1234"))
        assertEquals("Unknown DTC", dtcDescription("U0100"))
        assertEquals("Unknown DTC", dtcDescription(""))
    }

    @Test
    fun `lookup is exact - no trimming and no case folding`() {
        assertEquals("Unknown DTC", dtcDescription("p0301"))
        assertEquals("Unknown DTC", dtcDescription(" P0301"))
    }

    @Test
    fun `the parser and the function agree`() {
        val parsed = DtcParser().parse("43 03 01", isPending = false).single()

        assertEquals("P0301", parsed.code)
        assertEquals(dtcDescription(parsed.code), parsed.description)
    }
}
