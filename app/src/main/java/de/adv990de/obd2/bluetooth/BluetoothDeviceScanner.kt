package de.adv990de.obd2.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class BluetoothDeviceScanner(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter
) {

    /** Returns already-bonded (paired) Classic BT devices that look like OBD2 adapters */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun bondedDevices(): List<ScannedDevice> =
        bluetoothAdapter.bondedDevices
            .filter { it.isObdLike() }
            .map { ScannedDevice(it, BtType.CLASSIC, it.name ?: "Unknown") }

    /** Scans for BLE OBD2 adapters */
    fun scanBle(): Flow<ScannedDevice> = callbackFlow {
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            close(BluetoothException.ConnectionFailed("Bluetooth is off or BLE not supported"))
            return@callbackFlow
        }
        val seen = mutableSetOf<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (device.isObdLike() && seen.add(device.address)) {
                    trySend(ScannedDevice(device, BtType.BLE, device.name ?: "BLE OBD2"))
                }
            }
        }
        scanner.startScan(callback)
        awaitClose { scanner.stopScan(callback) }
    }

    private fun BluetoothDevice.isObdLike(): Boolean {
        val n = name?.uppercase() ?: return false
        return n.contains("OBD") || n.contains("ELM") || n.contains("VIECAR")
                || n.contains("OBDLINK") || n.contains("VEEPEAK")
    }
}
