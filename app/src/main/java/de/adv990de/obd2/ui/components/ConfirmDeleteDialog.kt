package de.adv990de.obd2.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget
import de.adv990de.obd2.ui.theme.AppTypography
import de.adv990de.obd2.ui.theme.blueColorScheme

/** Glove-friendly touch height of the dialog buttons (CLAUDE.md design principle 4); the M3
 *  default of a dialog `TextButton` is only 40 dp. */
private val DialogButtonMinHeight = 56.dp

/**
 * The shared confirmation step of the local delete contract: title · consequence · cancel/delete.
 * A plain Material 3 [AlertDialog] — deliberately not a bottom sheet (CLAUDE.md "Verbotene
 * Patterns": no bottom sheets for critical actions). The delete button is tinted with the theme's
 * error color, the cancel button stays neutral.
 *
 * Used for "delete all" actions; a single row is deleted without a dialog and offers undo instead.
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        // Ein Dialog ist ein eigenes Fenster: die 56-dp-Regel aus AppTheme gilt nur, wenn sie in den Slots erneut gesetzt
        // wird (s. ProvideMinTouchTarget). Die Mindesthoehe unten bleibt: sie sorgt fuer die sichtbare Groesse.
        confirmButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = DialogButtonMinHeight)) {
                    Text(confirmLabel, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = DialogButtonMinHeight)) {
                    Text(dismissLabel)
                }
            }
        },
    )
}

// region — Previews

@Composable
private fun PreviewConfirmDeleteDialogContent() {
    ConfirmDeleteDialog(
        title = "Alle Logs löschen?",
        message = "Alle Log-Einträge werden gelöscht. Du kannst das gleich noch rückgängig machen.",
        confirmLabel = "Löschen",
        dismissLabel = "Abbrechen",
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview(name = "ConfirmDeleteDialog · light", showBackground = true)
@Composable
private fun PreviewConfirmDeleteDialogLight() {
    MaterialTheme(typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) { PreviewConfirmDeleteDialogContent() }
    }
}

@Preview(name = "ConfirmDeleteDialog · dark (blue)", showBackground = true)
@Composable
private fun PreviewConfirmDeleteDialogDark() {
    MaterialTheme(colorScheme = blueColorScheme, typography = AppTypography) {
        Surface(color = MaterialTheme.colorScheme.background) { PreviewConfirmDeleteDialogContent() }
    }
}

// endregion
