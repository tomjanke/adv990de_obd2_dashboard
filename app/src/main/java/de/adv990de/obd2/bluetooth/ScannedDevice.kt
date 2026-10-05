package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothDevice

/** Art der Verbindung. Ein gespeichertes Geraet mit dem frueheren Typ "BLE" wird als unbekannt gelesen (s. `AppPreferences.lastDevice`). */
enum class BtType { CLASSIC, SIMULATOR }

data class ScannedDevice(
    val device: BluetoothDevice?,
    val type: BtType,
    val name: String
) {
    val address: String get() = device?.address ?: "SIMULATOR"
}
