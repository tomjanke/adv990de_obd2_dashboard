package de.adv990de.obd2.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import de.adv990de.obd2.alarm.AlarmSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Die Alarm-Einstellungen im DataStore (Spannung, Kuehlmittel, Fehlercodes unterwegs — s. `de.adv990de.obd2.alarm`): Schluessel, Lesen und Schreiben. Herausgeloest aus [AppPreferences] (die
 * Datei war auf 461 Zeilen gewachsen); `AppPreferences` reicht den Fluss und die Setter unveraendert nach aussen durch, [data] ist ihr Lese-Fluss (E/A-Fehler = Standardwerte).
 *
 * Die Schluessel sind persistierter Zustand (DataStore) — nie umbenennen oder mit anderer Bedeutung wiederverwenden, sonst faellt die Einstellung bei jedem Nutzer still auf den Standard
 * zurueck (`AlarmPreferencesTest` haelt sie fest).
 */
internal class AlarmPreferences(
    private val dataStore: DataStore<Preferences>,
    data: Flow<Preferences>,
) {
    /**
     * Alle Alarm-Einstellungen als ein Wert: Der Alarm-Tracker braucht sie immer zusammen, und ein einziger Fluss stellt sicher, dass
     * er nie einen halb geaenderten Satz sieht. Die vier Schwellen sind Werte aus den Auswahllisten von [AlarmSettings] — ein
     * gespeicherter Wert ausserhalb (nur von Hand oder aus einer spaeteren Aenderung der Liste moeglich) wird beim LESEN auf die
     * naechste Option gesetzt ([snapToOption], Gleichstand: die groessere), ohne Migration und ohne Zurueckschreiben, genau wie beim
     * `obdWatchdogTimeoutSeconds`. **Ohne gespeicherte Werte gilt `AlarmSettings()`** — die Standardwerte stehen dort einmal, auch die der drei Schalter (alle an).
     *
     * `distinctUntilChanged`: DataStore emittiert bei jedem Schreibvorgang, auch fuer fremde Schluessel (Theme, Einheiten); ohne das
     * liefe der Tracker bei jedem davon mit unveraenderten Werten erneut an.
     */
    val settings: Flow<AlarmSettings> = data.map { prefs ->
        val defaults = AlarmSettings()
        AlarmSettings(
            voltageEnabled = prefs[KEY_VOLTAGE_ENABLED] ?: defaults.voltageEnabled,
            coolantEnabled = prefs[KEY_COOLANT_ENABLED] ?: defaults.coolantEnabled,
            faultsEnabled = prefs[KEY_FAULTS_ENABLED] ?: defaults.faultsEnabled,
            voltageNoticeDeciV = snapToOption(prefs[KEY_VOLTAGE_NOTICE_DV] ?: defaults.voltageNoticeDeciV, AlarmSettings.VOLTAGE_NOTICE_OPTIONS_DV),
            voltageWarningDeciV = snapToOption(prefs[KEY_VOLTAGE_WARNING_DV] ?: defaults.voltageWarningDeciV, AlarmSettings.VOLTAGE_WARNING_OPTIONS_DV),
            coolantNoticeC = snapToOption(prefs[KEY_COOLANT_NOTICE_C] ?: defaults.coolantNoticeC, AlarmSettings.COOLANT_NOTICE_OPTIONS_C),
            coolantWarningC = snapToOption(prefs[KEY_COOLANT_WARNING_C] ?: defaults.coolantWarningC, AlarmSettings.COOLANT_WARNING_OPTIONS_C),
        )
    }.distinctUntilChanged()

    suspend fun setVoltageEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_VOLTAGE_ENABLED] = enabled }
    }

    suspend fun setCoolantEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_COOLANT_ENABLED] = enabled }
    }

    suspend fun setFaultsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_FAULTS_ENABLED] = enabled }
    }

    // Die Schwellen werden unveraendert gespeichert: das Einstellungs-UI bietet nur Listenwerte an, ein anderer Wert wuerde ohnehin
    // beim Lesen auf die naechste Option gesetzt (wie bei setObdWatchdogTimeoutSeconds).

    suspend fun setVoltageNoticeDeciV(value: Int) {
        dataStore.edit { it[KEY_VOLTAGE_NOTICE_DV] = value }
    }

    suspend fun setVoltageWarningDeciV(value: Int) {
        dataStore.edit { it[KEY_VOLTAGE_WARNING_DV] = value }
    }

    suspend fun setCoolantNoticeC(value: Int) {
        dataStore.edit { it[KEY_COOLANT_NOTICE_C] = value }
    }

    suspend fun setCoolantWarningC(value: Int) {
        dataStore.edit { it[KEY_COOLANT_WARNING_C] = value }
    }

    private companion object {
        val KEY_VOLTAGE_ENABLED = booleanPreferencesKey("alarm_voltage_enabled")
        val KEY_COOLANT_ENABLED = booleanPreferencesKey("alarm_coolant_enabled")
        val KEY_FAULTS_ENABLED = booleanPreferencesKey("alarm_faults_enabled")
        val KEY_VOLTAGE_NOTICE_DV = intPreferencesKey("alarm_voltage_notice_dv")
        val KEY_VOLTAGE_WARNING_DV = intPreferencesKey("alarm_voltage_warning_dv")
        val KEY_COOLANT_NOTICE_C = intPreferencesKey("alarm_coolant_notice_c")
        val KEY_COOLANT_WARNING_C = intPreferencesKey("alarm_coolant_warning_c")
    }
}
