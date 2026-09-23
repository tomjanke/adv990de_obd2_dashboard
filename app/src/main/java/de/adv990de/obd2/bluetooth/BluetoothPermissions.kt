package de.adv990de.obd2.bluetooth

import android.Manifest
import android.annotation.SuppressLint
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
// InlinedApi: BLUETOOTH_CONNECT und BLUETOOTH_SCAN sind reine String-Konstanten, die der
// Compiler einbettet. Das ist hier gewollt — der SDK_INT-Guard in hasRuntimePermission
// verhindert, dass unterhalb von API 31 ueberhaupt danach gefragt wird. Lint's InlinedApi-Guard
// erkennt nur einen direkten `Build.VERSION.SDK_INT >= ...`-Vergleich — es sieht den Guard nicht
// durch den `sdkInt`-Parameter hindurch, obwohl alle echten Call-Sites unten den Default (das
// echte Build.VERSION.SDK_INT) nutzen und nur Tests ihn ueberschreiben (gleiches Pattern wie
// PermissionGate.requiredPermissions()).
@SuppressLint("InlinedApi")
object BluetoothPermissions {

    fun hasConnect(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        hasRuntimePermission(context, Manifest.permission.BLUETOOTH_CONNECT, sdkInt)

    fun hasScan(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        hasRuntimePermission(context, Manifest.permission.BLUETOOTH_SCAN, sdkInt)

    private fun hasRuntimePermission(
        context: Context,
        permission: String,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean =
        sdkInt < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
