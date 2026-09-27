package de.adv990de.obd2.weather

import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.location.CurrentLocationProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Liefert die aktuelle Aussentemperatur am aktuellen Standort — oder `null` bei jedem Fehler
 * (keine Berechtigung, kein Fix, Netzwerkfehler). Wirft nie, loggt einen Fehlschlag einmalig
 * pro Aufruf statt ihn unbemerkt zu verschlucken.
 */
@Singleton
class WeatherRepository @Inject constructor(
    private val locationProvider: CurrentLocationProvider,
    private val weatherApi: WeatherApi,
    private val appLogRepository: AppLogRepository,
) {
    suspend fun fetchCurrentTemperatureC(): Float? {
        if (!locationProvider.hasPermission()) return null
        val (lat, lon) = locationProvider.lastKnownLocation() ?: return null
        val temp = weatherApi.currentTemperatureC(lat, lon)
        if (temp == null) {
            appLogRepository.log(LogLevel.WARN, "Aussentemperatur konnte nicht abgerufen werden")
        }
        return temp
    }
}
