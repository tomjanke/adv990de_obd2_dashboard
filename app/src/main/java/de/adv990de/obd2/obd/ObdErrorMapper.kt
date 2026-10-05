package de.adv990de.obd2.obd

import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.BluetoothException
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.ui.UiText

object ObdErrorMapper {
    /**
     * Ergibt immer einen sichtbaren Text, nie null und nie leer: Ein Fehlerzustand ohne Text sah fuer die
     * Oberflaeche aus wie "kein Fehler" (die DTC-Seite meldete dann "Keine Fehlercodes").
     * Bekannte Ausnahmen werden lokalisiert; bei unbekannten ist die technische Meldung (oder
     * zumindest der Klassenname) fuer die Fehlersuche am Fahrzeug nuetzlicher als ein "Unbekannter Fehler".
     */
    fun toUiText(e: Throwable): UiText = when (e) {
        is ObdException.ConnectionLost       -> UiText.StringResource(R.string.obd_err_connection_lost)
        is ObdException.BusInit              -> UiText.StringResource(R.string.obd_err_bus_init)
        is ObdException.Timeout              -> UiText.StringResource(R.string.obd_err_timeout)
        is ObdException.InitializationFailed -> UiText.StringResource(R.string.obd_err_init_failed)
        is ObdException.NoData               -> UiText.StringResource(R.string.obd_err_no_data)
        is ObdException.UnknownResponse      -> UiText.StringResource(R.string.obd_err_unknown_response)
        is BluetoothException.ConnectionLost -> UiText.StringResource(R.string.obd_err_connection_lost)
        is BluetoothException.ConnectionFailed -> UiText.StringResource(R.string.obd_err_connection_failed)
        is BluetoothException.DeviceNotFound -> UiText.StringResource(R.string.obd_err_device_not_found)
        is BluetoothException.PermissionDenied -> UiText.StringResource(R.string.obd_err_bluetooth_permission)
        is SecurityException                 -> UiText.StringResource(R.string.obd_err_bluetooth)
        else                                 -> UiText.DynamicString(
            e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        )
    }
}
