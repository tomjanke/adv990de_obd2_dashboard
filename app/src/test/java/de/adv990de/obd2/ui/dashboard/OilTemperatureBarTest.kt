package de.adv990de.obd2.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `just above 75C is already Yellow - the edge is exactly 75`() {
        assertEquals(OilBarColor.Yellow, oilBarColor(75.1f, nowMillis = 0L, state = fresh))
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
    fun `exactly 80C does not start the timer - it is the edge of the band, not above it`() {
        val next = updateOilBarTimer(coolantTempC = 80f, nowMillis = 1000L, state = fresh)
        assertNull(next.aboveGreenThresholdSinceMillis)
    }

    @Test
    fun `80_1C starts the timer`() {
        val next = updateOilBarTimer(coolantTempC = 80.1f, nowMillis = 1000L, state = fresh)
        assertEquals(1000L, next.aboveGreenThresholdSinceMillis)
    }

    @Test
    fun `exactly 80C keeps a running timer, just above 78C too`() {
        val running = OilBarTimerState(aboveGreenThresholdSinceMillis = 1000L)
        assertEquals(1000L, updateOilBarTimer(80f, 5000L, running).aboveGreenThresholdSinceMillis)
        assertEquals(1000L, updateOilBarTimer(78.1f, 5000L, running).aboveGreenThresholdSinceMillis)
    }

    @Test
    fun `holds steady in the 79-80C hysteresis band`() {
        val running = OilBarTimerState(aboveGreenThresholdSinceMillis = 1000L)
        val next = updateOilBarTimer(coolantTempC = 79f, nowMillis = 5000L, state = running)
        assertEquals(1000L, next.aboveGreenThresholdSinceMillis) // holds, neither resets nor advances
    }

    @Test
    fun `at 75C or below the timer resets at once - the red zone is no dip to wait out`() {
        val running = OilBarTimerState(aboveGreenThresholdSinceMillis = 1000L, lastSampleAtMillis = 4000L, belowResetSinceMillis = 3000L)
        for (temp in listOf(75f, 60f)) {
            val next = updateOilBarTimer(coolantTempC = temp, nowMillis = 5000L, state = running)
            assertNull("$temp C", next.aboveGreenThresholdSinceMillis)
            assertNull("$temp C: auch die Entprellung ist beendet", next.belowResetSinceMillis)
        }
    }

    // --- updateOilBarTimer: Entprellen des Resets (RESET_DEBOUNCE_MS = 120 s) ---
    // Anlass: In zwei aufgezeichneten Aufwaermfahrten (2026-10-12) bricht das Kuehlmittel bei hoher Drehzahl immer wieder auf 76-78 C ein
    // (Thermostat/Fahrtwind), die laengsten Einbrueche dauerten 74-84 s. Ein sofortiger Reset bei <= 78 C liess Gruen nie erscheinen.

    private val dipMs = 120_000L

    @Test
    fun `a dip to 78C or below does not reset a running timer - it starts the debounce`() {
        val running = OilBarTimerState(aboveGreenThresholdSinceMillis = 1000L, lastSampleAtMillis = 4000L)
        for (temp in listOf(78f, 77f, 76f)) {
            val next = updateOilBarTimer(coolantTempC = temp, nowMillis = 5000L, state = running)
            assertEquals("$temp C", 1000L, next.aboveGreenThresholdSinceMillis)
            assertEquals("$temp C", 5000L, next.belowResetSinceMillis)
        }
    }

    @Test
    fun `just above 78C is not a dip`() {
        val running = OilBarTimerState(aboveGreenThresholdSinceMillis = 1000L, lastSampleAtMillis = 4000L)
        assertNull(updateOilBarTimer(78.1f, 5000L, running).belowResetSinceMillis)
    }

    @Test
    fun `the debounce counts from the first sample of the dip, not from the latest`() {
        var state = OilBarTimerState(aboveGreenThresholdSinceMillis = 0L, lastSampleAtMillis = 4000L)
        state = updateOilBarTimer(77f, 5000L, state)
        state = updateOilBarTimer(76f, 6000L, state)
        state = updateOilBarTimer(78f, 7000L, state)
        assertEquals(5000L, state.belowResetSinceMillis)
    }

    @Test
    fun `a dip of exactly 120s resets the timer, one millisecond less does not`() {
        val inDip = OilBarTimerState(aboveGreenThresholdSinceMillis = 0L, lastSampleAtMillis = 5000L + dipMs - 1000L, belowResetSinceMillis = 5000L)
        val early = updateOilBarTimer(77f, 5000L + dipMs - 1L, inDip)
        assertEquals(0L, early.aboveGreenThresholdSinceMillis)
        assertEquals(5000L, early.belowResetSinceMillis)

        val due = updateOilBarTimer(77f, 5000L + dipMs, inDip)
        assertNull(due.aboveGreenThresholdSinceMillis)
        assertNull("nach dem Reset gibt es nichts mehr zu entprellen", due.belowResetSinceMillis)
    }

    @Test
    fun `a dip shorter than 120s changes nothing once the temperature is back above 80C`() {
        var state = OilBarTimerState(aboveGreenThresholdSinceMillis = 0L, lastSampleAtMillis = 1000L)
        state = state.feed(77f, fromMs = 2_000L, toMs = 2_000L + 100_000L)
        assertEquals(0L, state.aboveGreenThresholdSinceMillis)
        state = updateOilBarTimer(85f, 103_000L, state)
        assertEquals("der Start der Zaehlung bleibt", 0L, state.aboveGreenThresholdSinceMillis)
        assertNull(state.belowResetSinceMillis)
    }

    @Test
    fun `a sample in the hysteresis band interrupts the dip - the 120s count starts over`() {
        var state = OilBarTimerState(aboveGreenThresholdSinceMillis = 0L, lastSampleAtMillis = 1000L)
        state = state.feed(77f, fromMs = 2_000L, toMs = 100_000L)          // 99 s unter der Grenze
        state = updateOilBarTimer(79f, 101_000L, state)                     // Haltezone unterbricht
        assertNull(state.belowResetSinceMillis)
        state = state.feed(77f, fromMs = 102_000L, toMs = 200_000L)        // weitere 99 s: zusammen 198 s, aber nicht ununterbrochen
        assertEquals("nicht zurueckgesetzt: kein einzelner Einbruch dauerte 120 s", 0L, state.aboveGreenThresholdSinceMillis)
    }

    @Test
    fun `without a running timer a dip starts no debounce`() {
        val next = updateOilBarTimer(77f, 5000L, fresh)
        assertNull(next.aboveGreenThresholdSinceMillis)
        assertNull(next.belowResetSinceMillis)
    }

    @Test
    fun `a gap of more than 60s forgets the debounce too`() {
        val inDip = OilBarTimerState(aboveGreenThresholdSinceMillis = 0L, lastSampleAtMillis = 50_000L, belowResetSinceMillis = 40_000L)
        val next = updateOilBarTimer(77f, 50_000L + 61_000L, inDip)
        assertNull(next.aboveGreenThresholdSinceMillis)
        assertNull("die Auswertung beginnt frisch, auch die Entprellung", next.belowResetSinceMillis)
    }

    @Test
    fun `a green bar stays green through a short dip and falls back to yellow only when the debounce runs out`() {
        var state = fresh.feed(85f, fromMs = 0L, toMs = tenMinutesMs)
        assertEquals(OilBarColor.Green, oilBarColor(85f, tenMinutesMs, state))

        state = state.feed(77f, fromMs = tenMinutesMs + 1_000L, toMs = tenMinutesMs + dipMs - 1_000L)
        assertEquals("119 s unter 78 C: noch Gruen", OilBarColor.Green, oilBarColor(77f, tenMinutesMs + dipMs - 1_000L, state))

        state = updateOilBarTimer(77f, tenMinutesMs + 1_000L + dipMs, state)
        assertEquals("120 s: der Timer ist zurueckgesetzt, 77 C ist nicht Rot", OilBarColor.Yellow, oilBarColor(77f, tenMinutesMs + 1_000L + dipMs, state))
        assertNull(state.aboveGreenThresholdSinceMillis)
    }

    @Test
    fun `a full realistic sequence reaches Green only after a genuine continuous 10-minute hold`() {
        // Messwerte im Sekundentakt wie im echten OBD-Betrieb. (Bis zum 60-s-Luecken-Limit sprang
        // dieser Test in Minutenschritten — solche Abstaende setzen den Timer jetzt bewusst zurueck,
        // s. die Luecken-Tests unten.)
        var state = OilBarTimerState()
        // Warms past 80C at t=0 and stays there for 5 minutes.
        state = state.feed(85f, fromMs = 0L, toMs = 5 * 60_000L)
        assertEquals(0L, state.aboveGreenThresholdSinceMillis)
        // Dips into the hysteresis band for a minute — must NOT reset.
        state = state.feed(79f, fromMs = 5 * 60_000L + 1_000L, toMs = 6 * 60_000L)
        assertEquals(0L, state.aboveGreenThresholdSinceMillis)
        assertEquals(OilBarColor.Yellow, oilBarColor(79f, 6 * 60_000L, state))
        // Back above 80C, still counting from t=0 — Yellow at 9:59, Green at exactly 10:00.
        state = state.feed(85f, fromMs = 6 * 60_000L + 1_000L, toMs = tenMinutesMs - 1_000L)
        assertEquals(OilBarColor.Yellow, oilBarColor(85f, tenMinutesMs - 1_000L, state))
        state = updateOilBarTimer(85f, tenMinutesMs, state)
        assertEquals(OilBarColor.Green, oilBarColor(85f, tenMinutesMs, state))
        // A real drop to 60C resets everything.
        state = updateOilBarTimer(60f, tenMinutesMs + 1_000L, state)
        assertEquals(OilBarColor.Red, oilBarColor(60f, tenMinutesMs + 1_000L, state))
        assertNull(state.aboveGreenThresholdSinceMillis)
    }

    // --- updateOilBarTimer: sample-gap limit (MAX_SAMPLE_GAP_MS = 60 s) ---

    @Test
    fun `every update records its sample time, in all threshold branches`() {
        var state = updateOilBarTimer(coolantTempC = 85f, nowMillis = 1_000L, state = fresh)
        assertEquals(1_000L, state.lastSampleAtMillis)   // start branch
        state = updateOilBarTimer(coolantTempC = 79f, nowMillis = 2_000L, state = state)
        assertEquals(2_000L, state.lastSampleAtMillis)   // hysteresis hold branch
        state = updateOilBarTimer(coolantTempC = 60f, nowMillis = 3_000L, state = state)
        assertEquals(3_000L, state.lastSampleAtMillis)   // reset branch (rot)
        state = updateOilBarTimer(coolantTempC = 77f, nowMillis = 4_000L, state = state)
        assertEquals(4_000L, state.lastSampleAtMillis)   // dip branch
    }

    @Test
    fun `a gap of more than 60s between two samples above 80C restarts the green timer`() {
        // Zwei echte, frische Messwerte ueber 80 C, 61 s auseinander: der zweite darf den Timer
        // des ersten nicht fortsetzen (Verbindungsabbruch oder App war zu — was die Temperatur
        // dazwischen tat, ist unbekannt), sondern muss neu zaehlen.
        var state = updateOilBarTimer(coolantTempC = 85f, nowMillis = 0L, state = fresh)
        state = updateOilBarTimer(coolantTempC = 85f, nowMillis = 61_000L, state = state)
        assertEquals(61_000L, state.aboveGreenThresholdSinceMillis)
        // Continuous samples from the restart on: still Yellow 10 minutes after the FIRST sample...
        state = state.feed(85f, fromMs = 62_000L, toMs = tenMinutesMs)
        assertEquals(OilBarColor.Yellow, oilBarColor(85f, tenMinutesMs, state))
        // ...and Green only 10 minutes after the restart.
        state = state.feed(85f, fromMs = tenMinutesMs + 1_000L, toMs = 61_000L + tenMinutesMs)
        assertEquals(OilBarColor.Green, oilBarColor(85f, 61_000L + tenMinutesMs, state))
    }

    @Test
    fun `a gap of exactly 60s is still tolerated, one millisecond more is not`() {
        // Grenze ist "> 60 s", nicht ">= 60 s" — ein normaler Reconnect (Watchdog-Default 20 s
        // plus Backoff und Verbindungsaufbau) soll die Zaehlung nicht verlieren.
        val running = updateOilBarTimer(coolantTempC = 85f, nowMillis = 0L, state = fresh)
        assertEquals(0L, updateOilBarTimer(85f, nowMillis = 60_000L, state = running).aboveGreenThresholdSinceMillis)
        assertEquals(60_001L, updateOilBarTimer(85f, nowMillis = 60_001L, state = running).aboveGreenThresholdSinceMillis)
    }

    @Test
    fun `after a gap of more than 60s a hysteresis-band sample does not keep the old timer`() {
        // Die Hysterese-Zone haelt nur einen Timer, der ohne Luecke bis hierher lief. Nach einer
        // Luecke startet die Auswertung frisch — und 79 C allein startet keinen Timer.
        var state = updateOilBarTimer(coolantTempC = 85f, nowMillis = 0L, state = fresh)
        state = updateOilBarTimer(coolantTempC = 79f, nowMillis = 120_000L, state = state)
        assertNull(state.aboveGreenThresholdSinceMillis)
        assertEquals(120_000L, state.lastSampleAtMillis)
    }

    @Test
    fun `a single sample above 80C right after an hours-long gap is Yellow, not Green`() {
        // Der eigentliche Befund: ohne Luecken-Limit setzte ein einzelner Messwert > 78 C nach
        // Stunden den alten Startzeitpunkt fort und zeigte sofort Gruen.
        var state = fresh.feed(85f, fromMs = 0L, toMs = tenMinutesMs)
        assertEquals(OilBarColor.Green, oilBarColor(85f, tenMinutesMs, state))
        val threeHoursLater = tenMinutesMs + 3 * 60 * 60_000L
        state = updateOilBarTimer(85f, threeHoursLater, state)
        assertEquals(OilBarColor.Yellow, oilBarColor(85f, threeHoursLater, state))
        assertEquals(threeHoursLater, state.aboveGreenThresholdSinceMillis)
    }

    /**
     * Speist [tempC] im Takt [stepMs] von [fromMs] bis einschliesslich [toMs] ein — eine
     * lueckenlose Messreihe wie bei laufendem OBD-Takt (Luecken unter dem 60-s-Limit).
     */
    private fun OilBarTimerState.feed(tempC: Float, fromMs: Long, toMs: Long, stepMs: Long = 1_000L): OilBarTimerState {
        var state = this
        var t = fromMs
        while (t <= toMs) {
            state = updateOilBarTimer(tempC, t, state)
            t += stepMs
        }
        return state
    }
}
