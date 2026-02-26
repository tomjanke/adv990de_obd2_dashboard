package de.adv990de.obd2.bluetooth

import android.content.Context

class ConnectionFactory(private val context: Context) {

    fun create(device: ScannedDevice): ObdConnection = when (device.type) {
        BtType.CLASSIC   -> ClassicBtConnection(requireNotNull(device.device) {
            "CLASSIC device has null BluetoothDevice"
        })
        BtType.BLE       -> BleConnection(context, requireNotNull(device.device) {
            "BLE device has null BluetoothDevice"
        })
        BtType.SIMULATOR -> SimulatorObdConnection()
    }
}
