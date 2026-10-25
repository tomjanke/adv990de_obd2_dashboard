package de.adv990de.obd2.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelDistanceTrackerTest {

    private class RecordingSink : FuelDistanceSink {
        val added = mutableListOf<Float>()
        var flushes = 0
        override fun addDistance(km: Float) { added += km }
        override fun flush() { flushes++ }
    }

    private var nowMs = 0L
    private val sink = RecordingSink()
    private val tracker = FuelDistanceTracker(sink) { nowMs }

    @Test
    fun `the first sample starts an interval and counts nothing`() {
        tracker.sample(speedKmh = 60f)

        assertTrue(sink.added.isEmpty())
    }

    @Test
    fun `a second sample hands the driven kilometres to the sink`() {
        tracker.sample(60f)
        nowMs = 60_000L / 10 // 6 s bei 60 km/h = 0,1 km
        tracker.sample(60f)

        assertEquals(1, sink.added.size)
        assertEquals(0.1f, sink.added.single(), 1e-4f)
    }

    @Test
    fun `a sample without distance never reaches the sink`() {
        // Unter 2 km/h gilt Stillstand: der Integrator liefert 0, der Zaehler darf nicht mit 0 km belaestigt werden (addDistance verlangt > 0).
        tracker.sample(0f)
        nowMs = 5_000L
        tracker.sample(0f)
        nowMs = 6_000L
        tracker.sample(null)

        assertTrue(sink.added.isEmpty())
    }

    @Test
    fun `the time comes from the injected clock at the moment of the sample`() {
        nowMs = 1_000L
        tracker.sample(36f)
        nowMs = 11_000L // 10 s bei 36 km/h = 0,1 km, genau die Obergrenze der Luecke
        tracker.sample(36f)

        assertEquals(0.1f, sink.added.single(), 1e-4f)
    }

    @Test
    fun `ending the interval flushes the sink and forgets the last measurement`() {
        tracker.sample(60f)
        nowMs = 1_000L
        tracker.endInterval()
        nowMs = 2_000L
        tracker.sample(60f) // beginnt ein neues Intervall — die Luecke zaehlt nicht

        assertEquals(1, sink.flushes)
        assertTrue(sink.added.isEmpty())
    }

    @Test
    fun `flush alone does not end the interval`() {
        tracker.sample(60f)
        nowMs = 4_000L
        tracker.flush()
        nowMs = 8_000L
        tracker.sample(60f)

        assertEquals(1, sink.flushes)
        assertEquals(1, sink.added.size)
        assertEquals(60f * 8_000f / 3_600_000f, sink.added.single(), 1e-4f)
    }

    @Test
    fun `a fan-out sink hands every distance and every flush to all sinks in order`() {
        val calls = mutableListOf<String>()
        fun recording(name: String) = object : FuelDistanceSink {
            override fun addDistance(km: Float) { calls += "$name add $km" }
            override fun flush() { calls += "$name flush" }
        }
        val fan = FuelDistanceSink.fanOut(recording("a"), recording("b"))

        fan.addDistance(0.5f)
        fan.flush()

        assertEquals(listOf("a add 0.5", "b add 0.5", "a flush", "b flush"), calls)
    }

    @Test
    fun `a fan-out sink of nothing does nothing`() {
        val fan = FuelDistanceSink.fanOut()

        fan.addDistance(1f)
        fan.flush()
    }
}
