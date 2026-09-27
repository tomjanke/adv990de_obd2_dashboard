package de.adv990de.obd2.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Die einzige Stelle der App, die fuer die Wetterabfrage den [LocationManager] kennt. Trifft
 * bewusst keine Entscheidung (kein Filtern, kein Umrechnen) — reiner Framework-Zugriff, analog
 * [AndroidLocationSpeedProvider].
 */
@Singleton
class AndroidCurrentLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : CurrentLocationProvider {

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun lastKnownLocation(): Pair<Double, Double>? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            val location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            location?.let { it.latitude to it.longitude }
        } catch (_: SecurityException) {
            null
        }
    }
}
