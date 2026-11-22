package de.adv990de.obd2.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.MapRepository
import de.adv990de.obd2.map.MapImportResult
import de.adv990de.obd2.map.MapProblem
import de.adv990de.obd2.map.MapState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Wo das Einspielen einer Karten-Datei steht (nur die Oberflaeche braucht das). */
sealed interface MapImportUi {
    data object Idle : MapImportUi

    /** Die Datei wird kopiert und geprueft; das dauert bei einer grossen Datei Minuten. */
    data object Working : MapImportUi

    data class Failed(val problem: MapProblem) : MapImportUi
}

/** Die Zeile „Karte“ der Einstellungen: [mapState] aus dem [MapRepository], Einspielen und Entfernen. Ein Einspielen laeuft hoechstens einmal zugleich. */
@HiltViewModel
class MapSettingsViewModel @Inject constructor(private val repository: MapRepository) : ViewModel() {

    val mapState: StateFlow<MapState> = repository.state

    private val _importState = MutableStateFlow<MapImportUi>(MapImportUi.Idle)
    val importState: StateFlow<MapImportUi> = _importState.asStateFlow()

    /** Spielt die Datei hinter [uri] ein. Waehrend ein Einspielen laeuft, tut ein zweiter Aufruf nichts. */
    fun import(uri: Uri) {
        if (!_importState.compareAndSet(MapImportUi.Idle, MapImportUi.Working)) {
            // Ein altes Fehlerergebnis darf ein neues Einspielen nicht blockieren.
            if (_importState.value !is MapImportUi.Failed || !_importState.compareAndSet(_importState.value, MapImportUi.Working)) return
        }
        viewModelScope.launch {
            try {
                _importState.value = when (val r = repository.import(uri)) {
                    is MapImportResult.Success -> MapImportUi.Idle
                    is MapImportResult.Failure -> MapImportUi.Failed(r.problem)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _importState.value = MapImportUi.Idle
                throw e
            }
        }
    }

    /** Fuer den Fall, dass das System keinen Dateidialog hat. */
    fun reportUnavailable() {
        if (_importState.value !is MapImportUi.Working) _importState.value = MapImportUi.Failed(MapProblem.UNREADABLE)
    }

    /** Schliesst die Meldung eines gescheiterten Einspielens. */
    fun dismissFailure() {
        if (_importState.value is MapImportUi.Failed) _importState.value = MapImportUi.Idle
    }

    fun remove() {
        viewModelScope.launch { repository.remove() }
    }
}
