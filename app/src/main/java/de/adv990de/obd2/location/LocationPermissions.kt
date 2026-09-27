package de.adv990de.obd2.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Mirrors `bluetooth/BluetoothPermissions.kt` for the location permissions GPS speed and the
 *  weather lookup need (GPS speed: fine; weather: fine OR coarse). */
@SuppressLint("InlinedApi")
object LocationPermissions {

    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Genuegt fuer die Wetterabfrage (s. `AndroidCurrentLocationProvider`) — anders als die
     *  GPS-Geschwindigkeit, die einen genauen Fix braucht. */
    fun hasCoarseLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // InlinedApi: ACCESS_BACKGROUND_LOCATION ist eine reine String-Konstante, die der Compiler
    // einbettet. Lint's InlinedApi-Guard erkennt nur einen direkten
    // `Build.VERSION.SDK_INT >= ...`-Vergleich — es sieht den Guard nicht durch den
    // `sdkInt`-Parameter hindurch, obwohl alle echten Call-Sites unten den Default (das echte
    // Build.VERSION.SDK_INT) nutzen und nur Tests ihn ueberschreiben (gleiches Pattern wie
    // BluetoothPermissions.kt / PermissionGate.kt).
    fun hasBackgroundLocation(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** ACCESS_BACKGROUND_LOCATION doesn't exist as a concept before API 29 — nothing to request. */
    fun needsBackgroundLocationRequest(sdkInt: Int, backgroundLocationGranted: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.Q && !backgroundLocationGranted
}
