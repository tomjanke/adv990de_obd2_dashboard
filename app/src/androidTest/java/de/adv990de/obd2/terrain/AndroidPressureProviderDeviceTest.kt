package de.adv990de.obd2.terrain

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Der echte Drucksensor des Geraets ([AndroidPressureProvider]): die JVM-Tests pruefen die Anmeldung gegen Attrappen, hier steht, dass am Pixel wirklich ein
 * plausibler Luftdruck ankommt (Meereshoehe rund 1013 hPa, hoechster Berg rund 340 hPa — ein Wert zwischen 800 und 1100 hPa deckt jeden Ort ab, an dem
 * die Suite laufen kann) und dass sich der Sensor wieder abmelden laesst. Auf einem Geraet ohne Drucksensor ist die Anmeldung `null` — dann scheitert der Test mit Absicht.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPressureProviderDeviceTest {

    @Test
    fun theRealBarometerDeliversAPlausiblePressure() {
        val provider = AndroidPressureProvider(ApplicationProvider.getApplicationContext())
        val arrived = CountDownLatch(1)
        val hpa = AtomicReference(Float.NaN)

        val stop = provider.startUpdates { value -> hpa.set(value); arrived.countDown() }

        assertNotNull("kein Drucksensor oder Anmeldung gescheitert", stop)
        try {
            assertTrue("kein Druckwert innerhalb von 5 s", arrived.await(5, TimeUnit.SECONDS))
            assertTrue("Druck ${hpa.get()} hPa ist nicht plausibel", hpa.get() in 800f..1100f)
        } finally {
            stop!!()
        }
    }
}
