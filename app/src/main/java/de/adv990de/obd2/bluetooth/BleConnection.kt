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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

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
    // PipedInputStream/PipedOutputStream are single-use: once closed they cannot be
    // reconnected. Reconnection requires creating a new BleConnection instance.
    private val pipedOutput = PipedOutputStream()
    private val pipedInput = PipedInputStream(pipedOutput)
    // Serializes GATT writes: write() blocks until onCharacteristicWrite fires (or 5-second timeout).
    // Correctness relies on ElmController.commandMutex ensuring at most one write is in-flight.
    // close() releases one permit to unblock any waiting write thread; the null-guard in write()
    // prevents a subsequent spurious GATT write on the stale permit (BleConnection is single-use).
    private val writeAck = Semaphore(0)

    override val isConnected: Boolean get() = gatt != null

    override suspend fun connect(): ElmController = withContext(Dispatchers.IO) {
        // Zweite Verteidigungslinie hinter dem PermissionGate: die Berechtigung kann ab
        // Android 12 jederzeit entzogen werden, auch zwischen Gate und Verbindungsaufbau.
        if (!BluetoothPermissions.hasConnect(context)) {
            throw BluetoothException.PermissionDenied("BLUETOOTH_CONNECT")
        }

        // Local deferred so connect() can be called again after close() without reuse issues
        val connectedDeferred = CompletableDeferred<Unit>()

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                try {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices()
                    } else {
                        connectedDeferred.completeExceptionally(
                            BluetoothException.ConnectionFailed("state=$newState")
                        )
                    }
                } catch (_: SecurityException) {
                    // BLUETOOTH_CONNECT wurde zur Laufzeit entzogen.
                    connectedDeferred.completeExceptionally(
                        BluetoothException.PermissionDenied("BLUETOOTH_CONNECT")
                    )
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                try {
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
                    val cccd = notifyChar.getDescriptor(CCCD_UUID) ?: run {
                        g.close()
                        connectedDeferred.completeExceptionally(
                            BluetoothException.ConnectionFailed("CCCD descriptor not found")
                        )
                        return
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                    // gatt assignment and deferred completion happen in onDescriptorWrite
                    // to ensure notifications are active before the caller gets ElmController
                } catch (_: SecurityException) {
                    // BLUETOOTH_CONNECT wurde zur Laufzeit entzogen.
                    connectedDeferred.completeExceptionally(
                        BluetoothException.PermissionDenied("BLUETOOTH_CONNECT")
                    )
                }
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                try {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        g.close()
                        connectedDeferred.completeExceptionally(
                            BluetoothException.ConnectionFailed("CCCD write failed, status=$status")
                        )
                    } else {
                        gatt = g
                        connectedDeferred.complete(Unit)
                    }
                } catch (_: SecurityException) {
                    // BLUETOOTH_CONNECT wurde zur Laufzeit entzogen.
                    connectedDeferred.completeExceptionally(
                        BluetoothException.PermissionDenied("BLUETOOTH_CONNECT")
                    )
                }
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                // Release regardless of status: a failed write surfaces as a read timeout in
                // ElmController. Log the failure to aid debugging on clone adapters.
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    android.util.Log.w("BleConnection", "onCharacteristicWrite: status=$status")
                }
                writeAck.release()
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

        val rawGatt = try {
            device.connectGatt(context, false, callback)
        } catch (_: SecurityException) {
            throw BluetoothException.PermissionDenied("BLUETOOTH_CONNECT")
        }
        try {
            connectedDeferred.await()
        } catch (e: Exception) {
            try { rawGatt.close() } catch (_: SecurityException) { /* Berechtigung entzogen */ }
            throw e
        }

        // OutputStream that writes commands to the adapter via GATT write characteristic
        val bleOutputStream = object : java.io.OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray) {
                val currentGatt = gatt ?: return
                val service = currentGatt.getService(SERVICE_UUID) ?: return
                val writeChar = service.getCharacteristic(WRITE_CHAR_UUID) ?: return
                try {
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
                } catch (_: SecurityException) {
                    // Berechtigung mitten in der Sitzung entzogen: als IOException melden, damit
                    // ElmController und ObdRepository das wie einen Verbindungsabbruch behandeln.
                    throw IOException("Bluetooth permission revoked")
                }
                writeAck.tryAcquire(5, TimeUnit.SECONDS)
            }
        }

        ElmController(pipedInput, bleOutputStream)
    }

    override fun close() {
        writeAck.release()
        try { gatt?.close() } catch (_: SecurityException) { /* Berechtigung entzogen */ }
        gatt = null
        try { pipedOutput.close() } catch (_: IOException) {}
        try { pipedInput.close() } catch (_: IOException) {}
    }
}
