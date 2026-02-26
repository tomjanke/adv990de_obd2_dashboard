package de.adv990de.obd2.bluetooth

import android.content.Context

class ConnectionFactory(private val context: Context) {

    fun create(device: ScannedDevice): ObdConnection = when (device.type) {
        BtType.CLASSIC -> {
            val btDevice = requireNotNull(device.device) { "CLASSIC device has null BluetoothDevice" }
            ClassicBtConnection(btDevice)
        }
        BtType.BLE -> {
            val btDevice = requireNotNull(device.device) { "BLE device has null BluetoothDevice" }
            BleConnection(context, btDevice)
        }
        BtType.SIMULATOR -> SimulatorObdConnection()
    }
}
