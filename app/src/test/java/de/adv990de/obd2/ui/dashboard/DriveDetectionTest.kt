package de.adv990de.obd2.ui.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fahrterkennung mit Hysterese (B11): GPS-Rauschen im Stand darf Hauptmenue und Statuszeile nicht umschalten. */
class DriveDetectionTest {

    @Test
    fun `no speed means standing - the navigation must never stay hidden for good`() {
        assertFalse(isMoving(speedKmh = null, wasMoving = false))
        assertFalse(isMoving(speedKmh = null, wasMoving = true))
    }

    @Test
    fun `GPS noise below the enter threshold does not start a drive`() {
        for (noise in listOf(0f, 0.1f, 0.4f, 1.0f, 1.8f, 2.9f, 4.9f)) {
            assertFalse("$noise km/h darf keine Fahrt starten", isMoving(noise, wasMoving = false))
        }
    }

    @Test
    fun `reaching the enter threshold starts a drive`() {
        assertTrue(isMoving(MOVING_ENTER_KMH, wasMoving = false))
        assertTrue(isMoving(30f, wasMoving = false))
    }

    @Test
    fun `a drive that is going on survives a slow crawl between the two thresholds`() {
        // Stop-and-go: 6 -> 4 km/h ist noch Fahrt, sonst flackerte das Menue im Stau.
        assertTrue(isMoving(4f, wasMoving = true))
        assertTrue(isMoving(MOVING_LEAVE_KMH, wasMoving = true))
    }

    @Test
    fun `falling below the leave threshold ends the drive`() {
        assertFalse(isMoving(MOVING_LEAVE_KMH - 0.1f, wasMoving = true))
        assertFalse(isMoving(0f, wasMoving = true))
    }

    @Test
    fun `the two thresholds leave a hysteresis band`() {
        assertTrue("Enter muss ueber Leave liegen", MOVING_ENTER_KMH > MOVING_LEAVE_KMH)
        // Im Band entscheidet allein der bisherige Zustand.
        val inBand = (MOVING_ENTER_KMH + MOVING_LEAVE_KMH) / 2
        assertFalse(isMoving(inBand, wasMoving = false))
        assertTrue(isMoving(inBand, wasMoving = true))
    }

    @Test
    fun `implausible values count as standing`() {
        assertFalse(isMoving(Float.NaN, wasMoving = true))
        assertFalse(isMoving(Float.NaN, wasMoving = false))
        assertFalse(isMoving(Float.POSITIVE_INFINITY, wasMoving = false))
        assertFalse(isMoving(-5f, wasMoving = true))
    }
}
