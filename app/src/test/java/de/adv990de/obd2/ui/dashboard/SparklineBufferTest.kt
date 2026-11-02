package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die Sparkline-Puffer: Fenster, Mindestabstand, Wiederholungen und das Leeren bei einer neuen Verbindung. */
class SparklineBufferTest {

    private fun rpm(value: Float, at: Long) = SensorValue(PidRegistry.RPM, value, timestamp = at)
    private fun speed(value: Float, at: Long) = SensorValue(PidRegistry.SPEED, value, timestamp = at)

    @Test
    fun `values are kept per PID, oldest first`() {
        val buffer = SparklineBuffer()

        buffer.append(listOf(rpm(1000f, 0), speed(10f, 0)))
        buffer.append(listOf(rpm(2000f, 1_000), speed(20f, 1_000)))

        assertEquals(mapOf(PidRegistry.RPM.id to listOf(1000f, 2000f), PidRegistry.SPEED.id to listOf(10f, 20f)), buffer.snapshot())
    }

    @Test
    fun `the window keeps only the newest points`() {
        val buffer = SparklineBuffer(window = 3)

        for (i in 1..5) buffer.append(listOf(rpm(i.toFloat(), at = i * 1_000L)))

        assertEquals(listOf(3f, 4f, 5f), buffer.snapshot().getValue(PidRegistry.RPM.id))
    }

    @Test
    fun `the default window is 60 points`() {
        val buffer = SparklineBuffer()

        for (i in 1..70) buffer.append(listOf(rpm(i.toFloat(), at = i * 1_000L)))

        val points = buffer.snapshot().getValue(PidRegistry.RPM.id)
        assertEquals(60, points.size)
        assertEquals(70f, points.last(), 0f)
    }

    @Test
    fun `a repeated reading - the GPS refresh re-delivers the last OBD values - adds no point`() {
        val buffer = SparklineBuffer()
        val reading = rpm(1500f, at = 5_000)

        buffer.append(listOf(reading))
        buffer.append(listOf(reading))
        buffer.append(listOf(rpm(1600f, at = 5_300)))   // neu, aber unter dem Mindestabstand von 800 ms

        assertEquals(listOf(1500f), buffer.snapshot().getValue(PidRegistry.RPM.id))
    }

    @Test
    fun `clear empties the points and the timestamps, so the first point of a new connection is taken even from an older clock`() {
        val buffer = SparklineBuffer()
        buffer.append(listOf(rpm(1500f, at = 100_000)))

        buffer.clear()
        assertTrue(buffer.snapshot().isEmpty())
        buffer.append(listOf(rpm(900f, at = 100_100)))   // nur 100 ms nach dem alten Punkt: ohne Zeitstempel-Reset verschluckt

        assertEquals(listOf(900f), buffer.snapshot().getValue(PidRegistry.RPM.id))
    }

    @Test
    fun `a snapshot is a copy - later points do not change it`() {
        val buffer = SparklineBuffer()
        buffer.append(listOf(rpm(1f, at = 0)))
        val before = buffer.snapshot()

        buffer.append(listOf(rpm(2f, at = 1_000)))

        assertEquals(listOf(1f), before.getValue(PidRegistry.RPM.id))
    }

    // --- der Snapshot wird nur neu gebaut, wenn sich etwas geaendert hat (jedes GPS-Zwischenupdate fragt ihn ab) ---

    @Test
    fun `a snapshot without a change in between is the same instance - the GPS refresh copies nothing`() {
        val buffer = SparklineBuffer()
        buffer.append(listOf(rpm(1500f, at = 1_000)))

        assertSame(buffer.snapshot(), buffer.snapshot())
    }

    @Test
    fun `a repeated or too-early reading changes nothing, so the snapshot stays the same instance`() {
        val buffer = SparklineBuffer()
        val reading = rpm(1500f, at = 5_000)
        buffer.append(listOf(reading))
        val before = buffer.snapshot()

        buffer.append(listOf(reading))                      // dieselbe Messung noch einmal
        buffer.append(listOf(rpm(1600f, at = 5_300)))       // neu, aber unter dem Mindestabstand

        assertSame(before, buffer.snapshot())
    }

    @Test
    fun `a new point makes a new snapshot that shows it, and the old one is not changed`() {
        val buffer = SparklineBuffer()
        buffer.append(listOf(rpm(1500f, at = 1_000)))
        val before = buffer.snapshot()

        buffer.append(listOf(rpm(1600f, at = 2_000)))
        val after = buffer.snapshot()

        assertNotSame(before, after)
        assertEquals(listOf(1500f), before.getValue(PidRegistry.RPM.id))
        assertEquals(listOf(1500f, 1600f), after.getValue(PidRegistry.RPM.id))
    }

    @Test
    fun `clear makes a new empty snapshot`() {
        val buffer = SparklineBuffer()
        buffer.append(listOf(rpm(1500f, at = 1_000)))
        val before = buffer.snapshot()

        buffer.clear()

        assertNotSame(before, buffer.snapshot())
        assertTrue(buffer.snapshot().isEmpty())
    }
}
