package de.adv990de.obd2.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import de.adv990de.obd2.obd.ElmController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
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

    // @Volatile ensures the GATT callback thread's write is visible to the coroutine thread
    @Volatile private var gatt: BluetoothGatt? = null
    private val pipedOutput = PipedOutputStream()
    private val pipedInput = PipedInputStream(pipedOutput)

    override val isConnected: Boolean get() = gatt != null

    override suspend fun connect(): ElmController = withContext(Dispatchers.IO) {
        // Local deferred so connect() can be called again after close() without reuse issues
        val connectedDeferred = CompletableDeferred<Unit>()

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
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    g.close()
                    connectedDeferred.completeExceptionally(
                        BluetoothException.ConnectionFailed("Service discovery failed, status=$status")
                    )
                    return
                }
                val service = g.getService(SERVICE_UUID) ?: run {
                    g.close()
                    connectedDeferred.completeExceptionally(
                        BluetoothException.ConnectionFailed("OBD2 service not found")
                    )
                    return
                }

                val notifyChar = service.getCharacteristic(NOTIFY_CHAR_UUID) ?: run {
                    g.close()
                    connectedDeferred.completeExceptionally(
                        BluetoothException.ConnectionFailed("Notify characteristic not found")
                    )
                    return
                }
                g.setCharacteristicNotification(notifyChar, true)
                val cccd = notifyChar.getDescriptor(CCCD_UUID)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }

                gatt = g
                connectedDeferred.complete(Unit)
            }

            // API 33+ signature — called on Android 13 and above
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                char: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                try {
                    pipedOutput.write(value)
                    pipedOutput.flush()
                } catch (_: IOException) { /* pipe closed on disconnect */ }
            }

            // Pre-API 33 signature — called on Android 12 and below
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                char: BluetoothGattCharacteristic
            ) {
                try {
                    pipedOutput.write(char.value)
                    pipedOutput.flush()
                } catch (_: IOException) { /* pipe closed on disconnect */ }
            }
        }

        val rawGatt = device.connectGatt(context, false, callback)
        try {
            connectedDeferred.await()
        } catch (e: Exception) {
            rawGatt.close()
            throw e
        }

        // OutputStream that writes commands to the adapter via GATT write characteristic
        val bleOutputStream = object : java.io.OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray) {
                val currentGatt = gatt ?: return
                val service = currentGatt.getService(SERVICE_UUID) ?: return
                val writeChar = service.getCharacteristic(WRITE_CHAR_UUID) ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    currentGatt.writeCharacteristic(
                        writeChar,
                        b,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                } else {
                    @Suppress("DEPRECATION")
                    writeChar.value = b
                    @Suppress("DEPRECATION")
                    currentGatt.writeCharacteristic(writeChar)
                }
            }
        }

        ElmController(pipedInput, bleOutputStream)
    }

    override fun close() {
        gatt?.close()
        gatt = null
        try { pipedOutput.close() } catch (_: IOException) {}
        try { pipedInput.close() } catch (_: IOException) {}
    }
}
