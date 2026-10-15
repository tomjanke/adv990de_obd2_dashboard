package de.adv990de.obd2.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.seedBlocking
import de.adv990de.obd2.fuel.FuelRangeLimits
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Die Einstellung "Reichweite bei vollem Tank" (Gruppe "Fahrzeug") der virtuellen Tankanzeige. */
@HiltViewModel
class FuelSettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
) : ViewModel() {

    /** Reichweite bei vollem Tank in km; 0 = nicht festgelegt. Startwert synchron gelesen: die Zeile zeigt nie kurz "nicht festgelegt". */
    val rangeFullKm: StateFlow<Int> = appPreferences.fuelRangeFullKm.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        seedBlocking(0, source = appPreferences.fuelRangeFullKm),
    )

    /** Speichert die Reichweite; 0 loescht sie. Ein Wert ausserhalb von [FuelRangeLimits] wird nicht gespeichert (die Eingabe prueft ihn schon). */
    fun setRangeFullKm(km: Int) {
        if (km != 0 && km !in FuelRangeLimits.MIN_KM..FuelRangeLimits.MAX_KM) return
        viewModelScope.launch { appPreferences.setFuelRangeFullKm(km) }
    }
}
