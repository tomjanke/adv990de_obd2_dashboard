package de.adv990de.obd2.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.bluetooth.BtType
import de.adv990de.obd2.bluetooth.ScannedDevice
import de.adv990de.obd2.data.ObdRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [DeviceRow] — the composable behind every row of `SettingsScreen`'s
 * paired-devices list. Documented gap (see this class's origin task): `SettingsScreenDeviceTest` covers
 * the GPS-speed switch and background-location escalation in detail, and its
 * `emptyState_noPairedDevicesMessage_isVisible` only exercises the *empty*-list path — `DeviceRow`
 * itself, and its three-way trailing-content branch, had zero coverage anywhere (JVM or
 * instrumentation) before this file.
 *
 * Covers the branch in `DeviceRow` (`SettingsScreen.kt`) that picks the trailing content:
 * - neither connected nor last-connected -> plain "Connect" button, no checkmark
 * - last-connected but not currently connected (`isLastConnected && !isConnected`) -> checkmark +
 *   "Connect" button
 * - currently connected -> "Disconnect" button, no checkmark even when also last-connected
 *
 * plus that the rendered button invokes the callback `DeviceRow` was given for that state
 * (`onClick` for both "Connect" states, `onDisconnect` only once connected) and never the other
 * one. `DeviceRow`'s `onClick`/`onDisconnect` are parameterless (`() -> Unit`) — the production
 * call site in `SettingsScreen` supplies the device identity itself via closure (`onClick = {
 * vm.connect(device) }`), so "the right device gets connected" is a property of that closure, not
 * of `DeviceRow`. What *is* `DeviceRow`'s responsibility, and what these tests assert, is wiring
 * the visible button to the matching callback parameter for each of the three states.
 *
 * `DeviceRow` is a public, standalone `@Composable` with no ViewModel/state dependency, so it is
 * exercised directly via `composeRule.setContent`, the same pattern `RpmBarTest` uses for `RpmBar`
 * in this same session. Still `@HiltAndroidTest`/`HiltTestActivity` because Hilt validates the
 * whole app graph per test class regardless (see `RpmBarTest`'s KDoc for the identical note) —
 * hence the `ObdRepository` `@BindValue` mock even though `DeviceRow` never touches it.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DeviceRowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        every { isConnectedFlow } returns MutableStateFlow(false)
        every { userDisconnectCount } returns MutableStateFlow(0)
        every { isMovingFlow } returns MutableStateFlow(false)
    }

    @Before
    fun setup() {
        hiltRule.inject()
    }

    // device = null: no real android.bluetooth.BluetoothDevice is available off-device, and
    // DeviceRow never dereferences ScannedDevice.device directly — only .name/.type, set below.
    private val device = ScannedDevice(device = null, type = BtType.CLASSIC, name = "OBDII Adapter")

    private fun connectLabel() = composeRule.activity.getString(R.string.settings_connect)
    private fun disconnectLabel() = composeRule.activity.getString(R.string.settings_disconnect)
    private fun lastConnectedDesc() = composeRule.activity.getString(R.string.settings_last_connected_desc)

    @Test
    fun neitherConnectedNorLastConnected_showsPlainConnectButton_noCheckmark() {
        composeRule.setContent {
            DeviceRow(device = device, onClick = {}, isConnected = false, isLastConnected = false)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(connectLabel()).assertIsDisplayed()
        composeRule.onNodeWithText(disconnectLabel()).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(lastConnectedDesc()).assertDoesNotExist()
    }

    @Test
    fun lastConnectedButNotCurrentlyConnected_showsCheckmarkPlusConnectButton() {
        composeRule.setContent {
            DeviceRow(device = device, onClick = {}, isConnected = false, isLastConnected = true)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(connectLabel()).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(lastConnectedDesc()).assertIsDisplayed()
    }

    @Test
    fun currentlyConnected_showsDisconnectButton_noCheckmarkEvenIfAlsoLastConnected() {
        // isLastConnected = true here too: production guards the checkmark with
        // `isLastConnected && !isConnected`, so this pins down that isConnected wins.
        composeRule.setContent {
            DeviceRow(device = device, onClick = {}, isConnected = true, isLastConnected = true)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(disconnectLabel()).assertIsDisplayed()
        composeRule.onNodeWithText(connectLabel()).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(lastConnectedDesc()).assertDoesNotExist()
    }

    @Test
    fun connectButtonClick_plainState_invokesOnClick() {
        var clicked = false
        composeRule.setContent {
            DeviceRow(device = device, onClick = { clicked = true }, isConnected = false, isLastConnected = false)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(connectLabel()).performClick()
        assertEquals(true, clicked)
    }

    @Test
    fun connectButtonClick_lastConnectedState_invokesOnClick() {
        var clicked = false
        composeRule.setContent {
            DeviceRow(device = device, onClick = { clicked = true }, isConnected = false, isLastConnected = true)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(connectLabel()).performClick()
        assertEquals(true, clicked)
    }

    @Test
    fun disconnectButtonClick_connectedState_invokesOnDisconnect_notOnClick() {
        var connectClicked = false
        var disconnectClicked = false
        composeRule.setContent {
            DeviceRow(
                device = device,
                onClick = { connectClicked = true },
                onDisconnect = { disconnectClicked = true },
                isConnected = true
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(disconnectLabel()).performClick()
        assertEquals(true, disconnectClicked)
        assertEquals(false, connectClicked)
    }
}
