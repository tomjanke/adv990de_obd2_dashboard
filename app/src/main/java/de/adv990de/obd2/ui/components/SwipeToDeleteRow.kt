package de.adv990de.obd2.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme

/** How far a row must be dragged before the release deletes it — half its width, so a stray touch never does. */
private const val DELETE_THRESHOLD_SHARE = 0.5f

/**
 * A list row that is deleted by swiping it toward the END edge — the shared swipe of the local delete
 * contract. It is only an ADDITION to a visible delete button (CLAUDE.md design principle 4: no
 * swipe-only gestures): one direction only, [DELETE_THRESHOLD_SHARE] of the width, and a delete
 * background in the theme's error container with a trash icon appears only while dragging.
 *
 * [onDelete] runs ONCE, when the row has been swiped out past the threshold; the row then stays
 * swiped out until the caller removes it from the list. Hiding it at once and offering undo is the
 * caller's job (`UndoableDeletion`) — an undone row comes back as a fresh composition with a fresh
 * swipe state. It is deliberately NOT called from `confirmValueChange`: Material3 asks that callback
 * several times per gesture (measured: 4 calls for one swipe), and every extra delete would commit
 * the previous one at once. With [enabled] = false (e.g. the trip that is being recorded) the row
 * does not react to swipes.
 *
 * The [content] needs an opaque background (a `ListItem` has one), otherwise the delete background
 * would shine through at rest — it is only drawn while a swipe is in progress.
 */
@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val currentOnDelete by rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState(
        // Direction filter only — no side effect here, see the KDoc.
        confirmValueChange = { target -> target == SwipeToDismissBoxValue.EndToStart },
        positionalThreshold = { totalDistance -> totalDistance * DELETE_THRESHOLD_SHARE },
    )
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) currentOnDelete()
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        gesturesEnabled = enabled,
        backgroundContent = {
            val swiping = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (swiping) MaterialTheme.colorScheme.errorContainer else Color.Transparent)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (swiping) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
    ) {
        content()
    }
}

// region — Previews

@Composable
private fun PreviewSwipeToDeleteRowContent() {
    SwipeToDeleteRow(onDelete = {}, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text("04.10.2026 14:25") },
            supportingContent = { Text("FIN unbekannt") },
        )
    }
}

@Preview(name = "SwipeToDeleteRow · light", showBackground = true)
@Composable
private fun PreviewSwipeToDeleteRowLight() {
    MaterialTheme(typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) { PreviewSwipeToDeleteRowContent() }
    }
}

@Preview(name = "SwipeToDeleteRow · dark (blue)", showBackground = true)
@Composable
private fun PreviewSwipeToDeleteRowDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) { PreviewSwipeToDeleteRowContent() }
    }
}

// endregion
