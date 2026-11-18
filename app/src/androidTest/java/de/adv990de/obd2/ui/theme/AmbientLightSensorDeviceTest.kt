package de.adv990de.obd2.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Der echte Lichtsensor des Testgeraets (Pixel 8 Pro): er ist da, und die Anmeldung liefert einen Messwert in Lux — ein einziger, nicht der Wert einer bestimmten
 * Helligkeit (der Raum ist unbekannt). Ein Geraet ohne Lichtsensor wuerde hier scheitern; das Testgeraet hat einen.
 */
@RunWith(AndroidJUnit4::class)
class AmbientLightSensorDeviceTest {

    private val sensor = AmbientLightSensor(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun theDeviceHasALightSensor() {
        assertTrue(sensor.isAvailable)
    }

    @Test
    fun registeringDeliversAMeasurementInLux() = runBlocking {
        val lux = withTimeout(10_000) { sensor.luxUpdates().first() }
        assertTrue("lux was $lux", lux.isFinite() && lux >= 0f)
    }

    @Test
    fun theSensorIsUnregisteredWhenCollectingStops_aSecondRegistrationStillDelivers() = runBlocking {
        withTimeout(10_000) { sensor.luxUpdates().first() }
        val second = withTimeout(10_000) { sensor.luxUpdates().first() }
        assertTrue(second.isFinite() && second >= 0f)
    }
}
