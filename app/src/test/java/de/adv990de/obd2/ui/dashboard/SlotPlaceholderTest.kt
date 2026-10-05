package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.R
import de.adv990de.obd2.obd.PidRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class SlotPlaceholderTest {

    private val supported = setOf(PidRegistry.RPM.id, PidRegistry.COOLANT_TEMP.id)

    // --- Test 1 ---

    @Test
    fun `unassigned slot asks for a sensor`() {
        assertEquals(
            R.string.dashboard_slot_empty,
            slotPlaceholderRes(pidId = null, supportedPidIds = supported, isConnected = true)
        )
        assertEquals(
            R.string.dashboard_slot_empty,
            slotPlaceholderRes(pidId = "", supportedPidIds = supported, isConnected = true)
        )
    }

    // --- Test 2 ---

    @Test
    fun `assigned but unsupported pid says the vehicle does not provide it`() {
        assertEquals(
            R.string.dashboard_slot_unsupported,
            slotPlaceholderRes(PidRegistry.SPEED.id, supported, isConnected = true)
        )
    }

    // --- Test 3 ---

    @Test
    fun `supported pid without a reading keeps the neutral hint`() {
        // Kein Messwert, aber unterstuetzt: der Wert kann jeden Moment kommen — kein Fehlertext.
        assertEquals(
            R.string.dashboard_slot_empty,
            slotPlaceholderRes(PidRegistry.RPM.id, supported, isConnected = true)
        )
    }

    // --- Test 4 ---

    @Test
    fun `without a connection no statement about support is possible`() {
        assertEquals(
            R.string.dashboard_slot_empty,
            slotPlaceholderRes(PidRegistry.SPEED.id, supported, isConnected = false)
        )
    }

    // --- Test 5 ---

    @Test
    fun `before discovery finished no statement about support is possible`() {
        assertEquals(
            R.string.dashboard_slot_empty,
            slotPlaceholderRes(PidRegistry.SPEED.id, supportedPidIds = null, isConnected = true)
        )
    }
    // --- Test 6 ---

    @Test
    fun `with GPS enabled the speed slot waits for a fix instead of blaming the vehicle`() {
        // Das Fahrzeug liefert 010D nicht — mit eingeschaltetem GPS ist "nicht unterstuetzt"
        // trotzdem falsch, denn der Wert kommt gleich aus einer anderen Quelle.
        assertEquals(
            R.string.dashboard_slot_waiting_gps,
            slotPlaceholderRes(PidRegistry.SPEED.id, supported, isConnected = true, gpsSpeedEnabled = true)
        )
    }

    // --- Test 7 ---

    @Test
    fun `GPS switch does not change the hint for other pids`() {
        assertEquals(
            R.string.dashboard_slot_unsupported,
            slotPlaceholderRes(PidRegistry.INTAKE_AIR_TEMP.id, supported, isConnected = true, gpsSpeedEnabled = true)
        )
    }

    // --- B10: GPS on, but the permission is missing ---

    @Test
    fun `GPS speed on without the location permission says the permission is missing, not that it waits for a fix`() {
        assertEquals(
            R.string.dashboard_slot_gps_permission,
            slotPlaceholderRes(
                PidRegistry.SPEED.id, supported, isConnected = true,
                gpsSpeedEnabled = true, gpsPermissionMissing = true,
            )
        )
    }

    @Test
    fun `with the permission the GPS slot waits for a fix as before`() {
        assertEquals(
            R.string.dashboard_slot_waiting_gps,
            slotPlaceholderRes(
                PidRegistry.SPEED.id, supported, isConnected = true,
                gpsSpeedEnabled = true, gpsPermissionMissing = false,
            )
        )
    }

    @Test
    fun `a missing permission does not matter while GPS speed is off`() {
        assertEquals(
            R.string.dashboard_slot_unsupported,
            slotPlaceholderRes(
                PidRegistry.SPEED.id, supported, isConnected = true,
                gpsSpeedEnabled = false, gpsPermissionMissing = true,
            )
        )
    }

    @Test
    fun `the permission hint only applies to the speed slot`() {
        assertEquals(
            R.string.dashboard_slot_empty,
            slotPlaceholderRes(
                PidRegistry.RPM.id, supported, isConnected = true,
                gpsSpeedEnabled = true, gpsPermissionMissing = true,
            )
        )
    }
}
