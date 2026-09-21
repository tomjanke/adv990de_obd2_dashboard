package de.adv990de.obd2.obd

import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.BluetoothException
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.ui.UiText

object ObdErrorMapper {
    fun toUiText(e: Throwable): UiText = when (e) {
        is ObdException.ConnectionLost       -> UiText.StringResource(R.string.obd_err_connection_lost)
        is ObdException.BusInit              -> UiText.StringResource(R.string.obd_err_bus_init)
        is ObdException.Timeout              -> UiText.StringResource(R.string.obd_err_timeout)
        is ObdException.InitializationFailed -> UiText.StringResource(R.string.obd_err_init_failed)
        is BluetoothException.PermissionDenied -> UiText.StringResource(R.string.obd_err_bluetooth_permission)
        is SecurityException                 -> UiText.StringResource(R.string.obd_err_bluetooth)
        else                                 -> UiText.DynamicString(e.message ?: e.javaClass.simpleName)
    }
}
