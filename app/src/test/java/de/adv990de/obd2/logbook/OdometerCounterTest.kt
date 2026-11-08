package de.adv990de.obd2.logbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der GPS-Zaehler des Kilometerstands als reiner Zustand: er wird erst bereit, wenn der gespeicherte Wert gelesen UND der hoechste Stand des Logbuchs bekannt ist (vorher zaehlt er nur mit,
 * ohne etwas zu entscheiden), beginnt neu, sobald sich der hoechste Stand aendert, und zaehlt ohne Eintrag nichts.
 */
class OdometerCounterTest {

    private val start = OdometerCounter()

    @Test
    fun `before anything is known there is no counter`() {
        assertNull(start.counter)
        assertFalse(start.ready)
    }

    @Test
    fun `it becomes ready only with both the stored value and the highest reading`() {
        val onlyStored = start.withStored(GpsOdometer(40_000, 5f))
        val onlyMax = start.withLoggedMax(40_000)

        assertFalse(onlyStored.ready)
        assertNull(onlyStored.counter)
        assertFalse(onlyMax.ready)
        assertNull(onlyMax.counter)
        assertTrue(onlyStored.withLoggedMax(40_000).ready)
        assertTrue(onlyMax.withStored(null).ready)
    }

    @Test
    fun `the stored counter of the same reading is kept`() {
        val ready = start.withStored(GpsOdometer(40_000, 5.5f)).withLoggedMax(40_000)

        assertEquals(GpsOdometer(40_000, 5.5f), ready.counter)
    }

    @Test
    fun `only the first stored value counts`() {
        val ready = start.withStored(GpsOdometer(40_000, 5f)).withStored(GpsOdometer(40_000, 99f)).withLoggedMax(40_000)

        assertEquals(GpsOdometer(40_000, 5f), ready.counter)
    }

    @Test
    fun `the stored counter of another reading is dropped and a new one begins at zero`() {
        val ready = start.withStored(GpsOdometer(39_000, 99f)).withLoggedMax(40_000)

        assertEquals(GpsOdometer(40_000, 0f), ready.counter)
    }

    @Test
    fun `without a stored counter a new one begins at zero`() {
        assertEquals(GpsOdometer(40_000, 0f), start.withStored(null).withLoggedMax(40_000).counter)
    }

    @Test
    fun `without any entry there is no counter and kilometres count for nothing`() {
        val ready = start.withStored(GpsOdometer(40_000, 5f)).withLoggedMax(null)

        assertTrue(ready.ready)
        assertNull(ready.counter)
        assertNull(ready.withDistance(3f).counter)
    }

    @Test
    fun `kilometres add up on a ready counter`() {
        val counter = start.withStored(null).withLoggedMax(40_000).withDistance(0.4f).withDistance(0.6f).counter

        assertEquals(GpsOdometer(40_000, 1.0f), counter)
    }

    @Test
    fun `kilometres driven before it is ready are not lost`() {
        val ready = start.withDistance(0.3f).withDistance(0.2f).withStored(GpsOdometer(40_000, 5f)).withLoggedMax(40_000)

        assertEquals(GpsOdometer(40_000, 5.5f), ready.counter)
    }

    @Test
    fun `kilometres driven before it is ready count for a new reading too`() {
        val ready = start.withDistance(0.5f).withStored(GpsOdometer(39_000, 5f)).withLoggedMax(40_000)

        assertEquals(GpsOdometer(40_000, 0.5f), ready.counter)
    }

    @Test
    fun `a new highest reading starts the counter over`() {
        val driven = start.withStored(null).withLoggedMax(40_000).withDistance(12f)

        assertEquals(GpsOdometer(40_000, 12f), driven.counter)
        assertEquals(GpsOdometer(40_300, 0f), driven.withLoggedMax(40_300).counter)
    }

    @Test
    fun `the same highest reading again changes nothing`() {
        val driven = start.withStored(null).withLoggedMax(40_000).withDistance(12f)

        assertEquals(driven.counter, driven.withLoggedMax(40_000).counter)
    }

    @Test
    fun `a lower highest reading, after deleting the newest entry, also starts over`() {
        val driven = start.withStored(null).withLoggedMax(40_000).withDistance(12f)

        assertEquals(GpsOdometer(39_500, 0f), driven.withLoggedMax(39_500).counter)
    }

