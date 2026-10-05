package de.adv990de.obd2.ui.permission

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.adv990de.obd2.location.LocationPermissions

/**
 * Der aktuelle Stand der Standortberechtigungen als Compose-State.
 *
 * Eine Berechtigung kann sich AUSSERHALB der App aendern (in den Systemeinstellungen erteilt oder
 * entzogen, "Nur dieses Mal" abgelaufen). Wer den Wert einmal beim Komponieren liest, zeigt danach
 * Veraltetes — [rememberLocationPermissionState] liest bei jedem `ON_RESUME` neu, und Aufrufer, die selbst
 * einen Dialog starten, rufen [refresh] mit dem Ergebnis (B10, B21).
 */
@Stable
class LocationPermissionState internal constructor(private val context: Context) {
    var fine by mutableStateOf(LocationPermissions.hasFineLocation(context))
        private set
    var coarse by mutableStateOf(LocationPermissions.hasCoarseLocation(context))
        private set

    fun refresh() {
        fine = LocationPermissions.hasFineLocation(context)
        coarse = LocationPermissions.hasCoarseLocation(context)
    }
}

@Composable
fun rememberLocationPermissionState(): LocationPermissionState {
    val context = LocalContext.current
    val state = remember(context) { LocationPermissionState(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}
