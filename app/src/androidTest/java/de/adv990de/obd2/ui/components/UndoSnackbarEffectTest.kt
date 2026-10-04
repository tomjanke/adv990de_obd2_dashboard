package de.adv990de.obd2.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import de.adv990de.obd2.HiltTestActivity
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
 * Compose UI tests for [UndoSnackbarEffect]: the snackbar is only a VIEW of the open undo batch —
 * shown (without a timeout of its own) while a batch id is present, dismissed when it goes away,
 * and the "undo" action reports back. The window itself is owned by `UndoableDeletion`
 * (JVM-tested with virtual time).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class UndoSnackbarEffectTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    /** Required boilerplate of every @HiltAndroidTest class here, see [SectionLabelTest]. */
    @BindValue
    @JvmField
    val obdRepository: ObdRepository = mockk(relaxed = true) {
        every { isConnectedFlow } returns MutableStateFlow(false)
        every { isMovingFlow } returns MutableStateFlow(false)
    }

    @Before
    fun setup() {
        hiltRule.inject()
    }

    private var undone = 0
    private var batchId by mutableStateOf<Long?>(null)

    private fun launch(initial: Long?) {
        batchId = initial
        composeRule.setContent {
            MaterialTheme {
                val host = remember { SnackbarHostState() }
                UndoSnackbarEffect(
                    batchId = batchId,
                    message = "Things deleted",
                    undoLabel = "Undo",
                    snackbarHostState = host,
                    onUndo = { undone++ },
                )
                Scaffold(snackbarHost = { SnackbarHost(host) }) { padding -> Box(Modifier.padding(padding)) }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun noBatch_showsNoSnackbar() {
        launch(initial = null)
        composeRule.onNodeWithText("Things deleted").assertDoesNotExist()
    }

    @Test
    fun anOpenBatch_showsTheMessageAndTheUndoAction() {
        launch(initial = 1L)
        composeRule.onNodeWithText("Things deleted").assertIsDisplayed()
        composeRule.onNodeWithText("Undo").assertIsDisplayed()
    }

    @Test
    fun tappingUndo_reportsBack() {
        launch(initial = 1L)
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()
        assertEquals(1, undone)
    }

    @Test
    fun whenTheBatchCloses_theSnackbarGoesAway() {
        launch(initial = 1L)
        composeRule.onNodeWithText("Things deleted").assertIsDisplayed()

        composeRule.runOnIdle { batchId = null }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Things deleted").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(0, undone)
    }

    @Test
    fun aNewBatchShowsTheSnackbarAgain() {
        launch(initial = 1L)
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()
        assertEquals(1, undone)

        composeRule.runOnIdle { batchId = null }
        composeRule.waitForIdle()
        composeRule.runOnIdle { batchId = 2L }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Things deleted").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
