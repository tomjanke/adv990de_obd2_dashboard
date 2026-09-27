package de.adv990de.obd2.location

/**
 * Die Naht zwischen "wo bin ich gerade" und dem Ortungs-Framework des Geraets — fuer die
 * Wetterabfrage (Aussentemperatur), nicht fuer die laufende Geschwindigkeit (dafuer bleibt
 * [LocationSpeedProvider] zustaendig). Getrennt, weil beide unterschiedliche Update-Frequenzen
 * brauchen: Geschwindigkeit kontinuierlich, Standort fuer Wetter hoechstens 1x/Minute.
 */
interface CurrentLocationProvider {

    /** true, solange die App den Standort lesen darf — genau (FINE) ODER ungefaehr (COARSE); fuer
     *  eine Wetterabfrage genuegt der ungefaehre Standort. */
    fun hasPermission(): Boolean

    /** Letzter bekannter Standort als (Breitengrad, Laengengrad), oder null wenn keiner
     *  vorliegt oder die Berechtigung fehlt. */
    fun lastKnownLocation(): Pair<Double, Double>?
}
