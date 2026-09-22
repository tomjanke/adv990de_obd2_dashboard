package de.adv990de.obd2.location

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Geschwindigkeit aus dem GPS-Empfaenger des Geraets, in km/h.
 *
 * Hintergrund: Viele Fahrzeuge — Motorraeder besonders haeufig — liefern PID 010D gar nicht, weil
 * die Geschwindigkeit dort vom ABS-Steuergeraet oder dem Kombiinstrument kommt und nicht auf dem
 * OBD2-Bus liegt. Das GPS-Signal schliesst diese Luecke.
 *
 * Der Flow liefert `null`, solange keine Berechtigung erteilt ist, kein Fix vorliegt oder der Fix
 * keine Geschwindigkeit enthaelt. Er fragt nichts ab, solange ihn niemand sammelt.
 *
 * Seit 1.13.0 haengt die Klasse an [LocationSpeedProvider] statt direkt am `LocationManager` —
 * damit ist sie im JVM-Test pruefbar. Was hier steht, ist genau das, was zu pruefen war:
 * Berechtigung, fehlgeschlagene Registrierung, Umrechnung, unplausible Werte und das Abmelden.
 */
@Singleton
class GpsSpeedSource @Inject constructor(
    private val provider: LocationSpeedProvider,
) {

    val speedKmh: Flow<Float?> = callbackFlow {
        if (!provider.hasPermission()) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }

        val stopUpdates = provider.startUpdates { metersPerSecond ->
            trySend(metersPerSecond.toKmh())
        }
        if (stopUpdates == null) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }

        awaitClose { stopUpdates() }
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

    private companion object {
        const val MS_TO_KMH = 3.6f
    }
}
