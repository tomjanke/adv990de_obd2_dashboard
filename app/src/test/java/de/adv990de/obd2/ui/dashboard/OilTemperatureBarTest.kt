package de.adv990de.obd2.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class OilTemperatureBarTest {

    private val fresh = OilBarTimerState()
    private val tenMinutesMs = 10 * 60 * 1000L

    // --- oilBarColor: static thresholds, no timer involved ---

    @Test
    fun `at or below 75C is Red regardless of timer state`() {
        assertEquals(OilBarColor.Red, oilBarColor(75f, nowMillis = 0L, state = fresh))
        assertEquals(OilBarColor.Red, oilBarColor(50f, nowMillis = 0L, state = fresh))
    }

    @Test
    fun `above 75C without a running green timer is Yellow`() {
        assertEquals(OilBarColor.Yellow, oilBarColor(76f, nowMillis = 0L, state = fresh))
        assertEquals(OilBarColor.Yellow, oilBarColor(90f, nowMillis = 0L, state = fresh))
    }

    @Test
    fun `above 80C is Yellow until the 10-minute hold completes`() {
        val state = OilBarTimerState(aboveGreenThresholdSinceMillis = 0L)
        assertEquals(OilBarColor.Yellow, oilBarColor(85f, nowMillis = tenMinutesMs - 1, state = state))
        assertEquals(OilBarColor.Green, oilBarColor(85f, nowMillis = tenMinutesMs, state = state))
    }

    @Test
    fun `dropping to 75C or below is Red even with a fully-held green timer`() {
        val state = OilBarTimerState(aboveGreenThresholdSinceMillis = 0L)
        assertEquals(OilBarColor.Red, oilBarColor(75f, nowMillis = tenMinutesMs, state = state))
    }

    // --- updateOilBarTimer: the hysteresis state machine ---

    @Test
    fun `starts the timer when crossing above 80C`() {
        val next = updateOilBarTimer(coolantTempC = 81f, nowMillis = 1000L, state = fresh)
        assertEquals(1000L, next.aboveGreenThresholdSinceMillis)
    }

    @Test
    fun `does not restart an already-running timer`() {
        val running = OilBarTimerState(aboveGreenThresholdSinceMillis = 1000L)
        val next = updateOilBarTimer(coolantTempC = 85f, nowMillis = 5000L, state = running)
        assertEquals(1000L, next.aboveGreenThresholdSinceMillis) // unchanged, not reset to 5000
    }

    @Test
    fun `holds steady in the 78-80C hysteresis band`() {
        val running = OilBarTimerState(aboveGreenThresholdSinceMillis = 1000L)
        val next = updateOilBarTimer(coolantTempC = 79f, nowMillis = 5000L, state = running)
        assertEquals(1000L, next.aboveGreenThresholdSinceMillis) // holds, neither resets nor advances
    }

    @Test
    fun `resets at exactly 78C`() {
        val running = OilBarTimerState(aboveGreenThresholdSinceMillis = 1000L)
        val next = updateOilBarTimer(coolantTempC = 78f, nowMillis = 5000L, state = running)
        assertNull(next.aboveGreenThresholdSinceMillis)
    }

    @Test
    fun `resets below 78C`() {
        val running = OilBarTimerState(aboveGreenThresholdSinceMillis = 1000L)
        val next = updateOilBarTimer(coolantTempC = 60f, nowMillis = 5000L, state = running)
        assertNull(next.aboveGreenThresholdSinceMillis)
    }

    @Test
    fun `a full realistic sequence reaches Green only after a genuine continuous 10-minute hold`() {
        var state = OilBarTimerState()
        // Warms past 80C at t=0.
        state = updateOilBarTimer(85f, 0L, state)
        assertNotNull(state.aboveGreenThresholdSinceMillis)
        // Dips into the hysteresis band at t=5min — must NOT reset.
        state = updateOilBarTimer(79f, 5 * 60_000L, state)
        assertNotNull(state.aboveGreenThresholdSinceMillis)
        assertEquals(OilBarColor.Yellow, oilBarColor(79f, 5 * 60_000L, state))
        // Back above 80C, still counting from t=0 — reaches Green at t=10min.
        state = updateOilBarTimer(85f, 9 * 60_000L, state)
        assertEquals(OilBarColor.Green, oilBarColor(85f, 10 * 60_000L, state))
        // A real drop to 60C resets everything.
        state = updateOilBarTimer(60f, 10 * 60_000L + 1, state)
        assertEquals(OilBarColor.Red, oilBarColor(60f, 10 * 60_000L + 1, state))
        assertNull(state.aboveGreenThresholdSinceMillis)
    }
}
