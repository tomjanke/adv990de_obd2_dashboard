package de.adv990de.obd2.logbook

import org.junit.Assert.assertEquals
import org.junit.Test

class LogTypeTest {

    @Test
    fun `the nine names are stable - they are stored and exported as text`() {
        assertEquals(
            listOf("FUEL", "OIL", "COOLANT", "CHAIN_SMALL", "CHAIN_LARGE", "SERVICE_SMALL", "SERVICE_LARGE", "TIRE_CHANGE", "CHAIN_KIT_CHANGE"),
            LogType.entries.map { it.name },
        )
    }

    @Test
    fun `only fuel, oil and coolant carry an amount`() {
        assertEquals(
            listOf(LogType.FUEL, LogType.OIL, LogType.COOLANT),
            LogType.entries.filter { it.hasAmount },
        )
    }

    @Test
    fun `the largest amount is 100 l for fuel and 5 l for oil and coolant`() {
        assertEquals(100_000, LogType.FUEL.maxAmountMl)
        assertEquals(5_000, LogType.OIL.maxAmountMl)
        assertEquals(5_000, LogType.COOLANT.maxAmountMl)
    }

    @Test
    fun `only the tire change carries a brand`() {
        assertEquals(listOf(LogType.TIRE_CHANGE), LogType.entries.filter { it.hasBrand })
    }

    @Test
    fun `the tire change and the chain kit change carry no amount`() {
        assertEquals(false, LogType.TIRE_CHANGE.hasAmount)
        assertEquals(false, LogType.CHAIN_KIT_CHANGE.hasAmount)
        assertEquals(0, LogType.TIRE_CHANGE.maxAmountMl)
        assertEquals(0, LogType.CHAIN_KIT_CHANGE.maxAmountMl)
    }
}
