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
            dashboardStatusDotState(isConnected = true, isReconnecting = true)
        )
    }

    @Test
    fun `reconnecting without a connection shows Connecting`() {
        assertEquals(
            StatusDotState.Connecting,
            dashboardStatusDotState(isConnected = false, isReconnecting = true)
        )
    }

    @Test
    fun `neither connected nor reconnecting shows no dot`() {
        assertNull(dashboardStatusDotState(isConnected = false, isReconnecting = false))
    }
}
