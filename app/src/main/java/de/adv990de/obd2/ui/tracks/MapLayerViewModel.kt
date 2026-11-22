package de.adv990de.obd2.ui.tracks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.MapRepository
import de.adv990de.obd2.map.MapState
import de.adv990de.obd2.map.MapTiles
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Gibt den Ansichten eines Tracks die eingespielte Karte: [tiles] ist `null` ohne Karte (oder mit einer, die nicht taugt) und sonst der Leser samt Angaben. Er folgt dem Einspielen und
 * Entfernen in den Einstellungen. Eine Ansicht ohne Karte zeichnet wie vorher.
 */
@HiltViewModel
class MapLayerViewModel @Inject constructor(repository: MapRepository) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val tiles: StateFlow<MapTiles?> = repository.state
        .mapLatest { state -> if (state is MapState.Ready) repository.archive()?.let { MapTiles(it, state.info) } else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
