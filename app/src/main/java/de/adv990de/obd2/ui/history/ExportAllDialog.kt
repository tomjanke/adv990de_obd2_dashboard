package de.adv990de.obd2.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.components.ExportChoiceDialog
import de.adv990de.obd2.ui.theme.PreviewTheme

/** Test tags of the two ways to export. */
internal const val EXPORT_ALL_SHARE_TEST_TAG = "export_all_share"
internal const val EXPORT_ALL_SAVE_TEST_TAG = "export_all_save"

/**
 * "Alle Fahrten exportieren": alle abgeschlossenen Fahrten in EINER CSV-Datei, entweder ueber das Teilen-Menue ([onShare]) oder in eine Datei,
 * die der Nutzer mit dem Dateidialog des Systems waehlt ([onSave]). Der Dialog selbst ist [ExportChoiceDialog], geteilt mit dem Export des App-Logs.
 */
@Composable
internal fun ExportAllDialog(tripCount: Int, onShare: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    ExportChoiceDialog(
        title = stringResource(R.string.history_export_all_title),
        message = pluralStringResource(R.plurals.history_export_all_message, tripCount, tripCount),
        onShare = onShare,
        onSave = onSave,
        onDismiss = onDismiss,
        saveTag = EXPORT_ALL_SAVE_TEST_TAG,
        shareTag = EXPORT_ALL_SHARE_TEST_TAG,
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
