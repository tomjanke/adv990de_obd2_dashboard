package de.adv990de.obd2.ui.logbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.logbook.ImportPlan
import de.adv990de.obd2.ui.UiText
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget
import de.adv990de.obd2.ui.transfer.LogbookPreviewBody
import de.adv990de.obd2.ui.transfer.previewLogbookPlan

/** Test tags of the buttons and the preview body. */
internal const val LOGBOOK_IMPORT_CONFIRM_TEST_TAG = "logbook_import_confirm"
internal const val LOGBOOK_IMPORT_CLOSE_TEST_TAG = "logbook_import_close"
internal const val LOGBOOK_IMPORT_CANCEL_TEST_TAG = "logbook_import_cancel"
internal const val LOGBOOK_IMPORT_BODY_TEST_TAG = "logbook_import_body"

/** Glove-friendly touch height of the dialog buttons (the M3 default of a dialog `TextButton` is only 40 dp). */
private val DialogButtonMinHeight = 56.dp

/**
 * Der Dialog des Imports: Arbeit ([ImportState.Working]), die Vorschau mit den Zahlen und den abgelehnten Zeilen ([ImportState.Preview] — erst "Importieren" schreibt),
 * das Ergebnis ([ImportState.Done]) oder der Grund eines Scheiterns ([ImportState.Failed]). Bei [ImportState.Idle] ist nichts zu sehen. Ein [AlertDialog], kein Bottom
 * Sheet; `ProvideMinTouchTarget` in den Slots (eigenes Fenster).
 */
@Composable
internal fun LogbookImportDialog(state: ImportState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    when (state) {
        ImportState.Idle -> Unit
        ImportState.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.logbook_import_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.logbook_import_working))
                }
            },
            confirmButton = {},
        )
        is ImportState.Preview -> PreviewDialog(state.plan, onConfirm, onDismiss)
        is ImportState.Done -> MessageDialog(pluralStringResource(R.plurals.logbook_import_done, state.count, state.count), onDismiss)
        is ImportState.Failed -> MessageDialog(state.text.asString(), onDismiss)
    }
}

@Composable
private fun CloseButton(onDismiss: () -> Unit) {
    ProvideMinTouchTarget {
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = DialogButtonMinHeight).testTag(LOGBOOK_IMPORT_CLOSE_TEST_TAG)) {
            Text(stringResource(R.string.logbook_import_close))
        }
    }
}

@Composable
private fun MessageDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.logbook_import_title)) },
        text = { Text(message) },
        confirmButton = { CloseButton(onDismiss) },
    )
}

@Composable
private fun PreviewDialog(plan: ImportPlan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val nothingNew = plan.toInsert.isEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.logbook_import_title)) },
        text = { LogbookPreviewBody(plan, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(LOGBOOK_IMPORT_BODY_TEST_TAG)) },
        confirmButton = {
            if (nothingNew) CloseButton(onDismiss)
            else ProvideMinTouchTarget {
                TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = DialogButtonMinHeight).testTag(LOGBOOK_IMPORT_CONFIRM_TEST_TAG)) {
                    Text(stringResource(R.string.logbook_import_confirm))
                }
            }
        },
        dismissButton = if (nothingNew) null else {
            {
                ProvideMinTouchTarget {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = DialogButtonMinHeight).testTag(LOGBOOK_IMPORT_CANCEL_TEST_TAG)) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        },
    )
}

// region — Previews
//
// Ein Dialog ist ein eigenes Fenster: jede Vorschau zeigt ihn in einer Flaeche.

@Preview(name = "LogbookImportDialog · preview (light)", showBackground = true)
@Composable
private fun PreviewLogbookImportDialogPreviewLight() {
    PreviewTheme(dark = false) { LogbookImportDialog(ImportState.Preview(previewLogbookPlan()), onConfirm = {}, onDismiss = {}) }
}

@Preview(name = "LogbookImportDialog · nothing new (dark)", showBackground = true)
@Composable
private fun PreviewLogbookImportDialogNothingNewDark() {
    PreviewTheme(dark = true) {
        LogbookImportDialog(ImportState.Preview(ImportPlan(emptyList(), duplicates = 102, rejected = emptyList(), warnings = 0)), onConfirm = {}, onDismiss = {})
    }
}

@Preview(name = "LogbookImportDialog · done (light)", showBackground = true)
@Composable
private fun PreviewLogbookImportDialogDoneLight() {
    PreviewTheme(dark = false) { LogbookImportDialog(ImportState.Done(98), onConfirm = {}, onDismiss = {}) }
}

@Preview(name = "LogbookImportDialog · failed (dark)", showBackground = true)
@Composable
private fun PreviewLogbookImportDialogFailedDark() {
    PreviewTheme(dark = true) {
        LogbookImportDialog(ImportState.Failed(UiText.StringResource(R.string.logbook_import_error_format)), onConfirm = {}, onDismiss = {})
    }
}

// endregion
