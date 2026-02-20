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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppPreferencesTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    private fun makePreferences(): AppPreferences {
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

    @Test
    fun `selectedPidIds returns defaults when nothing is persisted`() = runTest {
        val prefs = makePreferences()
        val ids = prefs.selectedPidIds.first()
        assertEquals(PidRegistry.defaults.map { it.id }.toSet(), ids)
    }

    @Test
    fun `togglePid adds a PID that was not selected`() = runTest {
        val prefs = makePreferences()
        prefs.togglePid(PidRegistry.MAF.id)
        val ids = prefs.selectedPidIds.first()
        assertTrue(PidRegistry.MAF.id in ids)
    }

    @Test
    fun `togglePid removes a PID that was already selected`() = runTest {
        val prefs = makePreferences()
        val defaultId = PidRegistry.defaults.first().id
        prefs.togglePid(defaultId)  // remove
        val ids = prefs.selectedPidIds.first()
        assertTrue(defaultId !in ids)
    }

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
        assertEquals("AA:BB:CC:DD:EE:FF", last.device.address)
    }
}
