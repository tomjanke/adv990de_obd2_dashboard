package de.adv990de.obd2.ui.theme

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Der Lichtsensor (`Sensor.TYPE_LIGHT`) des Geraets, ohne Entscheidung: er liefert die Messwerte in Lux, die Stufe bildet [ambientBrightness]. Der Sensor braucht
 * keine Berechtigung und meldet nur bei Aenderung; er ist nur angemeldet, solange [luxUpdates] gesammelt wird.
 */
@Singleton
class AmbientLightSensor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager: SensorManager? get() = context.getSystemService(SensorManager::class.java)

    /** Ob das Geraet einen Lichtsensor hat; ohne ihn bleibt [luxUpdates] stumm und die Anzeige bei voller Helligkeit. */
    val isAvailable: Boolean get() = manager?.getDefaultSensor(Sensor.TYPE_LIGHT) != null

    /** Die Messwerte in Lux. Kalter Fluss: die Anmeldung beginnt beim Sammeln und endet mit ihm. */
    fun luxUpdates(): Flow<Float> = callbackFlow {
        val sensorManager = manager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (sensorManager == null || sensor == null) {
            awaitClose { }
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty()) trySend(event.values[0])
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { if (registered) sensorManager.unregisterListener(listener) }
    }.conflate()
}
