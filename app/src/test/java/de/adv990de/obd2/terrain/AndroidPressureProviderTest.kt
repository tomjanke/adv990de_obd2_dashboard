package de.adv990de.obd2.terrain

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Die Anmeldung am Drucksensor — ohne Entscheidung, aber mit vier Wegen, auf denen sie scheitern kann. Der echte Sensor: `AndroidPressureProviderDeviceTest`. */
class AndroidPressureProviderTest {

    private val context = mockk<Context>()
    private val sensor = mockk<Sensor>()

    private fun eventWithValues(vararg values: Float): SensorEvent {
        val event = mockk<SensorEvent>()
        // `values` ist ein oeffentliches Feld: ein Mock liest es direkt, ein Stub der Methoden hilft nicht.
        SensorEvent::class.java.getField("values").set(event, values)
        return event
    }

    @Test
    fun `no sensor manager means no registration`() {
        every { context.getSystemService(SensorManager::class.java) } returns null

        assertNull(AndroidPressureProvider(context).startUpdates { })
    }

    @Test
    fun `a device without a pressure sensor means no registration`() {
        val manager = mockk<SensorManager>()
        every { context.getSystemService(SensorManager::class.java) } returns manager
        every { manager.getDefaultSensor(Sensor.TYPE_PRESSURE) } returns null

        assertNull(AndroidPressureProvider(context).startUpdates { })
    }

    @Test
    fun `a failed registration means no registration`() {
        val manager = mockk<SensorManager>()
        every { context.getSystemService(SensorManager::class.java) } returns manager
        every { manager.getDefaultSensor(Sensor.TYPE_PRESSURE) } returns sensor
        every { manager.registerListener(any<SensorEventListener>(), any<Sensor>(), any<Int>()) } returns false

        assertNull(AndroidPressureProvider(context).startUpdates { })
    }

    @Test
    fun `the first value of an event is passed on and the stop function unregisters`() {
        val manager = mockk<SensorManager>(relaxed = true)
        every { context.getSystemService(SensorManager::class.java) } returns manager
        every { manager.getDefaultSensor(Sensor.TYPE_PRESSURE) } returns sensor
        val listener = slot<SensorEventListener>()
        every { manager.registerListener(capture(listener), sensor, SensorManager.SENSOR_DELAY_NORMAL) } returns true
        val received = mutableListOf<Float>()

        val stop = AndroidPressureProvider(context).startUpdates { received += it }
        assertNotNull(stop)
        listener.captured.onSensorChanged(eventWithValues(1013.2f))
        listener.captured.onSensorChanged(eventWithValues())          // ein Ereignis ohne Wert wird uebergangen

        assertEquals(listOf(1013.2f), received)
        stop!!()
        verify { manager.unregisterListener(listener.captured) }
    }
}
