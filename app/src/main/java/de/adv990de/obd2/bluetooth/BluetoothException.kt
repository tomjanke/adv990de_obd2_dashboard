package de.adv990de.obd2.bluetooth

sealed class BluetoothException(message: String) : Exception(message) {
    class DeviceNotFound(name: String) : BluetoothException("Device not found: $name")
    class ConnectionFailed(cause: String) : BluetoothException("Connection failed: $cause")
    class ConnectionLost : BluetoothException("Bluetooth connection lost")
}
