package de.adv990de.obd2.weather

import de.adv990de.obd2.data.AppLogRepository
import de.adv990de.obd2.data.LogLevel
import de.adv990de.obd2.location.CurrentLocationProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Warum ein Abruf keine Temperatur geliefert hat — je Ursache ein eigener App-Log-Text, der sagt,
 * was zu tun ist. [logMessage] ist Log-Text (wie die uebrigen `AppLogRepository`-Eintraege), kein
 * UI-String.
 */
internal enum class WeatherFetchFailure(val logMessage: String) {
    NO_PERMISSION(
        "Außentemperatur: keine Standortberechtigung. Wird einmalig automatisch angefragt, sobald " +
            "die Einstellungen bei eingeschaltetem \"Navigationsleiste beim Fahren ausblenden\" " +
            "geöffnet werden; danach nur noch über die Android-App-Einstellungen erlaubbar " +
            "(ungefähr genügt)."
    ),
    NO_LOCATION_FIX(
        "Außentemperatur: noch kein Standort bekannt (weder GPS- noch Netzwerk-Standort). " +
            "Erscheint, sobald das Gerät einmal einen Standort ermittelt hat."
    ),
    API_FAILED(
        "Außentemperatur: Abruf bei Open-Meteo fehlgeschlagen (keine Internetverbindung, " +
            "Server-Fehler oder unerwartete Antwort)."
    ),
}

/**
 * Liefert die aktuelle Aussentemperatur am aktuellen Standort — oder `null` bei jedem Fehler
 * (keine Berechtigung, kein Fix, Netzwerkfehler). Wirft nie.
 *
 * Logging: jede Ursache mit eigenem Text (s. [WeatherFetchFailure]), aber nur beim WECHSEL
 * gegenueber dem vorigen Abruf — nicht in jedem 60-s-Zyklus. Ohne Netz waehrend einer ganzen
 * Fahrt stuende sonst jede Minute dieselbe Zeile im auf 1000 Eintraege begrenzten App-Log und
 * verdraengte binnen Stunden alles andere. Ein erfolgreicher Abruf beendet eine Fehlerphase: tritt
 * dieselbe Ursache danach erneut auf, wird sie wieder geloggt (neue Phase, neue Information).
 *
 * [previousFailure] ist repository-interner Zustand; gerufen wird ausschliesslich aus dem einen
 * Polling-Loop in `WeatherViewModel` (Main-Thread, nie parallel), daher ohne Synchronisation.
 */
@Singleton
class WeatherRepository @Inject constructor(
    private val locationProvider: CurrentLocationProvider,
    private val weatherApi: WeatherApi,
    private val appLogRepository: AppLogRepository,
) {
    /** Ursache des vorigen Abrufs; null nach einem Erfolg und vor dem ersten Abruf. */
    private var previousFailure: WeatherFetchFailure? = null

    suspend fun fetchCurrentTemperatureC(): Float? {
        if (!locationProvider.hasPermission()) return failed(WeatherFetchFailure.NO_PERMISSION)
        val (lat, lon) = locationProvider.lastKnownLocation()
            ?: return failed(WeatherFetchFailure.NO_LOCATION_FIX)
        val temp = weatherApi.currentTemperatureC(lat, lon) ?: return failed(WeatherFetchFailure.API_FAILED)
        previousFailure = null
        return temp
    }

    private suspend fun failed(cause: WeatherFetchFailure): Float? {
        if (cause != previousFailure) {
            appLogRepository.log(LogLevel.WARN, cause.logMessage)
        }
        previousFailure = cause
        return null
    }
}
