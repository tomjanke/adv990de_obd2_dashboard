package de.adv990de.obd2.ui.dashboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Die Restzeit bis der Oel-Balken gruen wird — der eine Wert, den das Dashboard und die Ambient-Statuszeile teilen (Nutzerwunsch 2026-10-15).
 * Der Timer lebt im `DashboardViewModel` (`OilBarTracker`); die Statuszeile sitzt in der app-weiten Navigation ausserhalb des Dashboard-Ziels
 * und liest ihn ueber `DriveStateViewModel`. Ein Singleton ist hier unvermeidbar: zwei ViewModels verschiedener Navigationsziele brauchen
 * denselben Zustand, und die Fahrt-Erkennung (`ObdRepository.isMovingFlow`) gehoert in die Verbindungsschicht, die Restzeit nicht.
 *
 * Der Erzeuger ist allein das Dashboard ([set], je Zyklus und GPS-Zwischenupdate); die Statuszeile liest nur. Startwert und Wert ohne laufenden
 * Timer: die volle Dauer [OIL_BAR_GREEN_HOLD_MS] ("Starttimerzeit"). Der Wert ist eine Anzeige, kein Zustand, der ein Neustarten der App ueberlebt.
 */
@Singleton
class OilWarmupState @Inject constructor() {

    private val _greenInMillis = MutableStateFlow(OIL_BAR_GREEN_HOLD_MS)

    /** Restzeit bis Gruen in Millisekunden, immer zwischen 0 und [OIL_BAR_GREEN_HOLD_MS]. */
    val greenInMillis: StateFlow<Long> = _greenInMillis.asStateFlow()

    /** Veroeffentlicht die Restzeit; ein Wert ausserhalb von 0 bis [OIL_BAR_GREEN_HOLD_MS] wird auf die Grenze gesetzt. */
    fun set(remainingMillis: Long) {
        _greenInMillis.value = remainingMillis.coerceIn(0L, OIL_BAR_GREEN_HOLD_MS)
    }
}
