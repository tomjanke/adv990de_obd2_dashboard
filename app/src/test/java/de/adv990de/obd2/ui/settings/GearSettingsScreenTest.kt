package de.adv990de.obd2.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GearSettingsScreenTest {

    @Test
    fun `a positive number parses to itself`() {
        assertEquals(2096f, parsePositiveFloat("2096")!!, 0.001f)
        assertEquals(1.9143f, parsePositiveFloat("1.9143")!!, 0.001f)
    }

    @Test
    fun `a decimal comma parses the same as a decimal point`() {
        // German-locale keyboards (e.g. Samsung's) can offer only "," as the decimal key on a
        // KeyboardType.Decimal pad — without normalization a German-speaking user could not type
        // a fractional value like this at all.
        assertEquals(parsePositiveFloat("1.9143"), parsePositiveFloat("1,9143"))
    }

    @Test
    fun `zero is rejected`() {
        assertNull(parsePositiveFloat("0"))
    }

    @Test
    fun `a negative number is rejected`() {
        assertNull(parsePositiveFloat("-5"))
    }

    @Test
    fun `non-numeric text is rejected`() {
        assertNull(parsePositiveFloat("abc"))
        assertNull(parsePositiveFloat(""))
    }
}
