package de.adv990de.obd2.location

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Geschwindigkeit aus dem GPS-Empfaenger des Geraets, in km/h.
 *
 * Hintergrund: Viele Fahrzeuge — Motorraeder besonders haeufig — liefern PID 010D gar nicht, weil
 * die Geschwindigkeit dort vom ABS-Steuergeraet oder dem Kombiinstrument kommt und nicht auf dem
 * OBD2-Bus liegt. Das GPS-Signal schliesst diese Luecke.
 *
 * Der Flow liefert `null`, solange keine Berechtigung erteilt ist, kein Fix vorliegt, der Fix
 * keine Geschwindigkeit enthaelt, oder der letzte Fix laenger als [STALE_AFTER_MS] her ist (s.u.).
 * Er fragt nichts ab, solange ihn niemand sammelt.
 *
 * Seit 1.13.0 haengt die Klasse an [LocationSpeedProvider] statt direkt am `LocationManager` —
 * damit ist sie im JVM-Test pruefbar. Was hier steht, ist genau das, was zu pruefen war:
 * Berechtigung, fehlgeschlagene Registrierung, Umrechnung, unplausible Werte, Staleness und das
 * Abmelden.
 *
 * **Staleness:** `LocationManager.requestLocationUpdates`s Intervall ist eine Untergrenze, keine
 * Zusage — verliert das Geraet den Empfang (Tunnel, Tiefgarage), bleibt [LocationSpeedProvider]
 * einfach still, `onProviderDisabled` feuert nicht (der Anbieter ist ja nicht abgeschaltet, nur
 * ohne Empfang). Ohne Gegenmassnahme wuerde der letzte bekannte Wert unbegrenzt weiterlaufen — und
 * mit ihm ein daraus abgeleiteter Gang in der virtuellen Ganganzeige, obwohl das Fahrzeug laengst
 * steht. Jeder echte Fix startet deshalb einen Timer neu; laeuft er ab, bevor der naechste Fix
 * eintrifft, gilt die Geschwindigkeit als unbekannt.
 */
@Singleton
class GpsSpeedSource @Inject constructor(
    private val provider: LocationSpeedProvider,
) {

    val speedKmh: Flow<Float?> = callbackFlow {
        if (!provider.hasPermission()) {
            trySend(null)
            // Die Berechtigung kann spaeter erteilt werden (System-Dialog, App-Einstellungen), ohne dass jemand
            // den Schalter neu betaetigt — frueher wurde sie nur hier, beim Start des Flusses, geprueft, und
            // GPS-Speed blieb bis zum App-Neustart stumm (B10). Die Schleife endet mit dem Abbruch des Sammlers.
            while (!provider.hasPermission()) delay(PERMISSION_RECHECK_MS)
        }

        var staleJob: Job? = null
        val stopUpdates = provider.startUpdates { metersPerSecond ->
            val kmh = metersPerSecond.toKmh()
            trySend(kmh)
            staleJob?.cancel()
            staleJob = if (kmh != null) {
                launch {
                    delay(STALE_AFTER_MS)
                    trySend(null)
                }
            } else null
        }
        if (stopUpdates == null) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }

        awaitClose {
            staleJob?.cancel()
            stopUpdates()
        }
    }

    /**
     * m/s in km/h. Unplausible Werte werden zu null statt weitergereicht: Ein negativer oder
     * unendlicher Wert ist keine Geschwindigkeit, und weiter oben liesse er sich von einer echten
     * nicht mehr unterscheiden.
     */
    private fun Float?.toKmh(): Float? {
        if (this == null || isNaN() || isInfinite() || this < 0f) return null
        return this * MS_TO_KMH
    }

    internal companion object {
        const val MS_TO_KMH = 3.6f

        /** Grosszuegig ueber dem 200ms-Anfrageintervall (siehe `AndroidLocationSpeedProvider`),
         *  damit ein einzelner ausgelassener Zyklus nicht faelschlich als Signalverlust zaehlt —
         *  aber knapp genug, dass ein echter Empfangsabriss (Tunnel, Tiefgarage) innerhalb
         *  weniger Sekunden erkannt wird, statt eine veraltete Geschwindigkeit unbegrenzt lange
         *  weiterzureichen. */
        const val STALE_AFTER_MS = 2_000L

        /** So oft wird eine fehlende Berechtigung erneut geprueft; ein Aufruf von `checkSelfPermission` kostet nichts. */
        const val PERMISSION_RECHECK_MS = 2_000L
    }
}
