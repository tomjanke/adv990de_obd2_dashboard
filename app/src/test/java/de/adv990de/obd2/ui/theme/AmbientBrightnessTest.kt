package de.adv990de.obd2.ui.theme

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die automatische Helligkeit: Stufen mit Hysterese, Glaettung ueber ein Fenster, der Fluss mit Schalter und die Log-Zeile. */
@OptIn(ExperimentalCoroutinesApi::class)
class AmbientBrightnessTest {

    private val day = BrightnessLevel.DAY
    private val dusk = BrightnessLevel.DUSK
    private val night = BrightnessLevel.NIGHT

    // ── die Stufen ────────────────────────────────────────────────────────────

    @Test
    fun `the three levels have the brightness values the user asked for - full, half and about a fifth`() {
        assertEquals(1f, day.windowBrightness, 0f)
        assertEquals(0.5f, dusk.windowBrightness, 0f)
        assertEquals(0.2f, night.windowBrightness, 0f)
    }

    @Test
    fun `from dusk the day starts at 1200 lux and the night at under 30 lux`() {
        assertEquals(dusk, brightnessLevelFor(1_199f, dusk))
        assertEquals(day, brightnessLevelFor(1_200f, dusk))
        assertEquals(dusk, brightnessLevelFor(30f, dusk))
        assertEquals(night, brightnessLevelFor(29.9f, dusk))
    }

    @Test
    fun `the day is only left under 800 lux - hysteresis against flicker at the border`() {
        assertEquals(day, brightnessLevelFor(800f, day))
        assertEquals(day, brightnessLevelFor(1_000f, day))
        assertEquals(dusk, brightnessLevelFor(799f, day))
        // dieselben 1 000 lx aus der Daemmerung: noch kein Tag
        assertEquals(dusk, brightnessLevelFor(1_000f, dusk))
    }

    @Test
    fun `the night is only left from 50 lux - hysteresis against flicker at the border`() {
        assertEquals(night, brightnessLevelFor(49.9f, night))
        assertEquals(dusk, brightnessLevelFor(50f, night))
        // dieselben 40 lx aus der Daemmerung: noch keine Nacht? doch — 40 liegt ueber 30
        assertEquals(dusk, brightnessLevelFor(40f, dusk))
        assertEquals(night, brightnessLevelFor(40f, night))
    }

    @Test
    fun `a jump over a whole level is allowed - tunnel at noon, floodlight at night`() {
        assertEquals(night, brightnessLevelFor(5f, day))
        assertEquals(day, brightnessLevelFor(20_000f, night))
    }

    @Test
    fun `no measurement leaves the level alone - NaN, infinity and negative values`() {
        for (level in BrightnessLevel.entries) {
            assertEquals(level, brightnessLevelFor(Float.NaN, level))
            assertEquals(level, brightnessLevelFor(Float.POSITIVE_INFINITY, level))
            assertEquals(level, brightnessLevelFor(-1f, level))
        }
    }

    @Test
    fun `zero lux is the darkest night, not a missing value`() {
        assertEquals(night, brightnessLevelFor(0f, day))
    }

    // ── der Glaetter ──────────────────────────────────────────────────────────

    @Test
    fun `without a measurement there is no reading`() {
        assertNull(AmbientBrightnessTracker().onTick())
    }

    @Test
    fun `the first valid sample counts at once - no waiting at the start`() {
        val tracker = AmbientBrightnessTracker()
        val first = tracker.onSample(10f)
        assertNotNull(first)
        assertEquals(night, first!!.level)
        assertEquals(10f, first.medianLux!!, 0f)
    }

    @Test
    fun `invalid samples are no measurement - they neither start nor change anything`() {
        val tracker = AmbientBrightnessTracker()
        assertNull(tracker.onSample(Float.NaN))
        assertNull(tracker.onSample(-5f))
        assertNull(tracker.onTick())
        assertEquals(dusk, tracker.onSample(100f)!!.level)
        assertNull(tracker.onSample(Float.NaN))
        assertEquals(100f, tracker.onTick()!!.medianLux!!, 0f)
    }

    @Test
    fun `later samples only change the held value, the level follows at the next tick`() {
        val tracker = AmbientBrightnessTracker()
        tracker.onSample(5_000f)
        assertNull(tracker.onSample(5f))
        assertEquals(day, tracker.onTick()!!.level)
    }

    @Test
    fun `a real change needs three of five seconds - not two`() {
        val tracker = AmbientBrightnessTracker()
        tracker.onSample(5_000f)
        tracker.onSample(100f)
        assertEquals(day, tracker.onTick()!!.level)
        assertEquals(day, tracker.onTick()!!.level)
        val third = tracker.onTick()!!
        assertEquals(dusk, third.level)
        assertEquals(100f, third.medianLux!!, 0f)
    }

