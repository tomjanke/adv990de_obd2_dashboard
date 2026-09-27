package de.adv990de.obd2.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Die einzige Stelle der App, die fuer die Wetterabfrage den [LocationManager] kennt. Trifft
 * bewusst keine Entscheidung (kein Filtern, kein Umrechnen) — reiner Framework-Zugriff, analog
 * [AndroidLocationSpeedProvider].
 *
 * Fuer Wetter genuegt der ungefaehre Standort (Open-Meteo rechnet ohnehin auf ein Raster von rund
 * einem Kilometer, s. `OpenMeteoApi`), deshalb reicht FINE ODER COARSE — anders als die
 * GPS-Geschwindigkeit, die einen genauen Fix braucht.
 */
@Singleton
class AndroidCurrentLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : CurrentLocationProvider {

    override fun hasPermission(): Boolean =
        LocationPermissions.hasFineLocation(context) || LocationPermissions.hasCoarseLocation(context)

    /**
     * Zuerst der GPS-Provider (genauester Fix), dann der Netzwerk-Provider: der liefert mit nur
     * COARSE-Berechtigung und hat oft noch einen brauchbaren gecachten Fix, wenn GPS keinen hat
     * (z. B. frisch gestartet, in Gebaeuden). Jeder Provider in eigenem try: auf API 26-30 wirft der
     * GPS-Provider bei nur COARSE eine [SecurityException] — die darf den Netzwerk-Versuch nicht
     * verhindern.
     */
    override fun lastKnownLocation(): Pair<Double, Double>? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val location = lastKnownFrom(manager, LocationManager.GPS_PROVIDER)
            ?: lastKnownFrom(manager, LocationManager.NETWORK_PROVIDER)
        return location?.let { it.latitude to it.longitude }
    }

    private fun lastKnownFrom(manager: LocationManager, provider: String): Location? =
        try {
            manager.getLastKnownLocation(provider)
        } catch (_: SecurityException) {
            // Berechtigung zwischen Pruefung und Aufruf entzogen, oder (API 26-30) nur COARSE
            // erteilt und dieser Provider verlangt FINE.
            null
        }
}
