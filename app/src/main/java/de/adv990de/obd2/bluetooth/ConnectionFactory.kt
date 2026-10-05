package de.adv990de.obd2.bluetooth

/**
 * Baut die Verbindung zu einem Geraet. Es gibt zwei Arten: den gekoppelten Classic-Adapter (RFCOMM/SPP) und, nur im
 * Debug-Build, den ECU-Simulator. BLE gibt es seit 1.24.9 nicht mehr (die Geraeteliste hat nie BLE-Adapter angeboten:
 * der Scan wurde nirgends aufgerufen).
 */
class ConnectionFactory {

    fun create(device: ScannedDevice): ObdConnection = when (device.type) {
        BtType.CLASSIC -> {
            val btDevice = requireNotNull(device.device) { "CLASSIC device has null BluetoothDevice" }
            ClassicBtConnection(btDevice)
        }
        BtType.SIMULATOR -> SimulatorObdConnection()
    }
}
