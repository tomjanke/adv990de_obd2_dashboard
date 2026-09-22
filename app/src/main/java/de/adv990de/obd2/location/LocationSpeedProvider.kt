package de.adv990de.obd2.location

/**
 * Die Naht zwischen der Geschwindigkeits-Logik und dem Ortungs-Framework des Geraets.
 *
 * Warum es diese Schnittstelle gibt: [GpsSpeedSource] war die einzige Stelle des GPS-Pfads ohne
 * Test, weil `LocationManager` und `LocationListener` im JVM-Test nicht existieren. Dieselbe
 * Trennung leistet `ObdConnection` fuer Bluetooth — die Logik haengt an einer Schnittstelle, die
 * Hardware an genau einer Implementierung, die selbst keine Entscheidung trifft.
 *
 * Bewusst in Metern pro Sekunde: Das ist die Einheit, die das Framework liefert. Die Umrechnung
 * gehoert zur Logik und damit in die getestete Klasse, nicht in die untestbare.
 */
interface LocationSpeedProvider {

    /** true, solange die App den Standort lesen darf. */
    fun hasPermission(): Boolean

    /**
     * Meldet Geschwindigkeiten in m/s an [onSpeed] — null, wenn ein Fix keine traegt oder der
     * Provider abgeschaltet wurde.
     *
     * @return eine Funktion zum Abmelden, oder null wenn die Registrierung scheiterte (kein
     *   Ortungsdienst erreichbar, Berechtigung zwischen Pruefung und Registrierung entzogen).
     */
    fun startUpdates(onSpeed: (Float?) -> Unit): (() -> Unit)?
}
