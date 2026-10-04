package de.adv990de.obd2.obd

import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.BluetoothException
import de.adv990de.obd2.obd.model.ObdException
import de.adv990de.obd2.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdErrorMapperTest {

    @Test
    fun `ConnectionLost maps to StringResource`() {
        val result = ObdErrorMapper.toUiText(ObdException.ConnectionLost())
        assertEquals(UiText.StringResource(R.string.obd_err_connection_lost), result)
    }

    @Test
    fun `BusInit maps to StringResource`() {
        val result = ObdErrorMapper.toUiText(ObdException.BusInit())
        assertEquals(UiText.StringResource(R.string.obd_err_bus_init), result)
    }

    @Test
    fun `Timeout maps to StringResource`() {
        val result = ObdErrorMapper.toUiText(ObdException.Timeout("010C"))
        assertEquals(UiText.StringResource(R.string.obd_err_timeout), result)
    }

    @Test
    fun `InitializationFailed maps to StringResource`() {
        val result = ObdErrorMapper.toUiText(ObdException.InitializationFailed("NO DATA"))
        assertEquals(UiText.StringResource(R.string.obd_err_init_failed), result)
    }

    @Test
    fun `SecurityException maps to StringResource`() {
        val result = ObdErrorMapper.toUiText(SecurityException("permission denied"))
        assertEquals(UiText.StringResource(R.string.obd_err_bluetooth), result)
    }

    @Test
    fun `unknown exception with message maps to DynamicString with message`() {
        val result = ObdErrorMapper.toUiText(RuntimeException("boom"))
        assertEquals(UiText.DynamicString("boom"), result)
    }

    @Test
    fun `unknown exception with null message maps to DynamicString with class name`() {
        val result = ObdErrorMapper.toUiText(RuntimeException())
        assertTrue(
            "Expected class name fallback",
            (result as UiText.DynamicString).value.contains("RuntimeException")
        )
    }

    @Test
    fun `BluetoothException PermissionDenied maps to the permission StringResource`() {
        val result = ObdErrorMapper.toUiText(BluetoothException.PermissionDenied("BLUETOOTH_CONNECT"))
        assertEquals(UiText.StringResource(R.string.obd_err_bluetooth_permission), result)
    }

    @Test
    fun `BluetoothException ConnectionFailed maps to a localized text instead of the raw English message`() {
        val result = ObdErrorMapper.toUiText(BluetoothException.ConnectionFailed("read failed, socket might closed"))
        assertEquals(UiText.StringResource(R.string.obd_err_connection_failed), result)
    }

    @Test
    fun `BluetoothException DeviceNotFound maps to a localized text`() {
        val result = ObdErrorMapper.toUiText(BluetoothException.DeviceNotFound("MWT_OBD2"))
        assertEquals(UiText.StringResource(R.string.obd_err_device_not_found), result)
    }

    @Test
    fun `BluetoothException ConnectionLost maps to the connection lost text`() {
        val result = ObdErrorMapper.toUiText(BluetoothException.ConnectionLost())
        assertEquals(UiText.StringResource(R.string.obd_err_connection_lost), result)
    }

    @Test
    fun `unknown exception with a blank message falls back to the class name, never to an empty text`() {
        val result = ObdErrorMapper.toUiText(IllegalStateException("   "))
        assertEquals(UiText.DynamicString("IllegalStateException"), result)
    }
}
