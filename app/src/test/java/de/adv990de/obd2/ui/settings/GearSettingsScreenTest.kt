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
