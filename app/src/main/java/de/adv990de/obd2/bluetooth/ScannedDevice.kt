package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothDevice

enum class BtType { CLASSIC, BLE }

data class ScannedDevice(
    val device: BluetoothDevice,
    val type: BtType,
    val name: String
)
