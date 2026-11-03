package de.adv990de.obd2.ui.history

import de.adv990de.obd2.tripimport.TripImportPlan
import de.adv990de.obd2.ui.UiText

/** Wo der Fahrten-Import steht: nichts ([Idle]), lesen oder schreiben ([Working]), die Vorschau wartet auf die Bestaetigung ([Preview]), fertig ([Done]) oder gescheitert ([Failed]). */
sealed interface TripImportState {
    data object Idle : TripImportState
    data object Working : TripImportState
    data class Preview(val plan: TripImportPlan) : TripImportState
    data class Done(val tripCount: Int) : TripImportState
    data class Failed(val text: UiText) : TripImportState
}
