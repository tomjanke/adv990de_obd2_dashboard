package de.adv990de.obd2.ui.history

import de.adv990de.obd2.data.SessionStats

/**
 * Der Stand des Blocks „Messwerte“ im Detail-Dialog einer Fahrt: die Werte kommen aus der Datenbank und brauchen einen Moment ([Loading]); ein Lesefehler ist ein eigener Zustand
 * ([Failed]) — er darf nie wie „keine Werte aufgezeichnet“ aussehen, weil das eine Aussage ueber die Fahrt waere, die niemand gepruft hat.
 */
sealed interface SessionStatsState {
    data object Loading : SessionStatsState
    data class Ready(val stats: SessionStats) : SessionStatsState
    data object Failed : SessionStatsState
}
