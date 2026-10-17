package de.adv990de.obd2.ui.history

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget

/** Glove-friendly touch height of the dialog buttons (CLAUDE.md design principle 4); the M3 default of a dialog `TextButton` is only 40 dp. */
private val DialogButtonMinHeight = 56.dp

/** Test tags of the two ways to export. */
internal const val EXPORT_ALL_SHARE_TEST_TAG = "export_all_share"
internal const val EXPORT_ALL_SAVE_TEST_TAG = "export_all_save"

/**
 * "Alle Fahrten exportieren": alle abgeschlossenen Fahrten in EINER CSV-Datei, entweder ueber das Teilen-Menue ([onShare]) oder in eine Datei,
 * die der Nutzer mit dem Dateidialog des Systems waehlt ([onSave], z. B. Downloads — ohne Cloud-Umweg). Ein einfacher [AlertDialog], bewusst
 * kein Bottom Sheet (CLAUDE.md "Verbotene Patterns"); der Dialog ist ein eigenes Fenster, die 56-dp-Regel aus `AppTheme` gilt dort nur, wenn
 * sie im Slot erneut gesetzt wird ([ProvideMinTouchTarget]).
 */
@Composable
internal fun ExportAllDialog(tripCount: Int, onShare: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_export_all_title)) },
        text = { Text(pluralStringResource(R.plurals.history_export_all_message, tripCount, tripCount)) },
        confirmButton = {
            ProvideMinTouchTarget {
                Row {
                    TextButton(onClick = onSave, modifier = Modifier.heightIn(min = DialogButtonMinHeight).testTag(EXPORT_ALL_SAVE_TEST_TAG)) {
                        Text(stringResource(R.string.history_export_save))
                    }
                    TextButton(onClick = onShare, modifier = Modifier.heightIn(min = DialogButtonMinHeight).testTag(EXPORT_ALL_SHARE_TEST_TAG)) {
                        Text(stringResource(R.string.history_export_share))
                    }
                }
            }
        },
        dismissButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = DialogButtonMinHeight)) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

// region — Previews
//
// Ein Dialog ist ein eigenes Fenster: die Vorschau zeigt ihn in beiden Flaechen.

@Preview(name = "ExportAllDialog · light", showBackground = true)
@Composable
private fun PreviewExportAllDialogLight() {
    PreviewTheme(dark = false) { ExportAllDialog(tripCount = 9, onShare = {}, onSave = {}, onDismiss = {}) }
}

@Preview(name = "ExportAllDialog · dark", showBackground = true)
@Composable
private fun PreviewExportAllDialogDark() {
    PreviewTheme(dark = true) { ExportAllDialog(tripCount = 9, onShare = {}, onSave = {}, onDismiss = {}) }
}

// endregion
