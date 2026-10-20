package de.adv990de.obd2.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Oeffnet das Teilen-Menue des Systems fuer die CSV-Datei [uri] (Export der Fahrten und des App-Logs). Die Datei kommt vom FileProvider der App,
 * der Empfaenger bekommt Leserecht darauf. Liefert `false`, wenn keine App die Datei annehmen kann — der Aufrufer sagt das dann in einer
 * Snackbar.
 */
internal fun shareCsv(context: Context, uri: Uri, chooserTitle: String): Boolean {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        context.startActivity(Intent.createChooser(intent, chooserTitle))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
