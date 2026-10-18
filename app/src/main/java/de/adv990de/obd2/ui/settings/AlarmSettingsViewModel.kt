package de.adv990de.obd2.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.alarm.AlarmSettings
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.seedBlocking
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Die Einstellungsgruppe „Alarme“: der gebündelte Zustand ([AlarmSettings]) und sieben Setter, die jeweils genau einen Schlüssel schreiben. Die Schwellen bietet die
 * Oberfläche nur als Listenwerte an (`AlarmSettings.…_OPTIONS_…`); ein anderer Wert würde beim Lesen ohnehin auf die nächste Option gesetzt (`AppPreferences.alarmSettings`),
 * deshalb prüft der Setter ihn nicht noch einmal.
 */
@HiltViewModel
class AlarmSettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
) : ViewModel() {

    /**
     * Startwert synchron gelesen ([seedBlocking], wie bei der Reichweite): die Schalter bestimmen, welche Segmentzeilen es gibt — ein Start bei den Standardwerten
     * (alle an) ließe die Zeilen eines ausgeschalteten Alarms ein Bild lang aufblitzen und die Seite springen. Ohne gespeicherten Wert gelten die Standardwerte.
     */
    val settings: StateFlow<AlarmSettings> = appPreferences.alarmSettings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        seedBlocking(AlarmSettings(), source = appPreferences.alarmSettings),
    )

    fun setVoltageEnabled(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setAlarmVoltageEnabled(enabled) }
    }

    fun setCoolantEnabled(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setAlarmCoolantEnabled(enabled) }
    }

    fun setFaultsEnabled(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setAlarmFaultsEnabled(enabled) }
    }

    /** Spannung in Zehntelvolt, unter der ein Hinweis (gelb) kommt. */
    fun setVoltageNoticeDeciV(value: Int) {
        viewModelScope.launch { appPreferences.setAlarmVoltageNoticeDeciV(value) }
    }

    /** Spannung in Zehntelvolt, unter der eine Warnung (rot) kommt. */
    fun setVoltageWarningDeciV(value: Int) {
        viewModelScope.launch { appPreferences.setAlarmVoltageWarningDeciV(value) }
    }

    /** Kühlmitteltemperatur in °C, ab der ein Hinweis (gelb) kommt. */
    fun setCoolantNoticeC(value: Int) {
        viewModelScope.launch { appPreferences.setAlarmCoolantNoticeC(value) }
    }

    /** Kühlmitteltemperatur in °C, ab der eine Warnung (rot) kommt. */
    fun setCoolantWarningC(value: Int) {
        viewModelScope.launch { appPreferences.setAlarmCoolantWarningC(value) }
    }
}
