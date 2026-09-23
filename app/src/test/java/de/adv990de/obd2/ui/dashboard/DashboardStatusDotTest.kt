package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.ui.components.StatusDotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardStatusDotTest {

    @Test
    fun `connected takes precedence and shows Ok`() {
        assertEquals(
            StatusDotState.Ok,
            dashboardStatusDotState(isConnected = true, isReconnecting = true, hasError = true)
        )
    }

    @Test
    fun `reconnecting takes precedence over a stale error and shows Connecting`() {
        // isReconnecting=true and hasError=true can co-occur for real: a Failed event sets
        // error, and a later manual reconnect sets isReconnecting=true without clearing it
        // (see DashboardViewModel.startReconnecting). The new attempt in progress must win.
        assertEquals(
            StatusDotState.Connecting,
            dashboardStatusDotState(isConnected = false, isReconnecting = true, hasError = true)
        )
    }

    @Test
    fun `neither connected nor reconnecting, and no error, shows no dot`() {
        assertNull(dashboardStatusDotState(isConnected = false, isReconnecting = false, hasError = false))
    }

    @Test
    fun `neither connected nor reconnecting, but an error occurred, shows Critical`() {
        // Covers: no device configured, Bluetooth disabled, or all reconnect attempts
        // exhausted (ConnectionEvent.Failed) — DashboardUiState.error is never otherwise
        // shown to the user, so the dot is the only visual signal for this state.
        assertEquals(
            StatusDotState.Critical,
            dashboardStatusDotState(isConnected = false, isReconnecting = false, hasError = true)
        )
    }
}
