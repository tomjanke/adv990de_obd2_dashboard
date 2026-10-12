package de.adv990de.obd2.ui.permission

import android.Manifest
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
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
 * Instrumentation tests for the [PermissionGate] composable — previously zero coverage (no
 * `androidTest` file existed for this package at all; `PermissionGateTest` in `test` (JVM) only
 * covers the pure `requiredPermissions(sdkInt)` branching, not the composable itself).
 *
 * ## What is covered here
 *
 * Only the "permissions already granted" path: [GrantPermissionRule] pre-grants exactly the set
 * `requestedPermissions()` resolves to on this test device's real `Build.VERSION.SDK_INT` (API 31+:
 * `BLUETOOTH_CONNECT`; API 33+ additionally the OPTIONAL `POST_NOTIFICATIONS` —
 * this device is API 37, so both apply; granting the optional one keeps the system dialog from
 * being launched). With that pre-granted, `PermissionGate`'s initial `allGranted`
 * computation (`allRequiredGranted`) is `true` *before*
 * composition starts, so:
 * - `content()` is rendered directly ([allPermissionsGranted_rendersContentDirectly]), and
 * - the rationale screen (title/rationale/grant-button text) is never composed
 *   ([allPermissionsGranted_doesNotShowRationaleScreen]).
 *
 * This closes item 1 (initial `allGranted` computation) and the "granted" half of item 5
 * (content-vs-rationale conditional rendering) from the coverage-gap list.
 *
 * ## What is deliberately NOT covered, and why
 *
 * Every other documented gap — the `DisposableEffect`'s `ON_RESUME` re-check, the
 * `LaunchedEffect(Unit)` auto-launch condition, the launcher callback
 * (`results.values.all { it }`), and the "not granted" half of item 5 (rationale screen actually
 * rendering) — all require putting `PermissionGate` into composition with `allGranted = false`.
 *
 * That state cannot be reached safely on this real, shared physical device:
 * `LaunchedEffect(Unit) { if (!allGranted) launcher.launch(requiredPermissions()) }` is
 * unconditional — the instant `allGranted` is `false` at first composition, it calls
 * `ActivityResultLauncher.launch(...)`, which starts the real system permission-request activity
 * (`PermissionController`/`GrantPermissionsActivity`). Investigated concretely:
 * - Without a `GrantPermissionRule`, this test class has no control over the *current* grant
 *   state of BLUETOOTH_CONNECT/POST_NOTIFICATIONS on this specific installed debug
 *   build — they may already be granted from prior manual use, or not — and there is no
 *   permission-in-scope way to force "not granted" without either revoking real permissions via a
 *   shell command (which would leave the developer's own physical device in a mutated permission
 *   state after the run — explicitly out of scope: this session was told not to touch `adb` beyond
 *   gradle test tasks and not to mutate this shared device) or actually dismissing a live system
 *   dialog (explicitly out of scope per this session's established boundary).
 * - Even if "not granted" were reached, `composeRule`'s `waitForIdle()` (invoked implicitly by any
 *   assertion) drains the pending `LaunchedEffect` coroutine, so `launcher.launch(...)` — and the
 *   real system dialog — would already have fired by the time any assertion runs.
 * - The system permission dialog is launched in the same task as `HiltTestActivity`, on top of it.
 *   Finishing the underlying activity at test teardown (which `createAndroidComposeRule` does
 *   automatically) does not finish a non-top activity's superseding dialog — it would very likely
 *   leave that dialog stuck open on the device afterwards, exactly the risk this task called out to
 *   avoid.
 * - The rationale screen's "grant" button (`Button(onClick = { launcher.launch(...) })`) calls the
 *   exact same launcher, so clicking it (even via semantics `performClick()`, which invokes the
 *   `onClick` callback directly rather than a real touch) hits the identical real-dialog problem —
 *   it is not a safer proxy for this.
 * - `ON_RESUME` re-granting the same way would additionally require mutating the device's real
 *   permission state mid-test (grant while backgrounded, then resume) to observe a flip, which
 *   carries the same shared-device risk.
 *
 * These four items remain a documented gap, matching this project's own established precedent in
 * `SettingsScreenDeviceTest` ("there is no established pattern in this codebase for driving or
 * dismissing [a system permission dialog] mid-test, and a lingering dialog would risk breaking
 * whichever test runs next").
 *
 * Hilt/HiltTestActivity setup mirrored from `SectionLabelTest`/`SparklineDeviceTest` in this module:
 * `@HiltAndroidTest` + `createAndroidComposeRule<HiltTestActivity>()` is required for every Compose
 * UI test class here (Hilt validates the full app graph per test class, incl.
 * `ObdForegroundService`'s entry point), even though `PermissionGate` itself needs no Hilt object —
 * confirmed necessary in those classes' own KDoc, not re-litigated here. The `@BindValue`
 * `ObdRepository` mock below is required for the same reason.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PermissionGateDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @get:Rule(order = 2)
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

    @Test
    fun allPermissionsGranted_rendersContentDirectly() {
        composeRule.setContent {
            MaterialTheme {
                PermissionGate {
                    Text("GATED_CONTENT")
                }
            }
        }

        composeRule.onNodeWithText("GATED_CONTENT").assertIsDisplayed()
    }

    @Test
    fun allPermissionsGranted_doesNotShowRationaleScreen() {
        composeRule.setContent {
            MaterialTheme {
                PermissionGate {
                    Text("GATED_CONTENT")
                }
            }
        }

        composeRule.onNodeWithText("GATED_CONTENT").assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.permission_bt_title))
            .assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.permission_bt_rationale))
            .assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.permission_grant))
            .assertDoesNotExist()
    }

    // ── Sperrbildschirm: zustandslos getestet, ohne den echten System-Dialog auszuloesen ──────────

    @Test
    fun permissionRequiredScreen_showsExplanationAndBothButtons() {
        composeRule.setContent { MaterialTheme { PermissionRequiredScreen(onGrant = {}, onOpenSettings = {}) } }

        val a = composeRule.activity
        composeRule.onNodeWithText(a.getString(R.string.permission_bt_title)).assertIsDisplayed()
        composeRule.onNodeWithText(a.getString(R.string.permission_bt_rationale)).assertIsDisplayed()
        composeRule.onNodeWithText(a.getString(R.string.permission_settings_hint)).assertIsDisplayed()
        composeRule.onNodeWithText(a.getString(R.string.permission_grant)).assertIsDisplayed()
        composeRule.onNodeWithText(a.getString(R.string.permission_open_settings)).assertIsDisplayed()
    }

    @Test
    fun permissionRequiredScreen_buttonsInvokeTheirOwnCallback() {
        var grant = 0
        var settings = 0
        composeRule.setContent {
            MaterialTheme { PermissionRequiredScreen(onGrant = { grant++ }, onOpenSettings = { settings++ }) }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.permission_open_settings)).performClick()
        assertEquals("settings button must not request again", 0, grant)
        assertEquals(1, settings)

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.permission_grant)).performClick()
        assertEquals(1, grant)
        assertEquals(1, settings)
    }

    @Test
    fun permissionRequiredScreen_buttonsAreGloveFriendly() {
        composeRule.setContent { MaterialTheme { PermissionRequiredScreen(onGrant = {}, onOpenSettings = {}) } }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.permission_grant))
            .assertHeightIsAtLeast(56.dp)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.permission_open_settings))
            .assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun appSettingsIntent_pointsAtTheDetailsPageOfThisApp() {
        val intent = appSettingsIntent("de.adv990de.obd2")

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:de.adv990de.obd2", intent.data.toString())
    }
}
