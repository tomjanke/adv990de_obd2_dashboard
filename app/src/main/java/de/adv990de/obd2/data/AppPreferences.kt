// app/src/main/java/de/adv990de/obd2/data/AppPreferences.kt
package de.adv990de.obd2.data

import android.bluetooth.BluetoothAdapter
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.adv990de.obd2.bluetooth.BtType
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.obd.PidRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Top-level extension — single DataStore instance per Application context
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

@Singleton
class AppPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val bluetoothAdapter: BluetoothAdapter
) {
    companion object {
        private val KEY_DEVICE_ADDRESS = stringPreferencesKey("device_address")
        private val KEY_DEVICE_NAME    = stringPreferencesKey("device_name")
        private val KEY_DEVICE_TYPE    = stringPreferencesKey("device_type")
        private val KEY_SELECTED_PIDS  = stringSetPreferencesKey("selected_pid_ids")
        val DEFAULT_PIDS: Set<String>  = PidRegistry.defaults.map { it.id }.toSet()
    }

    suspend fun persistDevice(device: ScannedDevice) {
        dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ADDRESS] = device.device.address
            prefs[KEY_DEVICE_NAME]    = device.name
            prefs[KEY_DEVICE_TYPE]    = device.type.name
        }
    }

    val lastDevice: Flow<ScannedDevice?> = dataStore.data.map { prefs ->
        val address = prefs[KEY_DEVICE_ADDRESS] ?: return@map null
        val name    = prefs[KEY_DEVICE_NAME]    ?: return@map null
        val type    = prefs[KEY_DEVICE_TYPE]
            ?.let { runCatching { BtType.valueOf(it) }.getOrNull() }
            ?: return@map null
        val btDevice = try {
            bluetoothAdapter.getRemoteDevice(address)
        } catch (_: Exception) { return@map null }
        ScannedDevice(btDevice, type, name)
    }

    suspend fun togglePid(id: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SELECTED_PIDS] ?: DEFAULT_PIDS
            prefs[KEY_SELECTED_PIDS] = if (id in current) current - id else current + id
        }
    }

    val selectedPidIds: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_PIDS] ?: DEFAULT_PIDS
    }
}
