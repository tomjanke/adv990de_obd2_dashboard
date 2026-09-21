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
}
