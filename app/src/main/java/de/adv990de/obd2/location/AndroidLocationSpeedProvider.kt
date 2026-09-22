package de.adv990de.obd2.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Die einzige Stelle der App, die den [LocationManager] kennt.
 *
 * Sie trifft bewusst keine Entscheidung: kein Umrechnen, kein Filtern, kein Zustand. Was hier
 * steht, laesst sich im JVM-Test nicht pruefen — deshalb steht hier auch nichts, was zu pruefen
 * waere. Die Logik liegt in [GpsSpeedSource].
 *
 * Bewusst ueber den Framework-Manager statt ueber die Play-Services: Das Projekt hat keine
 * Google-Play-Abhaengigkeit, und fuer eine reine Geschwindigkeit reicht der GPS-Provider.
 */
@Singleton
class AndroidLocationSpeedProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationSpeedProvider {

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun startUpdates(onSpeed: (Float?) -> Unit): (() -> Unit)? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null

        // Kein SAM-Lambda: vor API 30 sind die uebrigen Methoden des Interface abstrakt, ein
        // Lambda wuerde auf solchen Geraeten mit AbstractMethodError abstuerzen.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onSpeed(if (location.hasSpeed()) location.speed else null)
            }

            @Deprecated("Vor API 30 abstrakt und deshalb pflichtig zu implementieren.")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = onSpeed(null)
        }

        return try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper(),
            )
            ({ manager.removeUpdates(listener) })
        } catch (_: SecurityException) {
            // Berechtigung zwischen Pruefung und Registrierung entzogen.
            null
        }
    }

    private companion object {
        /** 1 s: dichter als das OBD-Polling braucht es nicht, spart aber Strom gegenueber 0. */
        const val MIN_INTERVAL_MS = 1_000L
        const val MIN_DISTANCE_M = 0f
    }
}
