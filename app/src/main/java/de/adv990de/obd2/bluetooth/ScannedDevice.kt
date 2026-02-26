package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothDevice

enum class BtType { CLASSIC, BLE, SIMULATOR }

data class ScannedDevice(
    val device: BluetoothDevice?,
    val type: BtType,
    val name: String
) {
    val address: String get() = device?.address ?: "SIMULATOR"
}
