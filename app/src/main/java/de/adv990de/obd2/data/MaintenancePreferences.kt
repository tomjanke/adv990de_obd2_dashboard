package de.adv990de.obd2.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import de.adv990de.obd2.logbook.GpsOdometer
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.MAINTENANCE_TYPES
import de.adv990de.obd2.logbook.MAX_ODOMETER_KM
import de.adv990de.obd2.logbook.MaintenanceIntervalLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Die Wartungsintervalle des Logbuchs (je Art in km, vom Nutzer festgelegt — **ohne Vorgabe**) und der GPS-Zaehler fuer den geschaetzten Kilometerstand ([GpsOdometer]) im DataStore.
 * Herausgeloest aus [AppPreferences] (wie [AlarmPreferences]); `AppPreferences` reicht Fluesse und Setter unveraendert nach aussen durch, [data] ist ihr Lese-Fluss.
 *
 * Die Schluessel sind persistierter Zustand — nie umbenennen oder mit anderer Bedeutung wiederverwenden (`MaintenancePreferencesTest` haelt sie fest). Ein gespeicherter Wert ausserhalb der
 * Grenzen (nur von Hand moeglich) liest als nicht festgelegt.
 */
internal class MaintenancePreferences(
    private val dataStore: DataStore<Preferences>,
    data: Flow<Preferences>,
) {
    /** Die festgelegten Intervalle; eine Art ohne gueltigen Wert fehlt in der Karte. */
    val intervals: Flow<Map<LogType, Int>> = data.map { prefs ->
        buildMap {
            for (type in MAINTENANCE_TYPES) {
                val km = prefs[intervalKey(type)] ?: continue
                if (km in MaintenanceIntervalLimits.MIN_KM..MaintenanceIntervalLimits.MAX_KM) put(type, km)
            }
        }
    }.distinctUntilChanged()

    /** [km] = 0 loescht das Intervall der Art; sonst muss der Wert in [MaintenanceIntervalLimits] liegen (die Eingabe prueft das schon, das ist das Netz). */
    suspend fun setInterval(type: LogType, km: Int) {
        require(type in MAINTENANCE_TYPES) { "no maintenance interval for $type" }
        require(km == 0 || km in MaintenanceIntervalLimits.MIN_KM..MaintenanceIntervalLimits.MAX_KM) { "maintenance interval out of bounds: $km" }
        dataStore.edit { prefs -> if (km == 0) prefs.remove(intervalKey(type)) else prefs[intervalKey(type)] = km }
    }

    /** Der GPS-Zaehler; `null`, solange keiner gespeichert ist. Ein negativer oder nicht endlicher Wert liest als 0. */
    val gpsOdometer: Flow<GpsOdometer?> = data.map { prefs ->
        val anchor = prefs[KEY_GPS_ANCHOR_KM] ?: return@map null
        GpsOdometer(anchor, (prefs[KEY_GPS_KM] ?: 0f).takeIf { it.isFinite() && it >= 0f } ?: 0f)
    }.distinctUntilChanged()

    suspend fun setGpsOdometer(anchorKm: Int, gpsKm: Float) {
        require(anchorKm in 0..MAX_ODOMETER_KM) { "odometer anchor out of bounds: $anchorKm" }
        require(gpsKm.isFinite() && gpsKm >= 0f) { "gps counter must be finite and >= 0: $gpsKm" }
        dataStore.edit {
            it[KEY_GPS_ANCHOR_KM] = anchorKm
            it[KEY_GPS_KM] = gpsKm
        }
    }

    private companion object {
        fun intervalKey(type: LogType) = intPreferencesKey("maintenance_interval_${type.name.lowercase()}_km")

        val KEY_GPS_ANCHOR_KM = intPreferencesKey("logbook_gps_anchor_km")
        val KEY_GPS_KM = floatPreferencesKey("logbook_gps_km")
    }
}
