package de.adv990de.obd2.ui.logbook

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.components.ExportChoiceDialog
import de.adv990de.obd2.ui.theme.PreviewTheme

/** Test tags of the two ways to export. */
internal const val LOGBOOK_EXPORT_SHARE_TEST_TAG = "logbook_export_share"
internal const val LOGBOOK_EXPORT_SAVE_TEST_TAG = "logbook_export_save"

/**
 * "Logbuch exportieren": das ganze Logbuch in EINER CSV-Datei, entweder ueber das Teilen-Menue ([onShare]) oder in eine Datei, die der Nutzer mit dem Dateidialog
 * des Systems waehlt ([onSave]). Der Dialog selbst ist [ExportChoiceDialog], geteilt mit den Exporten der Fahrten und des App-Logs. Der Export ist die einzige Sicherung:
 * der Text sagt, dass das Logbuch mit den App-Daten verloren geht.
 */
@Composable
internal fun LogbookExportDialog(entryCount: Int, onShare: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    ExportChoiceDialog(
        title = stringResource(R.string.logbook_export_title),
        message = pluralStringResource(R.plurals.logbook_export_message, entryCount, entryCount),
        onShare = onShare,
        onSave = onSave,
        onDismiss = onDismiss,
        saveTag = LOGBOOK_EXPORT_SAVE_TEST_TAG,
        shareTag = LOGBOOK_EXPORT_SHARE_TEST_TAG,
    )
}

// region — Previews
//
// Ein Dialog ist ein eigenes Fenster: die Vorschau zeigt ihn in beiden Flaechen.

@Preview(name = "LogbookExportDialog · light", showBackground = true)
@Composable
private fun PreviewLogbookExportDialogLight() {
    PreviewTheme(dark = false) { LogbookExportDialog(entryCount = 102, onShare = {}, onSave = {}, onDismiss = {}) }
}

@Preview(name = "LogbookExportDialog · dark", showBackground = true)
@Composable
private fun PreviewLogbookExportDialogDark() {
    PreviewTheme(dark = true) { LogbookExportDialog(entryCount = 1, onShare = {}, onSave = {}, onDismiss = {}) }
}

// endregion
