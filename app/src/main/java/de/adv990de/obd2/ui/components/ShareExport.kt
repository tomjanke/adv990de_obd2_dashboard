package de.adv990de.obd2.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Der Typ, mit dem eine Exportdatei geteilt wird: `.zip` (die Pakete) als `application/zip`, alles andere als CSV. */
internal fun shareMimeType(fileName: String): String =
    if (fileName.endsWith(".zip", ignoreCase = true)) "application/zip" else "text/csv"

/**
 * Oeffnet das Teilen-Menue des Systems fuer die Exportdatei [uri] (CSV oder Paket). Die Datei kommt vom FileProvider der App, der Empfaenger bekommt Leserecht darauf; der Typ
 * folgt dem Dateinamen am Ende des Pfads. Liefert `false`, wenn keine App die Datei annehmen kann — der Aufrufer sagt das dann in einer Snackbar.
 */
internal fun shareExport(context: Context, uri: Uri, chooserTitle: String): Boolean {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = shareMimeType(uri.lastPathSegment.orEmpty())
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
