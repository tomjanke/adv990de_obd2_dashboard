package de.adv990de.obd2.weather

/** Naht zwischen der Wetter-Logik und dem konkreten HTTP-Client/Anbieter — austauschbar und
 *  ohne echtes Netzwerk testbar, analog [de.adv990de.obd2.location.LocationSpeedProvider]. */
interface WeatherApi {
    /** Aktuelle Aussentemperatur in Grad Celsius am angegebenen Standort, oder null bei
     *  jedem Fehler (kein Netz, Server-Fehler, unerwartetes Antwortformat). */
    suspend fun currentTemperatureC(latitude: Double, longitude: Double): Float?
}
