// app/src/main/java/de/adv990de/obd2/data/AppPreferences.kt
package de.adv990de.obd2.data

import android.bluetooth.BluetoothAdapter
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        private val KEY_LOG_ONLY_DTC   = booleanPreferencesKey("log_only_dtc_sessions")
        private val KEY_LOG_ONLY_DISC  = booleanPreferencesKey("log_only_discovered_pids")
        private val KEY_LOGGED_PIDS    = stringSetPreferencesKey("logged_pid_ids")
        val DEFAULT_PIDS: Set<String>  = PidRegistry.defaults.map { it.id }.toSet()
        val ALL_PID_IDS: Set<String>   = PidRegistry.all.keys.toSet()
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
            ?.let { try { BtType.valueOf(it) } catch (_: IllegalArgumentException) { null } }
            ?: return@map null
        val btDevice = try {
            bluetoothAdapter.getRemoteDevice(address)
        } catch (_: Exception) { return@map null }
        ScannedDevice(btDevice, type, name)
    }

    suspend fun togglePid(id: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SELECTED_PIDS] ?: DEFAULT_PIDS
            // Guard: never allow an empty selection. The UI enforces this too, but the
            // persistence layer is the canonical safeguard against direct or future call sites.
            if (id in current && current.size == 1) return@edit
            prefs[KEY_SELECTED_PIDS] = if (id in current) current - id else current + id
        }
    }

    val selectedPidIds: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_SELECTED_PIDS] ?: DEFAULT_PIDS
    }

    // ── Recording preferences ─────────────────────────────────────────────────

    val logOnlyDtcSessions: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_LOG_ONLY_DTC] ?: false
    }

    val logOnlyDiscoveredPids: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_LOG_ONLY_DISC] ?: false
    }

    val loggedPidIds: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_LOGGED_PIDS] ?: ALL_PID_IDS
    }

    suspend fun setLogOnlyDtcSessions(enabled: Boolean) {
        dataStore.edit { it[KEY_LOG_ONLY_DTC] = enabled }
    }

    suspend fun setLogOnlyDiscoveredPids(enabled: Boolean) {
        dataStore.edit { it[KEY_LOG_ONLY_DISC] = enabled }
    }

    suspend fun toggleLoggedPid(id: String) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_LOGGED_PIDS] ?: ALL_PID_IDS
            prefs[KEY_LOGGED_PIDS] = if (id in current) current - id else current + id
        }
    }

    suspend fun setLoggedPids(ids: Set<String>) {
        dataStore.edit { it[KEY_LOGGED_PIDS] = ids }
    }
}