    @Test
    fun `a short spike does not tip the level - one bright second in the dark`() {
        val tracker = AmbientBrightnessTracker()
        tracker.onSample(10f)
        tracker.onSample(8_000f)
        assertEquals(night, tracker.onTick()!!.level)
        tracker.onSample(10f)
        repeat(6) { assertEquals(night, tracker.onTick()!!.level) }
    }

    @Test
    fun `a spike that ends without a new sample does not stay forever - the sensor reports only changes, the held value moves on`() {
        val tracker = AmbientBrightnessTracker()
        tracker.onSample(10f)
        // 3 Sekunden hell (der Sensor meldet 8000 einmal), danach meldet er 10 lx nie wieder, weil es dabei bleibt? Nein: er meldet den Rueckgang — der Test
        // prueft, dass das Fenster nach dem Rueckgang wieder nur die neuen Werte enthaelt
        tracker.onSample(8_000f)
        repeat(3) { tracker.onTick() }
        tracker.onSample(10f)
        val levels = List(5) { tracker.onTick()!!.level }
        assertEquals(night, levels.last())
    }

    @Test
    fun `the window holds five values - older seconds fall out`() {
        val tracker = AmbientBrightnessTracker()
        tracker.onSample(5_000f)
        repeat(20) { tracker.onTick() }
        tracker.onSample(5f)
        assertEquals(day, tracker.onTick()!!.level)
        assertEquals(day, tracker.onTick()!!.level)
        assertEquals(night, tracker.onTick()!!.level)
    }

    // ── der Fluss ─────────────────────────────────────────────────────────────

    @Test
    fun `with the switch off the level is always the day and the sensor is never asked`() = runTest {
        var asked = 0
        val readings = mutableListOf<BrightnessReading>()
        backgroundScope.launch {
            ambientBrightness(MutableStateFlow(false), { asked++; flow { emit(1f) } }).collect { readings += it }
        }
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(listOf(BrightnessReading(day, null)), readings)
        assertEquals(0, asked)
    }

    @Test
    fun `with the switch on and no sensor values the level stays at the day - nothing is emitted`() = runTest {
        val readings = mutableListOf<BrightnessReading>()
        backgroundScope.launch {
            ambientBrightness(MutableStateFlow(true), { MutableSharedFlow() }).collect { readings += it }
        }
        runCurrent()
        advanceTimeBy(10_000)
        assertTrue(readings.isEmpty())
    }

    @Test
    fun `the first sensor value switches at once, later changes follow after the smoothing, and only a level change is reported`() = runTest {
        val lux = MutableSharedFlow<Float>()
        val readings = mutableListOf<BrightnessReading>()
        backgroundScope.launch {
            ambientBrightness(MutableStateFlow(true), { lux }).collect { readings += it }
        }
        runCurrent()
        lux.emit(10f)
        runCurrent()
        assertEquals(listOf(BrightnessReading(night, 10f)), readings)
        // gleiche Stufe, auch ueber viele Sekunden: nichts Neues
        advanceTimeBy(5_000)
        assertEquals(1, readings.size)
        // heller werden: nach zwei Sekunden noch nicht, nach vier ja (drei Auswertungen brauchen drei Takte, der erste kann vor der Meldung liegen)
        lux.emit(100f)
        runCurrent()
        advanceTimeBy(2_000)
        assertEquals(1, readings.size)
        advanceTimeBy(2_000)
        assertEquals(listOf(night, dusk), readings.map { it.level })
        assertEquals(100f, readings.last().medianLux!!, 0f)
    }

    @Test
    fun `switching the function off while dark gives the day at once and on again starts the smoothing anew`() = runTest {
        val enabled = MutableStateFlow(true)
        val lux = MutableSharedFlow<Float>()
        val readings = mutableListOf<BrightnessReading>()
        backgroundScope.launch { ambientBrightness(enabled, { lux }).collect { readings += it } }
        runCurrent()
        lux.emit(5f)
        runCurrent()
        assertEquals(night, readings.last().level)
        enabled.value = false
        runCurrent()
        assertEquals(BrightnessReading(day, null), readings.last())
        enabled.value = true
        runCurrent()
        // neu angemeldet: bis zum ersten Wert bleibt es beim Tag (kein Rest der alten Glaettung)
        assertEquals(BrightnessReading(day, null), readings.last())
        lux.emit(500f)
        runCurrent()
        assertEquals(BrightnessReading(dusk, 500f), readings.last())
    }

    // ── Fenster-Wert und Log-Zeile ────────────────────────────────────────────

    @Test
    fun `the log line names both levels and the lux value the change came from`() {
        assertEquals("Brightness level DAY -> NIGHT (median 12 lx)", brightnessLogMessage(day, BrightnessReading(night, 12.9f)))
        assertEquals("Brightness level NIGHT -> DAY (dimming off)", brightnessLogMessage(night, BrightnessReading(day, null)))
    }
}
