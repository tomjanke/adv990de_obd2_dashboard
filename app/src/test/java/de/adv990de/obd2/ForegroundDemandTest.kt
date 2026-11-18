package de.adv990de.obd2

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundDemandTest {

    private val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    private fun type(obd: Boolean, track: Boolean, obdWantsLocation: Boolean = false) =
        foregroundServiceType(ForegroundDemand(obd, track), obdWantsLocation)

    // ── der Dienst laeuft, solange einer etwas will ──────────────────────────

    @Test
    fun `the service is wanted while at least one demand is on`() {
        assertFalse(ForegroundDemand(obd = false, track = false).any)
        assertTrue(ForegroundDemand(obd = true, track = false).any)
        assertTrue(ForegroundDemand(obd = false, track = true).any)
        assertTrue(ForegroundDemand(obd = true, track = true).any)
    }

    // ── Typ ──────────────────────────────────────────────────────────────────

    @Test
    fun `without any demand there is no type`() {
        assertEquals(0, type(obd = false, track = false))
        assertEquals("a GPS setting alone starts nothing", 0, type(obd = false, track = false, obdWantsLocation = true))
    }

    @Test
    fun `only the adapter - connected device, plus location only when the GPS setting asks for it`() {
        assertEquals(device, type(obd = true, track = false, obdWantsLocation = false))
        assertEquals(device or location, type(obd = true, track = false, obdWantsLocation = true))
    }

    @Test
    fun `only the track - location alone, no Bluetooth type without Bluetooth use`() {
        assertEquals(location, type(obd = false, track = true))
        assertEquals("the GPS speed setting is about the adapter, not the track", location, type(obd = false, track = true, obdWantsLocation = true))
    }

    @Test
    fun `both - connected device and location, whatever the GPS setting says`() {
        assertEquals(device or location, type(obd = true, track = true, obdWantsLocation = false))
        assertEquals(device or location, type(obd = true, track = true, obdWantsLocation = true))
    }

    @Test
    fun `whether the type has the location bit`() {
        assertFalse(hasLocationType(device))
        assertTrue(hasLocationType(location))
        assertTrue(hasLocationType(device or location))
        assertFalse(hasLocationType(0))
    }

    // ── Benachrichtigung ─────────────────────────────────────────────────────

    @Test
    fun `the notification follows the demand`() {
        assertNull(notificationKind(ForegroundDemand(obd = false, track = false)))
        assertEquals(NotificationKind.OBD, notificationKind(ForegroundDemand(obd = true, track = false)))
        assertEquals(NotificationKind.TRACK, notificationKind(ForegroundDemand(obd = false, track = true)))
        assertEquals(NotificationKind.BOTH, notificationKind(ForegroundDemand(obd = true, track = true)))
    }
}
