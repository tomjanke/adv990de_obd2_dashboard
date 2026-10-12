package de.adv990de.obd2.ui.navigation

/** Was ein Tipp auf einen Reiter der Hauptmenue-Leiste ausloest — s. [bottomNavAction]. */
internal enum class BottomNavAction {
    /** Der Reiter ist schon aktiv und steht auf seiner Startseite: nichts zu tun. */
    Ignore,

    /** Der Reiter ist aktiv, der Nutzer steht aber auf einer Unterseite: zurueck zur Startseite des Reiters. */
    PopToTabRoot,

    /** Ein anderer Reiter: wechseln, den Zustand des verlassenen sichern und den des Ziels wiederherstellen. */
    SwitchTab,
}

/**
 * Entscheidet, was ein Tipp auf [tapped] bei der aktuellen Route [currentRoute] bewirkt. Reine Funktion (JVM-testbar ohne
 * NavController).
 *
 * Der Fall, der frueher fehlte: Auf einer Unterseite (App-Info, Getriebe, Freeze-Frame ...) ist ihr Reiter hervorgehoben
 * ([parentTab]), und ein erneuter Tipp darauf tat nichts — `navigate(..., restoreState = true)` stellte genau den Stapel wieder
 * her, den der Tipp gerade gesichert hatte. Ein Tipp auf den hervorgehobenen Reiter fuehrt jetzt zu dessen Startseite (das
 * uebliche Verhalten einer Navigationsleiste).
 */
internal fun bottomNavAction(currentRoute: String?, tapped: Screen): BottomNavAction = when {
    parentTab(currentRoute) != tapped -> BottomNavAction.SwitchTab
    currentRoute == tapped.route -> BottomNavAction.Ignore
    else -> BottomNavAction.PopToTabRoot
}
