package de.adv990de.obd2.terrain

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Der Drucksensor (`Sensor.TYPE_PRESSURE`) des Geraets. Wie `AndroidLocationSpeedProvider` ohne Entscheidung: kein Filtern, kein Mitteln, kein Zustand — das Mitteln
 * zwischen zwei Fixes macht [PressureAverager]. `SENSOR_DELAY_NORMAL` (rund 5 Hz) genuegt: die Kachel rechnet im Sekundentakt. Der Sensor braucht keine
 * Berechtigung; er liefert nur, solange die Anzeige im Vordergrund steht (die Anmeldung haengt am Sammler des Dashboards).
 */
@Singleton
class AndroidPressureProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : PressureProvider {

    override fun startUpdates(onHpa: (Float) -> Unit): (() -> Unit)? {
        val manager = context.getSystemService(SensorManager::class.java) ?: return null
        val sensor = manager.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty()) onHpa(event.values[0])
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) return null
        return { manager.unregisterListener(listener) }
    }
}
