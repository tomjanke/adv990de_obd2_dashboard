package de.adv990de.obd2.bluetooth

import android.annotation.SuppressLint
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

/**
 * Enables CCCD notifications via the SDK-appropriate `writeDescriptor` overload. The 2-arg
 * overload that returns a status code exists only from API 33 (TIRAMISU) onward; below that,
 * only the deprecated set-value-then-write pattern is available. Extracted with an explicit
 * `sdkInt` parameter (default: the real `Build.VERSION.SDK_INT`) so the real call site in
 * `BleConnection.connect()` is unchanged while `BleConnectionTest` can drive both branches
 * directly — same convention as `BluetoothPermissions`/`LocationPermissions`/
 * `ObdForegroundService.resolveForegroundStartMode()`.
 */
// NewApi: the 2-arg writeDescriptor(descriptor, value) overload requires API 33+. Lint's
// version-guard check only recognizes a direct `Build.VERSION.SDK_INT >= ...` comparison at the
// call site — it doesn't see through the `sdkInt` parameter, even though the guard is exactly as
// real (every production call site below uses the default, i.e. the real Build.VERSION.SDK_INT;
// only tests override it). Same limitation/suppression as
// BluetoothPermissions.kt/LocationPermissions.kt/ObdForegroundService.startForegroundTyped().
// MissingPermission: both writeDescriptor overloads require BLUETOOTH_CONNECT and can throw
// SecurityException at runtime. Before this extraction, the calls sat directly inside
// onServicesDiscovered's try/catch(SecurityException), which Lint recognizes as "explicitly
// handled". Now that they live in this separate function, Lint's intraprocedural check no longer
// sees the handler — but it is still there: onServicesDiscovered (BleConnection.kt) wraps its
// call to writeCccdDescriptor() in the same try/catch(SecurityException) it always had, so the
// real behavior — completeExceptionally(PermissionDenied) on revoke — is unchanged.
@SuppressLint("NewApi", "MissingPermission")
internal fun writeCccdDescriptor(
    gatt: BluetoothGatt,
    cccd: BluetoothGattDescriptor,
    sdkInt: Int = Build.VERSION.SDK_INT
) {
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    } else {
        @Suppress("DEPRECATION")
        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        @Suppress("DEPRECATION")
        gatt.writeDescriptor(cccd)
    }
}

/**
 * Sends command bytes via the SDK-appropriate `writeCharacteristic` overload. The 3-arg overload
 * that takes the payload and write type directly exists only from API 33 (TIRAMISU) onward;
 * below that, only the deprecated set-value-then-write pattern is available. Same `sdkInt`
 * extraction/rationale as [writeCccdDescriptor].
 */
// NewApi/MissingPermission: see writeCccdDescriptor above — same Lint limitations, same
// suppressions. Here the real try/catch(SecurityException) that still guards this function's
// call site lives in the OutputStream.write(ByteArray) override (BleConnection.kt), which wraps
// its call to writeCommandCharacteristic() and rethrows as IOException on revoke.
@SuppressLint("NewApi", "MissingPermission")
internal fun writeCommandCharacteristic(
    gatt: BluetoothGatt,
    writeChar: BluetoothGattCharacteristic,
    bytes: ByteArray,
    sdkInt: Int = Build.VERSION.SDK_INT
) {
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(writeChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    } else {
        @Suppress("DEPRECATION")
        writeChar.value = bytes
        @Suppress("DEPRECATION")
        gatt.writeCharacteristic(writeChar)
    }
}

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

                    writeCccdDescriptor(g, cccd)
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
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
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
                    writeCommandCharacteristic(currentGatt, writeChar, b)
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
