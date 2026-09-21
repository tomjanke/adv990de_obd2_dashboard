package de.adv990de.obd2.bluetooth

import android.content.Context
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothPermissionsTest {

    // Bewusst kein relaxed Mock: jeder Zugriff auf den Context wuerde den Test brechen.
    // Im JVM-Unit-Test ist Build.VERSION.SDK_INT == 0, also der Pfad unterhalb Android 12,
    // auf dem BLUETOOTH/BLUETOOTH_ADMIN Install-Time-Berechtigungen sind.
    private val context = mockk<Context>()

    // --- Test 1 ---

    @Test
    fun `hasConnect is true below API 31 without querying the context`() {
        assertTrue(BluetoothPermissions.hasConnect(context))
        verify { context wasNot Called }
    }

    // --- Test 2 ---

    @Test
    fun `hasScan is true below API 31 without querying the context`() {
        assertTrue(BluetoothPermissions.hasScan(context))
        verify { context wasNot Called }
    }
}
