package de.adv990de.obd2.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget

/** Glove-friendly touch height of the dialog buttons (CLAUDE.md design principle 4); the M3 default of a dialog `TextButton` is only 40 dp. */
private val DialogButtonMinHeight = 56.dp

/**
 * Die Frage "wie exportieren?" — gemeinsam fuer „Alle Fahrten exportieren“ ([de.adv990de.obd2.ui.history.ExportAllDialog]) und „Log exportieren“
 * ([de.adv990de.obd2.ui.settings.AppLogExportDialog]): entweder ueber das Teilen-Menue ([onShare]) oder in eine Datei, die der Nutzer mit dem Dateidialog des
 * Systems waehlt ([onSave], z. B. Downloads — ohne Cloud-Umweg). Ein einfacher [AlertDialog], bewusst kein Bottom Sheet (CLAUDE.md "Verbotene
 * Patterns"); der Dialog ist ein eigenes Fenster, die 56-dp-Regel aus `AppTheme` gilt dort nur, wenn sie im Slot erneut gesetzt wird
 * ([ProvideMinTouchTarget]). [saveTag] und [shareTag] sind die Test-Tags der beiden Knoepfe (je Aufrufer eigene).
 */
@Composable
internal fun ExportChoiceDialog(
    title: String,
    message: String,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    saveTag: String,
    shareTag: String,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            ProvideMinTouchTarget {
                Row {
                    TextButton(onClick = onSave, modifier = Modifier.heightIn(min = DialogButtonMinHeight).testTag(saveTag)) {
                        Text(stringResource(R.string.history_export_save))
                    }
                    TextButton(onClick = onShare, modifier = Modifier.heightIn(min = DialogButtonMinHeight).testTag(shareTag)) {
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
