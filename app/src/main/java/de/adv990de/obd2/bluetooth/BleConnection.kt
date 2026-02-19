package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import de.adv990de.obd2.obd.ElmController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID

// Standard BLE OBD2 UUIDs (Viecar / generic ELM327 BLE adapters)
private val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
private val WRITE_CHAR_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
private val NOTIFY_CHAR_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

class BleConnection(
    private val context: Context,
    private val device: BluetoothDevice
) : ObdConnection {

    private var gatt: BluetoothGatt? = null
    private val pipedOutput = PipedOutputStream()
    private val pipedInput = PipedInputStream(pipedOutput)
    private val connectedDeferred = CompletableDeferred<Unit>()

    override val isConnected: Boolean get() = gatt != null

    override suspend fun connect(): ElmController = withContext(Dispatchers.IO) {
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else {
                    connectedDeferred.completeExceptionally(
                        BluetoothException.ConnectionFailed("state=$newState")
                    )
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service = g.getService(SERVICE_UUID)
                    ?: return connectedDeferred.completeExceptionally(
                        BluetoothException.ConnectionFailed("OBD2 service not found")
                    )

                val notifyChar = service.getCharacteristic(NOTIFY_CHAR_UUID)
                g.setCharacteristicNotification(notifyChar, true)
                val cccd = notifyChar.getDescriptor(CCCD_UUID)
                cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                g.writeDescriptor(cccd)
                gatt = g
                connectedDeferred.complete(Unit)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                char: BluetoothGattCharacteristic
            ) {
                // Write incoming BLE data into the pipe so ElmController can read it
                pipedOutput.write(char.value)
                pipedOutput.flush()
            }
        }

        gatt = device.connectGatt(context, false, callback)
        connectedDeferred.await()

        // OutputStream that writes via GATT write characteristic
        val bleOutputStream = object : java.io.OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray) {
                val service = gatt?.getService(SERVICE_UUID) ?: return
                @Suppress("DEPRECATION")
                val writeChar = service.getCharacteristic(WRITE_CHAR_UUID) ?: return
                writeChar.value = b
                gatt?.writeCharacteristic(writeChar)
            }
        }

        ElmController(pipedInput, bleOutputStream)
    }

    override fun close() {
        gatt?.close()
        gatt = null
        pipedOutput.close()
    }
}
