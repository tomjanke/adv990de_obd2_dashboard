package de.adv990de.obd2.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.components.ExportChoiceDialog
import de.adv990de.obd2.ui.theme.PreviewTheme

/** Test tags of the two ways to export the log. */
internal const val APP_LOG_EXPORT_SHARE_TEST_TAG = "app_log_export_share"
internal const val APP_LOG_EXPORT_SAVE_TEST_TAG = "app_log_export_save"

/**
 * „Logs exportieren“: der sichtbare App-Log in EINER CSV-Datei, entweder ueber das Teilen-Menue ([onShare]) oder in eine Datei, die der Nutzer mit dem
 * Dateidialog des Systems waehlt ([onSave]). [entryCount] sind die Zeilen, die der Nutzer sieht. Der Text warnt, dass die Datei den Adapternamen und
 * Fehlermeldungen enthaelt — das Teilen reicht sie an eine fremde App weiter. Der Dialog selbst ist [ExportChoiceDialog].
 */
@Composable
internal fun AppLogExportDialog(entryCount: Int, onShare: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    ExportChoiceDialog(
        title = stringResource(R.string.app_logs_export_title),
        message = pluralStringResource(R.plurals.app_logs_export_message, entryCount, entryCount),
        onShare = onShare,
        onSave = onSave,
        onDismiss = onDismiss,
        saveTag = APP_LOG_EXPORT_SAVE_TEST_TAG,
        shareTag = APP_LOG_EXPORT_SHARE_TEST_TAG,
    )
}

// region — Previews
//
// Ein Dialog ist ein eigenes Fenster: die Vorschau zeigt ihn in beiden Flaechen.

@Preview(name = "AppLogExportDialog · light", showBackground = true)
@Composable
private fun PreviewAppLogExportDialogLight() {
    PreviewTheme(dark = false) { AppLogExportDialog(entryCount = 312, onShare = {}, onSave = {}, onDismiss = {}) }
}

@Preview(name = "AppLogExportDialog · dark", showBackground = true)
@Composable
private fun PreviewAppLogExportDialogDark() {
    PreviewTheme(dark = true) { AppLogExportDialog(entryCount = 312, onShare = {}, onSave = {}, onDismiss = {}) }
}

// endregion
