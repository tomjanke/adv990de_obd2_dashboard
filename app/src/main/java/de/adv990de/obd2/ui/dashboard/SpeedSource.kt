package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.R
import de.adv990de.obd2.obd.PidRegistry
import de.adv990de.obd2.obd.model.SensorValue
import de.adv990de.obd2.obd.model.ValueSource

/**
 * PID-Definition fuer die GPS-Geschwindigkeit. Sie traegt bewusst dieselbe ID wie [PidRegistry.SPEED],
 * damit der Wert in den Geschwindigkeits-Slot faellt und von Aufzeichnung und Fahrterkennung wie
 * eine Geschwindigkeit behandelt wird — aber ein eigenes Label, damit die Quelle sichtbar bleibt, und [ValueSource.GPS] am Wert, damit sie
 * auch in der Aufzeichnung und im CSV-Export erkennbar bleibt.
 *
 * Bewusst *nicht* in `PidRegistry.all`: Der Wert wird nie ueber den OBD-Bus abgefragt und darf
 * deshalb weder im Sensor-Picker noch in der Polling-Liste auftauchen.
 */
internal val GPS_SPEED = PidRegistry.SPEED.copy(labelRes = R.string.pid_speed_gps)

/**
 * Ergaenzt die Messwerte um die GPS-Geschwindigkeit, wenn das Fahrzeug keine liefert.
 *
 * Der Wert vom Fahrzeug hat immer Vorrang: Er kommt aus dem Antriebsstrang, ist praeziser als
 * eine aus Positionen abgeleitete Geschwindigkeit und steht auch im Stand sofort zur Verfuegung.
 */
fun mergeGpsSpeed(readings: List<SensorValue>, gpsKmh: Float?): List<SensorValue> {
    val hasVehicleSpeed = readings.any {
        it.pid.id == PidRegistry.SPEED.id && !it.value.isNaN() && !it.value.isInfinite()
    }
    if (hasVehicleSpeed) return readings
    if (gpsKmh == null || gpsKmh.isNaN() || gpsKmh < 0f) return readings
    return readings + SensorValue(GPS_SPEED, gpsKmh, source = ValueSource.GPS)
}
