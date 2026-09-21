package de.adv990de.obd2.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Laufzeit-Prüfung der Bluetooth-Berechtigungen.
 *
 * Ab Android 12 (API 31) sind BLUETOOTH_SCAN und BLUETOOTH_CONNECT gefährliche Berechtigungen,
 * die der Nutzer jederzeit entziehen kann — auch während einer laufenden Verbindung. Darunter
 * genügen BLUETOOTH und BLUETOOTH_ADMIN, die als Install-Time-Berechtigungen nicht entzogen
 * werden können; dort liefern die Prüfungen deshalb immer true, ohne den Context zu befragen.
 *
 * Das ersetzt nicht den PermissionGate in der UI — es ist die zweite Verteidigungslinie für
 * den Fall, dass die Berechtigung nach dem Gate wegfällt.
 */
object BluetoothPermissions {

    fun hasConnect(context: Context): Boolean =
        hasRuntimePermission(context, Manifest.permission.BLUETOOTH_CONNECT)

    fun hasScan(context: Context): Boolean =
        hasRuntimePermission(context, Manifest.permission.BLUETOOTH_SCAN)

    private fun hasRuntimePermission(context: Context, permission: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
