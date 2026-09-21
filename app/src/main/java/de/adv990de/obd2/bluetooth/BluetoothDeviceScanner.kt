package de.adv990de.obd2.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import androidx.annotation.RequiresPermission
import de.adv990de.obd2.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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
            .map { ScannedDevice(it, BtType.CLASSIC, it.safeName() ?: "Unknown") }
        return if (BuildConfig.DEBUG) {
            listOf(ScannedDevice(device = null, type = BtType.SIMULATOR, name = "OBD Sim")) + real
        } else {
            real
        }
    }

    /** Scans for BLE OBD2 adapters */
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun scanBle(): Flow<ScannedDevice> = callbackFlow {
        if (!BluetoothPermissions.hasScan(context)) {
            close(BluetoothException.PermissionDenied("BLUETOOTH_SCAN"))
            return@callbackFlow
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            close(BluetoothException.ConnectionFailed("Bluetooth is off or BLE not supported"))
            return@callbackFlow
        }
        val seen = mutableSetOf<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (device.isObdLike() && seen.add(device.address)) {
                    trySend(ScannedDevice(device, BtType.BLE, device.safeName() ?: "BLE OBD2"))
                }
            }
        }
        try {
            scanner.startScan(callback)
        } catch (_: SecurityException) {
            close(BluetoothException.PermissionDenied("BLUETOOTH_SCAN"))
            return@callbackFlow
        }
        awaitClose {
            try { scanner.stopScan(callback) } catch (_: SecurityException) { /* Berechtigung entzogen */ }
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
