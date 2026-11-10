package de.adv990de.obd2.location

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Mirrors `bluetooth/BluetoothPermissions.kt` for the location permissions GPS speed and the
 *  weather lookup need (GPS speed: fine; weather: fine OR coarse). */
object LocationPermissions {

    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Genuegt fuer die Wetterabfrage (s. `AndroidCurrentLocationProvider`) — anders als die
     *  GPS-Geschwindigkeit, die einen genauen Fix braucht. */
    fun hasCoarseLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Genau ODER ungefaehr: das ist, was der Dienst-Typ `location` zum Start braucht (der Hintergrund-Standort gehoert nicht dazu). */
    fun hasAnyLocation(context: Context): Boolean = hasFineLocation(context) || hasCoarseLocation(context)
}
