package de.adv990de.obd2.ui.dashboard

/**
 * Was das Dashboard gerade zeigt. Eine Stufe statt verstreuter Einzelabfragen, damit Kacheln,
 * Drehzahlbalken und Band nie widerspruechliche Zustaende zeigen.
 */
enum class DashboardPhase {
    /** Kein Link zum Adapter: Vorschau-Belegung, Band "Vorschau · nicht verbunden" (ggf. mit Grund). */
    Offline,

    /** Der Link steht, aber es kam noch kein Messwert (Sitzung startet: FIN, Fehlercodes, erster Zyklus). */
    Waiting,

    /** Es fliessen Werte. Auch wenn keiner zu einem Slot gehoert: Das ist "live", nicht "warte". */
    Live,

    /** Der Link steht, das Steuergeraet schweigt seit [OBD_STALE_AFTER_MS] (z. B. Zuendung aus). */
    Stale,
}

/**
 * Bestimmt die [DashboardPhase]. Reihenfolge: Ohne Link gilt immer [DashboardPhase.Offline] (ein Rest-Flag
 * einer abgerissenen Verbindung darf nicht auftauchen); "veraltet" schlaegt "warte" (ein Steuergeraet, das
 * von Anfang an schweigt, endet bei "Keine aktuellen Werte" statt bei ewigem "warte").
 *
 * Der Vollbild-Spinner, den es bis 1.24.3 gab (verbunden und keine Werte fuer die Slots), ist damit
 * abgeloest: Er verdeckte die Kacheln, und waren alle Slots mit PIDs belegt, die das Fahrzeug nicht liefert,
 * blieb er fuer immer stehen — ohne Weg zum Slot-Picker (B23).
 */
fun dashboardPhase(isConnected: Boolean, awaitingFirstValues: Boolean, isStale: Boolean): DashboardPhase = when {
    !isConnected -> DashboardPhase.Offline
    isStale -> DashboardPhase.Stale
    awaitingFirstValues -> DashboardPhase.Waiting
    else -> DashboardPhase.Live
}
