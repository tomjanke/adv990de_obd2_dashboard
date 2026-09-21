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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Geschwindigkeit aus dem GPS-Empfaenger des Geraets.
 *
 * Hintergrund: Viele Fahrzeuge — Motorraeder besonders haeufig — liefern PID 010D gar nicht,
 * weil die Geschwindigkeit dort vom ABS-Steuergeraet oder dem Kombiinstrument kommt und nicht
 * auf dem OBD2-Bus liegt. Das GPS-Signal schliesst diese Luecke.
 *
 * Bewusst ueber den Framework-[LocationManager] statt ueber die Play-Services: Das Projekt hat
 * bisher keine Google-Play-Abhaengigkeit, und fuer eine reine Geschwindigkeit reicht der
 * GPS-Provider.
 *
 * Der Flow liefert `null`, solange keine Berechtigung erteilt ist, kein Fix vorliegt oder der
 * Fix keine Geschwindigkeit enthaelt. Er fragt nichts ab, solange ihn niemand sammelt.
 */
@Singleton
class GpsSpeedSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    val speedKmh: Flow<Float?> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager == null || !hasLocationPermission()) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }

        // Kein SAM-Lambda: vor API 30 sind die uebrigen Methoden des Interface abstrakt, ein
        // Lambda wuerde auf solchen Geraeten mit AbstractMethodError abstuerzen.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(if (location.hasSpeed()) location.speed * MS_TO_KMH else null)
            }

            @Deprecated("Vor API 30 abstrakt und deshalb pflichtig zu implementieren.")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) {
                trySend(null)
            }
        }

        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper(),
            )
        } catch (_: SecurityException) {
            // Berechtigung zwischen Pruefung und Registrierung entzogen.
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }

        awaitClose { manager.removeUpdates(listener) }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        /** 1 s: dichter als das OBD-Polling braucht es nicht, spart aber Strom gegenueber 0. */
        const val MIN_INTERVAL_MS = 1_000L
        const val MIN_DISTANCE_M = 0f
        const val MS_TO_KMH = 3.6f
    }
}
