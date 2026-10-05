package de.adv990de.obd2.ui.dashboard

import androidx.compose.ui.graphics.Color
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.UiText
import de.adv990de.obd2.ui.components.StatusDotState
import de.adv990de.obd2.ui.theme.StatusColors
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

    // --- connectionDotColor ---

    @Test
    fun `Ok is a real fixed green, not the theme-tinted default`() {
        assertEquals(Color(0xFF22C55E), connectionDotColor(StatusDotState.Ok))
    }

    @Test
    fun `Connecting is a real fixed yellow, not theme primary`() {
        assertEquals(StatusColors.Warning, connectionDotColor(StatusDotState.Connecting))
    }

    @Test
    fun `Critical reuses the existing StatusColors red, unchanged`() {
        assertEquals(StatusColors.Critical, connectionDotColor(StatusDotState.Critical))
    }

    @Test
    fun `Warning reuses the existing StatusColors amber, unchanged`() {
        // Not reachable via dashboardStatusDotState() today, but connectionDotColor() must still
        // return something sane for every StatusDotState value.
        assertEquals(StatusColors.Warning, connectionDotColor(StatusDotState.Warning))
    }

    // ── B25: the banner says WHY there is no connection ──────────────────────

    private val someError = UiText.StringResource(R.string.err_reconnect_fail)

    @Test
    fun `an error without a connection is shown as the banner reason`() {
        assertEquals(
            someError,
            previewBannerReason(isConnected = false, isReconnecting = false, error = someError)
        )
    }

    @Test
    fun `no error means no reason - the plain preview banner`() {
        assertNull(previewBannerReason(isConnected = false, isReconnecting = false, error = null))
    }

    @Test
    fun `a running reconnect hides a stale error so the banner does not contradict the pulsing dot`() {
        assertNull(previewBannerReason(isConnected = false, isReconnecting = true, error = someError))
    }

    @Test
    fun `a connection never shows a reason`() {
        assertNull(previewBannerReason(isConnected = true, isReconnecting = false, error = someError))
    }
}
