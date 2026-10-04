package de.adv990de.obd2.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * The "… deleted · Undo" snackbar of the local delete contract — a pure VIEW of
 * [UndoableDeletion.openBatchId].
 *
 * While [batchId] is non-null the snackbar is shown WITHOUT a timeout of its own
 * ([SnackbarDuration.Indefinite]); it goes away when the id turns null (undo pressed, window over,
 * or a newer batch replaced it), because the effect is cancelled and a cancelled `showSnackbar`
 * removes its snackbar. So the offer on screen and the real deletion are driven by one clock — the
 * one inside [UndoableDeletion] — and cannot drift apart, also not with the longer timeouts
 * accessibility services apply to snackbars. Tapping the action calls [onUndo].
 *
 * Needs a `SnackbarHost(snackbarHostState)` in the screen's `Scaffold`. No visual of its own, hence
 * no preview.
 */
@Composable
fun UndoSnackbarEffect(
    batchId: Long?,
    message: String,
    undoLabel: String,
    snackbarHostState: SnackbarHostState,
    onUndo: () -> Unit,
) {
    val currentOnUndo by rememberUpdatedState(onUndo)
    LaunchedEffect(batchId) {
        if (batchId != null) {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Indefinite,
            )
            if (result == SnackbarResult.ActionPerformed) currentOnUndo()
        }
    }
}
