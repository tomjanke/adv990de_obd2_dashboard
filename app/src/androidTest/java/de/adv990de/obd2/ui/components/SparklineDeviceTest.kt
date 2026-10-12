package de.adv990de.obd2.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
import de.adv990de.obd2.data.ObdRepository
import de.adv990de.obd2.idleObdRepository
import io.mockk.every
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for [Sparkline] — previously without any instrumentation coverage (documented
 * gap). The pure functions it delegates to, `smoothSparklinePoints()` and `sparklinePointOffset()`
 * (the latter extracted from this Composable's `Canvas` block specifically to close this gap —
 * see its KDoc in `Sparkline.kt`), are already exhaustively covered as JVM tests in
 * `SparklineTest.kt` (`app/src/test/java/de/adv990de/obd2/ui/components/SparklineTest.kt`, same simple
 * name, JVM counterpart per this project's one-android-test-plus-one-jvm-test convention).
 *
 * `Sparkline` itself renders only a `Canvas` — no text, no semantics beyond what `Modifier`
 * carries. Matching this project's established ceiling for Canvas-only composables (e.g.
 * `RpmBarTest`'s segment bar, `SlotPickerSheetTest`): no `captureToImage`/pixel-level assertion
 * exists anywhere in this codebase, so nothing here asserts drawn pixels. Each test instead tags
 * the `Canvas` via its `modifier` parameter and asserts the tagged node exists after
 * `waitForIdle()` — i.e. composition completed without throwing — which is exactly the risk this
 * class targets: the `points.size < 2` early return, and the flat-line divide-by-zero guard
 * (`range = (max - min).coerceAtLeast(0.001f)`) that a regression could silently drop since float
 * division never throws (it would just produce a NaN coordinate instead of a visible crash).
 *
 * Hilt/HiltTestActivity setup mirrored from `SectionLabelTest`/`RpmBarTest` in this module:
 * `@HiltAndroidTest` + `createAndroidComposeRule<HiltTestActivity>()` is required for every
 * Compose UI test class here (Hilt validates the full app graph per test class, incl.
 * `ObdForegroundService`'s entry point), even though `Sparkline` itself needs no Hilt object —
 * confirmed necessary in those classes' own KDoc, not re-litigated here.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SparklineDeviceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val obdRepository: ObdRepository = idleObdRepository()

    private val tag = "sparkline"

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun normalMultiPointList_rendersWithoutCrashing() {
        composeRule.setContent {
            MaterialTheme {
                Sparkline(
                    points = listOf(10f, 25f, 15f, 40f, 30f, 45f),
                    modifier = Modifier.testTag(tag).size(width = 120.dp, height = 48.dp),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(tag).assertExists()
    }

    @Test
    fun flatLine_allPointsEqual_rendersWithoutCrashing() {
        // min == max here: exercises the `range = (max - min).coerceAtLeast(0.001f)` guard.
        // Without it this would divide by zero (NaN coordinates) instead of throwing outright,
        // so "renders without crashing" plus "the tagged node exists" is the strongest check
        // available at this level — see class KDoc.
        composeRule.setContent {
            MaterialTheme {
                Sparkline(
                    points = listOf(20f, 20f, 20f, 20f, 20f),
                    modifier = Modifier.testTag(tag).size(width = 120.dp, height = 48.dp),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(tag).assertExists()
    }

    @Test
    fun fewerThanTwoPoints_emptyList_rendersWithoutCrashing() {
        // points.size < 2: Sparkline returns before composing a Canvas at all, so the tagged
        // node must NOT exist — asserting that (rather than just "no crash") pins down that the
        // early-return guard actually took effect, not merely that some Canvas rendered.
        composeRule.setContent {
            MaterialTheme {
                Sparkline(
                    points = emptyList(),
                    modifier = Modifier.testTag(tag).size(width = 120.dp, height = 48.dp),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(tag).assertDoesNotExist()
    }

    @Test
    fun fewerThanTwoPoints_singlePoint_rendersWithoutCrashing() {
        composeRule.setContent {
            MaterialTheme {
                Sparkline(
                    points = listOf(42f),
                    modifier = Modifier.testTag(tag).size(width = 120.dp, height = 48.dp),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(tag).assertDoesNotExist()
    }
}
