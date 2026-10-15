package de.adv990de.obd2.ui.fuel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.fuel.FuelGaugeState
import de.adv990de.obd2.fuel.FuelRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Was die Tankanzeige in der Navigation zeigt, und ihr Zuruecksetzen. Eine duenne Huelle um das [FuelRepository]: der Zaehler gehoert ihm
 * (das Dashboard schreibt, diese Leiste liest), das ViewModel gibt der Oberflaeche nur den Zustand und den einen Befehl.
 */
@HiltViewModel
class FuelGaugeViewModel @Inject constructor(
    private val fuelRepository: FuelRepository,
) : ViewModel() {

    /** `null` = keine Reichweite hinterlegt (oder der Zaehler noch nicht gelesen): die Leiste bleibt ausgeblendet. */
    val gauge: StateFlow<FuelGaugeState?> = fuelRepository.state

    /** Setzt die Anzeige auf "voll" zurueck (nach dem Tanken). Die Rueckfrage stellt die Oberflaeche. */
    fun reset() = fuelRepository.reset()
}
