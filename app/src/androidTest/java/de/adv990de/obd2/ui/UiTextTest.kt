package de.adv990de.obd2.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.R
import de.adv990de.obd2.data.ObdRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [UiText] — previously untested anywhere in the repo (documented coverage
 * gap). The only logic is [UiText.asString]'s two-way `when` dispatch: [UiText.StringResource]
 * resolves via [androidx.compose.ui.res.stringResource], [UiText.DynamicString] just returns its
 * held value. `asString()` is `@Composable` (it calls `stringResource()`), so it needs a Compose
 * test rule rather than a plain JVM test.
 *
 * Same `@HiltAndroidTest` + `createAndroidComposeRule<HiltTestActivity>` + `@BindValue` mock
 * `ObdRepository` boilerplate as [de.adv990de.obd2.ui.components.SectionLabelTest] /
 * [de.adv990de.obd2.ui.components.LiveValueTextTest] — required by this project even though `UiText`
 * itself has no Hilt dependency, because Hilt validates the whole app graph per test class.
 *
 * Note (for awareness, not a defect to fix here): `asString()` currently has zero call sites under
 * `app/src/main` — this was investigated, and the coverage gap is closed anyway since it is small,
 * real decision logic that could gain a call site at any time.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class UiTextTest {

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

    @Test
    fun dynamicString_asString_returnsHeldValue() {
        val text: UiText = UiText.DynamicString("some text")

        composeRule.setContent {
            MaterialTheme {
                Text(text = text.asString())
            }
        }

        composeRule.onNodeWithText("some text").assertTextEquals("some text")
    }

    @Test
    fun stringResource_asString_returnsResolvedResourceValue() {
        val text: UiText = UiText.StringResource(R.string.err_no_device)

        composeRule.setContent {
            MaterialTheme {
                Text(text = text.asString())
            }
        }

        val expected = composeRule.activity.getString(R.string.err_no_device)
        composeRule.onNodeWithText(expected).assertTextEquals(expected)
    }
}
