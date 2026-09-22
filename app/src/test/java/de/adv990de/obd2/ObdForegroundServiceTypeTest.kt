package de.adv990de.obd2

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ObdForegroundServiceTypeTest {

    @Test
    fun `without location the type is connected-device only`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            ObdForegroundService.resolveForegroundServiceType(includeLocationType = false)
        )
    }

    @Test
    fun `with location the type combines both flags`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            ObdForegroundService.resolveForegroundServiceType(includeLocationType = true)
        )
    }
}
