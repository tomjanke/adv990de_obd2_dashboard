package de.adv990de.obd2.terrain

/**
 * Die Naht zwischen der Gelaende-Rechnung und dem Drucksensor des Geraets (dieselbe Trennung wie `LocationSpeedProvider`: die Logik haengt an einer
 * Schnittstelle, die Hardware an einer Implementierung ohne Entscheidung).
 */
interface PressureProvider {

    /**
     * Meldet den Luftdruck in hPa an [onHpa], solange angemeldet.
     *
     * @return eine Funktion zum Abmelden, oder null wenn es keinen Drucksensor gibt oder die Anmeldung scheiterte.
     */
    fun startUpdates(onHpa: (Float) -> Unit): (() -> Unit)?
}
