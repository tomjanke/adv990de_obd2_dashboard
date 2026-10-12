package de.adv990de.obd2.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.annotation.RequiresPermission
import de.adv990de.obd2.BuildConfig
import de.adv990de.obd2.R

class BluetoothDeviceScanner(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter
) {

    /** Returns already-bonded (paired) Classic BT devices that look like OBD2 adapters, or all bonded devices if [showAll] is true.
     *  In debug builds a virtual "OBD Sim" entry is prepended so the simulator is always reachable. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun bondedDevices(showAll: Boolean = false): List<ScannedDevice> {
        if (!BluetoothPermissions.hasConnect(context)) {
            throw BluetoothException.PermissionDenied("BLUETOOTH_CONNECT")
        }
        val bonded = try {
            bluetoothAdapter.bondedDevices
        } catch (_: SecurityException) {
            throw BluetoothException.PermissionDenied("BLUETOOTH_CONNECT")
        }
        val real = bonded
            .filter { showAll || it.isObdLike() }
            .map { ScannedDevice(it, BtType.CLASSIC, it.safeName() ?: context.getString(R.string.bluetooth_device_unnamed)) }
        return if (BuildConfig.DEBUG) {
            listOf(ScannedDevice(device = null, type = BtType.SIMULATOR, name = "OBD Sim")) + real
        } else {
            real
        }
    }

    /** Der Gerätename erfordert BLUETOOTH_CONNECT. Wurde die Berechtigung entzogen, wirft der
     *  Zugriff eine SecurityException statt null zu liefern — dann gilt das Gerät als unbekannt. */
    private fun BluetoothDevice.safeName(): String? =
        try { name } catch (_: SecurityException) { null }

    private fun BluetoothDevice.isObdLike(): Boolean {
        val n = safeName()?.uppercase() ?: return false
        return n.contains("OBD") || n.contains("ELM") || n.contains("VIECAR")
                || n.contains("OBDLINK") || n.contains("VEEPEAK") || n.contains("CARISTA")
                || n.contains("IOBD") || n.contains("SCAN") || n.contains("CAR TOOL")
    }
}
