package de.adv990de.obd2.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Mirrors `bluetooth/BluetoothPermissions.kt` for the location permissions GPS speed needs. */
@SuppressLint("InlinedApi")
object LocationPermissions {

    fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** ACCESS_BACKGROUND_LOCATION doesn't exist as a concept before API 29 — nothing to request. */
    fun needsBackgroundLocationRequest(sdkInt: Int, backgroundLocationGranted: Boolean): Boolean =
        sdkInt >= Build.VERSION_CODES.Q && !backgroundLocationGranted
}
