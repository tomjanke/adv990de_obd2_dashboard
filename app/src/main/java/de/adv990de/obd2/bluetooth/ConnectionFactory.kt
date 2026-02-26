package de.adv990de.obd2.bluetooth

import android.content.Context

class ConnectionFactory(private val context: Context) {

    fun create(device: ScannedDevice): ObdConnection {
        val btDevice = requireNotNull(device.device) {
            "ConnectionFactory.create() called with null BluetoothDevice (type=${device.type})"
        }
        return when (device.type) {
            BtType.CLASSIC -> ClassicBtConnection(btDevice)
            BtType.BLE     -> BleConnection(context, btDevice)
        }
    }
}
