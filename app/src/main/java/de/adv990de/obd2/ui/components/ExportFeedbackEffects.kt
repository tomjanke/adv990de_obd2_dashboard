package de.adv990de.obd2.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.adv990de.obd2.R

/**
 * Zeigt, was ein Export gebracht hat — der gemeinsame Teil von `HistoryScreen`, `AppLogsScreen` und `LogbookScreen`: das Teilen-Menue ([shareCsv]; nimmt keine App die Datei an, sagt es
 * eine Snackbar), die Meldung „gespeichert“, „nichts zu exportieren“ und einen Fehler. Jeder Zustand wird einmal gezeigt und dann am [feedback] quittiert; die Snackbars stehen
 * hintereinander in der Reihenfolge der Effekte.
 *
 * [savedMessage] ist der Text zu „gespeichert“ oder null, solange nichts zu melden ist (der Aufrufer loest ihn auf: bei den Fahrten mit der Zahl, sonst ein fester Text); nach der Anzeige ruft
 * dieser Baustein [onSavedShown]. Die Texte werden hier auf Composable-Ebene aufgeloest: `context.getString()` in einem Effekt umginge die Recomposition bei einem Sprachwechsel.
 * Der Fehlertext traegt den Praefix aus `history_export_error_prefix`, der „keine App“-Text ist `history_share_no_app` — beide gehoeren allen drei Exporten.
 */
@Composable
fun ExportFeedbackEffects(
    feedback: ExportFeedback,
    snackbarHostState: SnackbarHostState,
    shareChooserTitle: String,
    nothingToExportMessage: String,
    savedMessage: String?,
    onSavedShown: () -> Unit,
) {
    val context = LocalContext.current
    val shareUri by feedback.shareUri.collectAsStateWithLifecycle()
    val error by feedback.error.collectAsStateWithLifecycle()
    val nothingToExport by feedback.nothingToExport.collectAsStateWithLifecycle()
    val shareNoAppMessage = stringResource(R.string.history_share_no_app)
    val errorMessage = error?.let { stringResource(R.string.history_export_error_prefix, it) }

    LaunchedEffect(shareUri) {
        shareUri?.let { uri ->
            if (!shareCsv(context, uri, shareChooserTitle)) snackbarHostState.showSnackbar(shareNoAppMessage)
            feedback.consumeShareUri()
        }
    }

    LaunchedEffect(savedMessage) {
        savedMessage?.let {
            snackbarHostState.showSnackbar(it)
            onSavedShown()
        }
    }

    LaunchedEffect(nothingToExport) {
        if (nothingToExport) {
            snackbarHostState.showSnackbar(nothingToExportMessage)
            feedback.consumeNothingToExport()
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            feedback.consumeError()
        }
    }
}