    @Test
    fun `no entries left drops the counter`() {
        val driven = start.withStored(null).withLoggedMax(40_000).withDistance(12f)

        assertNull(driven.withLoggedMax(null).counter)
    }

    @Test
    fun `an unusable distance counts for nothing`() {
        val ready = start.withStored(null).withLoggedMax(40_000)

        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("$bad", GpsOdometer(40_000, 0f), ready.withDistance(bad).counter)
        }
        assertEquals("auch vor der Bereitschaft", GpsOdometer(40_000, 0f), start.withDistance(Float.NaN).withDistance(-3f).withStored(null).withLoggedMax(40_000).counter)
    }

    @Test
    fun `it is persisted when it is new, on a new anchor, after a step and after a reset, not after a small change`() {
        val written = GpsOdometer(40_000, 5f)

        assertTrue(shouldPersistOdometer(GpsOdometer(40_000, 5f), null))
        assertTrue("neuer Anker", shouldPersistOdometer(GpsOdometer(40_300, 0f), written))
        assertTrue("Schritt erreicht", shouldPersistOdometer(GpsOdometer(40_000, 5.5f), written))
        assertTrue("Zaehler zurueckgesetzt", shouldPersistOdometer(GpsOdometer(40_000, 0f), written))
        assertTrue("neuer Anker, auch wenn der Zaehler nicht unter dem alten liegt", shouldPersistOdometer(GpsOdometer(40_300, 0f), GpsOdometer(40_000, 0f)))
        assertFalse("kleine Aenderung", shouldPersistOdometer(GpsOdometer(40_000, 5.4f), written))
        assertFalse("unveraendert", shouldPersistOdometer(written, written))
    }

    @Test
    fun `the persist step is half a kilometre`() {
        assertEquals(0.5f, ODOMETER_PERSIST_STEP_KM, 0f)
    }

    // --- withRestored (Daten wiederherstellen) ---

    private val readyAt40000 = start.withStored(GpsOdometer(40_000, 5f)).withLoggedMax(40_000)

    @Test
    fun `a restored counter of the current reading replaces the counter at once`() {
        val r = readyAt40000.withRestored(GpsOdometer(40_000, 12.5f))

        assertEquals(GpsOdometer(40_000, 12.5f), r.counter)
    }

    @Test
    fun `a restored counter whose reading the log has not reported yet is kept and wins over the late report`() {
        val r = readyAt40000.withRestored(GpsOdometer(40_300, 7f)).withLoggedMax(40_300)

        assertEquals(GpsOdometer(40_300, 7f), r.counter)
    }

    @Test
    fun `the restored counter and kilometres counted afterwards add up`() {
        val r = readyAt40000.withRestored(GpsOdometer(40_300, 7f)).withLoggedMax(40_300).withDistance(1f)

        assertEquals(GpsOdometer(40_300, 8f), r.counter)
    }

    @Test
    fun `another reading than the restored one discards the note, a later report does not resurrect it`() {
        val r = readyAt40000.withRestored(GpsOdometer(40_300, 7f)).withLoggedMax(40_500).withLoggedMax(40_300)

        assertEquals(GpsOdometer(40_300, 0f), r.counter)
    }

    @Test
    fun `without any entry afterwards the note is discarded`() {
        val r = readyAt40000.withRestored(GpsOdometer(40_300, 7f)).withLoggedMax(null).withLoggedMax(40_300)

        assertEquals(GpsOdometer(40_300, 0f), r.counter)
    }

    @Test
    fun `before it is ready the restored counter wins over the stored one and the kilometres counted meanwhile are added`() {
        val r = start.withRestored(GpsOdometer(40_000, 12f)).withDistance(0.5f)
            .withStored(GpsOdometer(40_000, 99f)).withLoggedMax(40_000)

        assertEquals(GpsOdometer(40_000, 12.5f), r.counter)
    }

    @Test
    fun `before it is ready a restored counter of another reading is dropped when the highest reading arrives`() {
        val r = start.withRestored(GpsOdometer(41_000, 12f)).withStored(GpsOdometer(40_000, 3f)).withLoggedMax(40_000)

        assertEquals(GpsOdometer(40_000, 3f), r.counter)
    }

    @Test
    fun `an unusable restored value changes nothing`() {
        for (bad in listOf(-0.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val r = readyAt40000.withRestored(GpsOdometer(40_000, bad))

            assertEquals(bad.toString(), readyAt40000, r)
        }
    }
}
