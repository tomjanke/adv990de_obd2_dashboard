package de.adv990de.obd2.bluetooth

import android.content.Context

class ConnectionFactory(private val context: Context) {

    fun create(device: ScannedDevice): ObdConnection = when (device.type) {
        BtType.CLASSIC -> ClassicBtConnection(device.device)
        BtType.BLE -> BleConnection(context, device.device)
    }
}
