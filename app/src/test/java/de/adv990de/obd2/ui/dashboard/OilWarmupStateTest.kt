package de.adv990de.obd2.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** Der Zustand, den das Dashboard (Erzeuger) und die Ambient-Statuszeile in der Navigation (Verbraucher) teilen: die Restzeit bis Gruen. */
class OilWarmupStateTest {

    @Test
    fun `it starts with the full ten minutes - the timer has not run yet`() {
        assertEquals(600_000L, OilWarmupState().greenInMillis.value)
        assertEquals(OIL_BAR_GREEN_HOLD_MS, OilWarmupState().greenInMillis.value)
    }

    @Test
    fun `set publishes the new remaining time`() {
        val state = OilWarmupState()

        state.set(432_000L)

        assertEquals(432_000L, state.greenInMillis.value)
    }

    @Test
    fun `a value outside zero to ten minutes is clamped, never shown`() {
        val state = OilWarmupState()

        state.set(-5L)
        assertEquals(0L, state.greenInMillis.value)

        state.set(700_000L)
        assertEquals(600_000L, state.greenInMillis.value)
    }
}
