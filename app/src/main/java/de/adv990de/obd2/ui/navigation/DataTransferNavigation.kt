package de.adv990de.obd2.ui.navigation

import androidx.navigation.NavController

/**
 * Oeffnet die Seite „Daten sichern und wiederherstellen“ (Zeile in den Einstellungen und die Export- und Import-Symbole der Listen) ueber **eine** Stelle. `launchSingleTop`
 * sorgt dafuer, dass ein zweiter Tipp, solange die Seite schon oben liegt (die Ueberblendung dauert 700 ms und die Liste bleibt dabei beruehrbar), keinen zweiten Eintrag
 * anlegt — jeder haette sein eigenes ViewModel und seinen eigenen Dateidialog, und „Zurueck“ braeuchte zwei Schritte.
 */
internal fun NavController.openDataTransfer(route: String) {
    navigate(route) { launchSingleTop = true }
}
