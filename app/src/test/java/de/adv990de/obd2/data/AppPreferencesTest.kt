// app/src/test/java/de/adv990de/obd2/data/AppPreferencesTest.kt
package de.adv990de.obd2.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import de.adv990de.obd2.bluetooth.BtType
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.obd.PidRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppPreferencesTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun makePreferences(): AppPreferences {
        // UnconfinedTestDispatcher is required: DataStore launches internal coroutines tied to its
        // own TestScope. Using StandardTestDispatcher would cause a scheduler deadlock because
        // DataStore's scope and runTest's scheduler are separate — DataStore's work never runs.
        // A fresh dispatcher per call avoids accidental state sharing between tests.
        val testDispatcher = UnconfinedTestDispatcher()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(testDispatcher),
            produceFile = { tempFolder.newFile("test.preferences_pb") }
        )
        val btAdapter = mockk<BluetoothAdapter> {
            every { getRemoteDevice("AA:BB:CC:DD:EE:FF") } returns mockk<BluetoothDevice> {
                every { address } returns "AA:BB:CC:DD:EE:FF"
            }
        }
        return AppPreferences(dataStore, btAdapter)
    }

    // ── slotPids / setSlotPid ─────────────────────────────────────────────────

    @Test
    fun `slotPids returns DEFAULT_SLOT_PIDS when nothing is stored`() = runTest {
        val prefs = makePreferences()
        val slots = prefs.slotPids.first()
        // slotPids always pads to exactly 4 entries; DEFAULT_SLOT_PIDS may be shorter
        // (currently 3). Verify each default entry appears in the matching slot.
        AppPreferences.DEFAULT_SLOT_PIDS.forEachIndexed { i, id ->
            assertEquals("slot $i should match DEFAULT_SLOT_PIDS[$i]", id, slots[i])
        }
    }

    @Test
    fun `setSlotPid updates the correct slot and leaves others unchanged`() = runTest {
        val prefs = makePreferences()
        // Use the PID at index 1 of defaults so it is a known-valid, distinct value.
        val newPid = PidRegistry.defaults[1].id
        prefs.setSlotPid(2, newPid)

        val slots = prefs.slotPids.first()
        assertEquals(AppPreferences.DEFAULT_SLOT_PIDS[0], slots[0])
        assertEquals(AppPreferences.DEFAULT_SLOT_PIDS[1], slots[1])
        assertEquals(newPid,                               slots[2])
        // Slot 3 was never written; it falls back to DEFAULT_SLOT_PIDS[3] if it exists,
        // otherwise to empty string (DEFAULT_SLOT_PIDS has fewer than 4 entries currently).
        val expectedSlot3 = AppPreferences.DEFAULT_SLOT_PIDS.getOrElse(3) { "" }
        assertEquals(expectedSlot3, slots[3])
    }

    @Test
    fun `slotPids always returns exactly 4 entries`() = runTest {
        val prefs = makePreferences()
        // Verify size on a fresh store.
        assertEquals(4, prefs.slotPids.first().size)

        // And after an update.
        prefs.setSlotPid(0, PidRegistry.defaults[0].id)
        assertEquals(4, prefs.slotPids.first().size)
    }

    // ── lastDevice ────────────────────────────────────────────────────────────

    @Test
    fun `lastDevice is null when nothing is persisted`() = runTest {
        val prefs = makePreferences()
        assertNull(prefs.lastDevice.first())
    }

    @Test
    fun `persistDevice then lastDevice round-trips name and type`() = runTest {
        val btDevice = mockk<BluetoothDevice> { every { address } returns "AA:BB:CC:DD:EE:FF" }
        val scanned = ScannedDevice(btDevice, BtType.CLASSIC, "ELM327-Classic")
        val prefs = makePreferences()

        prefs.persistDevice(scanned)
        val last = prefs.lastDevice.first()

        assertNotNull(last)
        assertEquals("ELM327-Classic", last!!.name)
        assertEquals(BtType.CLASSIC, last.type)
        assertEquals("AA:BB:CC:DD:EE:FF", last.device!!.address)
    }

    // ── persistDevice SIMULATOR guard ────────────────────────────────────────

    @Test
    fun `persistDevice with SIMULATOR device does not persist anything`() = runTest {
        val prefs = makePreferences()
        val simDevice = ScannedDevice(device = null, type = BtType.SIMULATOR, name = "OBD Sim")
        prefs.persistDevice(simDevice)
        assertNull(prefs.lastDevice.first())
    }

    // ── loggedPidIds / toggleLoggedPid / setLoggedPids ────────────────────────

    @Test
    fun `loggedPidIds defaults to ALL_PID_IDS when nothing stored`() = runTest {
        val prefs = makePreferences()
        assertEquals(AppPreferences.ALL_PID_IDS, prefs.loggedPidIds.first())
    }

    @Test
    fun `toggleLoggedPid removes pid when it is in the set`() = runTest {
        val prefs = makePreferences()
        // Fresh store defaults to ALL_PID_IDS which contains RPM.
        val rpmId = PidRegistry.RPM.id
        assert(rpmId in prefs.loggedPidIds.first()) { "Precondition: $rpmId must be in ALL_PID_IDS" }
        prefs.toggleLoggedPid(rpmId)
        assert(rpmId !in prefs.loggedPidIds.first()) { "$rpmId should have been removed" }
    }

    @Test
    fun `toggleLoggedPid adds pid when it is not in the set`() = runTest {
        val prefs = makePreferences()
        val rpmId = PidRegistry.RPM.id
        prefs.setLoggedPids(emptySet())
        assert(rpmId !in prefs.loggedPidIds.first()) { "Precondition: set must be empty" }
        prefs.toggleLoggedPid(rpmId)
        assert(rpmId in prefs.loggedPidIds.first()) { "$rpmId should have been added" }
    }

    @Test
    fun `setLoggedPids persists empty set`() = runTest {
        val prefs = makePreferences()
        prefs.setLoggedPids(emptySet())
        assertEquals(emptySet<String>(), prefs.loggedPidIds.first())
    }

    // ── showAllBtDevices ──────────────────────────────────────────────────────

    @Test
    fun `showAllBtDevices defaults to false`() = runTest {
        assertEquals(false, makePreferences().showAllBtDevices.first())
    }

    @Test
    fun `setShowAllBtDevices persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setShowAllBtDevices(true)
        assertEquals(true, prefs.showAllBtDevices.first())
    }

    // ── hideNavOnDrive ────────────────────────────────────────────────────────

    @Test
    fun `hideNavOnDrive defaults to false`() = runTest {
        assertEquals(false, makePreferences().hideNavOnDrive.first())
    }

    @Test
    fun `setHideNavOnDrive persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setHideNavOnDrive(true)
        assertEquals(true, prefs.hideNavOnDrive.first())
    }

    // ── rpmBlinkThreshold ─────────────────────────────────────────────────────

    @Test
    fun `rpmBlinkThreshold defaults to 0`() = runTest {
        assertEquals(0, makePreferences().rpmBlinkThreshold.first())
    }

    @Test
    fun `setRpmBlinkThreshold persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setRpmBlinkThreshold(4500)
        assertEquals(4500, prefs.rpmBlinkThreshold.first())
    }

    // ── rpmDynamicThreshold ───────────────────────────────────────────────────

    @Test
    fun `rpmDynamicThreshold defaults to false`() = runTest {
        assertEquals(false, makePreferences().rpmDynamicThreshold.first())
    }

    @Test
    fun `setRpmDynamicThreshold persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setRpmDynamicThreshold(true)
        assertEquals(true, prefs.rpmDynamicThreshold.first())
    }

    // ── Display preferences ───────────────────────────────────────────────────

    @Test
    fun `useImperial defaults to false`() = runTest {
        assertEquals(false, makePreferences().useImperial.first())
    }

    @Test
    fun `setUseImperial persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setUseImperial(true)
        assertEquals(true, prefs.useImperial.first())
    }

    @Test
    fun `theme defaults to system`() = runTest {
        assertEquals("system", makePreferences().theme.first())
    }

    @Test
    fun `setTheme persists value`() = runTest {
        val prefs = makePreferences()
        prefs.setTheme("dark")
        assertEquals("dark", prefs.theme.first())
    }
}
