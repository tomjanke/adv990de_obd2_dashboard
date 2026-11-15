package de.adv990de.obd2.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController

/**
 * Oeffnet eine Unterseite (Zeile der Einstellungen, Eintrag oder Auswertung des Logbuchs, Freeze-Frame, Lizenzen) ueber **eine** Stelle. Die aufrufende Seite bleibt waehrend
 * der Ueberblendung (700 ms) beruehrbar: ohne `launchSingleTop` legte ein zweiter Tipp die Unterseite ein zweites Mal an — "Zurueck" brauchte zwei Schritte, und unter einem
 * gespeicherten Logbuch-Eintrag laege ein zweites, leeres Formular. (Die Sicherungsseite hat mit [openDataTransfer] dieselbe Regel.)
 */
internal fun NavController.openSubPage(route: String) {
    navigate(route) { launchSingleTop = true }
}

/**
 * Schliesst die Seite [page] — aber nur, solange sie noch oben auf dem Stapel liegt. Der Zurueck-Pfeil bleibt waehrend der Ueberblendung zur vorigen Seite beruehrbar; ein
 * zweiter Tipp (oder ein Speichern, das kurz nach dem Pfeil fertig wird) ginge mit einem nackten `popBackStack()` eine weitere Ebene zurueck: von einer Unterseite der
 * Einstellungen aufs Dashboard. [page] ist der Eintrag, in dem die Seite komponiert ist (`LocalLifecycleOwner` im `NavHost`); ausserhalb eines `NavHost` (Tests, Vorschauen)
 * gibt es keinen Eintrag, dann gilt das alte Verhalten.
 *
 * @return ob eine Seite geschlossen wurde.
 */
internal fun NavController.closePage(page: Any?): Boolean {
    val entry = page as? NavBackStackEntry ?: return popBackStack()
    if (currentBackStackEntry?.id != entry.id) return false
    return popBackStack()
}

/** [closePage] fuer die Seite, in der dieser Aufruf komponiert ist: fuer den Zurueck-Pfeil und fuer Effekte, die die Seite nach getaner Arbeit verlassen. */
@Composable
internal fun rememberClosePage(navController: NavController): () -> Unit {
    val page = LocalLifecycleOwner.current
    return remember(navController, page) { { navController.closePage(page) } }
}
