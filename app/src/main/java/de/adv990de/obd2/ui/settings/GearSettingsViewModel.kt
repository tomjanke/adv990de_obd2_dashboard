package de.adv990de.obd2.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.AppPreferences
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.ui.dashboard.closeGearPairs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GearSettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val appLogRepository: AppLogRepository,
) : ViewModel() {

    val wheelCircumferenceMm: StateFlow<Float> = appPreferences.wheelCircumferenceMm
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences.DEFAULT_WHEEL_CIRCUMFERENCE_MM)

    val primaryRatio: StateFlow<Float> = appPreferences.primaryRatio
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences.DEFAULT_PRIMARY_RATIO)

    val secondaryRatio: StateFlow<Float> = appPreferences.secondaryRatio
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences.DEFAULT_SECONDARY_RATIO)

    val gearRatios: StateFlow<List<Float>> = appPreferences.gearRatios
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences.DEFAULT_GEAR_RATIOS)

    /**
     * Gangpaare, deren Gesamt-Uebersetzung zu nah beieinander liegt, um die Hysterese in
     * `determineGear` noch zuverlaessig zwischen ihnen unterscheiden zu lassen (s. `closeGearPairs`-
     * KDoc). Rein informativ — blockiert das Speichern nicht, da die einzelnen Werte fuer sich
     * genommen weiterhin gueltige positive Zahlen sind.
     */
    val gearRatioConflicts: StateFlow<List<Pair<Int, Int>>> = combine(
        primaryRatio, secondaryRatio, gearRatios,
    ) { primary, secondary, gears -> closeGearPairs(primary, secondary, gears) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setWheelCircumferenceMm(value: Float) = persist { appPreferences.setWheelCircumferenceMm(value) }

    fun setPrimaryRatio(value: Float) = persist { appPreferences.setPrimaryRatio(value) }

    fun setSecondaryRatio(value: Float) = persist { appPreferences.setSecondaryRatio(value) }

    fun setGearRatio(index: Int, value: Float) = persist { appPreferences.setGearRatio(index, value) }

    /**
     * Faengt einen fehlgeschlagenen DataStore-Write ab, statt ihn unbehandelt aus der
     * viewModelScope-Coroutine entkommen zu lassen (das wuerde die App abstuerzen lassen). Der
     * Nutzer sieht seine Eingabe weiterhin im lokalen Textpuffer von `GearRatioField` — nur die
     * Persistierung ist fehlgeschlagen, worueber `AppLogRepository` (im Settings-Screen sichtbar)
     * informiert, statt es unbemerkt zu verschlucken.
     */
    private fun persist(write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appLogRepository.log(
                    LogLevel.ERROR,
                    "Getriebe-Einstellung konnte nicht gespeichert werden: ${e.message ?: "unknown"}"
                )
            }
        }
    }
}
