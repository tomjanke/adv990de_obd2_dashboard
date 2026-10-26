package de.adv990de.obd2.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.seedBlocking
import de.adv990de.obd2.fuel.FuelCapacityLimits
import de.adv990de.obd2.fuel.FuelRangeSource
import de.adv990de.obd2.fuel.LearnedRange
import de.adv990de.obd2.fuel.FuelRangeLimits
import de.adv990de.obd2.fuel.FuelReserveLimits
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Die Einstellungen der virtuellen Tankanzeige in der Gruppe "Fahrzeug": "Reichweite bei vollem Tank", "Tankinhalt" und "Reserve". */
@HiltViewModel
class FuelSettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    rangeSource: FuelRangeSource,
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

    /** Tankinhalt in Zehntelliter; 0 = nicht festgelegt. Mit Tankinhalt berechnet die Tankanzeige ihre Reichweite aus dem Logbuch. Startwert synchron gelesen. */
    val tankCapacityDl: StateFlow<Int> = appPreferences.fuelTankCapacityDl.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        seedBlocking(0, source = appPreferences.fuelTankCapacityDl),
    )

    /** Speichert den Tankinhalt; 0 loescht ihn. Ein Wert ausserhalb von [FuelCapacityLimits] wird nicht gespeichert (die Eingabe prueft ihn schon). */
    fun setTankCapacityDl(dl: Int) {
        if (dl != 0 && dl !in FuelCapacityLimits.MIN_DL..FuelCapacityLimits.MAX_DL) return
        viewModelScope.launch { appPreferences.setFuelTankCapacityDl(dl) }
    }

    /** Die aus dem Logbuch berechnete Reichweite samt Verbrauch (`null` = keine); die Zeile "Reichweite bei vollem Tank" nennt sie in ihrer Zusammenfassung. */
    val learned: StateFlow<LearnedRange?> = rangeSource.learned.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Reserve in km Restreichweite; 0 = aus. Startwert synchron gelesen (kein Blitz von "Aus"). */
    val reserveKm: StateFlow<Int> = appPreferences.fuelReserveKm.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        seedBlocking(0, source = appPreferences.fuelReserveKm),
    )

    /** Speichert die Reserve; 0 schaltet sie aus. Ein Wert ausserhalb von [FuelReserveLimits] wird nicht gespeichert (die Eingabe prueft ihn schon). */
    fun setReserveKm(km: Int) {
        if (km != 0 && km !in FuelReserveLimits.MIN_KM..FuelReserveLimits.MAX_KM) return
        viewModelScope.launch { appPreferences.setFuelReserveKm(km) }
    }
}

/** Die Werte der Tankanzeige-Einstellungen, wie die Gruppe "Fahrzeug" sie zeigt: die manuelle Reichweite, die berechnete (falls es eine gibt), der Tankinhalt und die Reserve. */
internal data class FuelSettingsUi(
    val rangeFullKm: Int,
    val learned: LearnedRange?,
    val tankCapacityDl: Int,
    val reserveKm: Int,
)
