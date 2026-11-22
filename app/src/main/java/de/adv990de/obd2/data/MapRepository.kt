package de.adv990de.obd2.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import de.adv990de.obd2.di.ApplicationScope
import de.adv990de.obd2.map.MapFileStore
import de.adv990de.obd2.map.MapImportResult
import de.adv990de.obd2.map.MapState
import de.adv990de.obd2.map.PmTilesArchive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Die Huelle der eingespielten Karte fuer die App: [state] sagt, ob und welche Karte da ist, [import] spielt eine Datei ein (aus einer `content://`-Uri des Dateidialogs), [remove] loescht sie,
 * [archive] gibt den Leser fuer den Zeichner. Die Datei liegt im App-Ordner (`files/map/map.pmtiles`) und ist **nicht** im Sicherungspaket (sie ist gross und laesst sich neu einspielen).
 * Die Regeln der Datei stehen in [MapFileStore]; hier ist nur Android: Uri, Groessenangabe, App-Log, Zustand.
 *
 * **Im App-Log steht nur die Art des Problems, nie ein Dateiname und nie eine Koordinate.**
 */
@Singleton
class MapRepository internal constructor(
    private val store: MapFileStore,
    scope: CoroutineScope,
    private val log: suspend (LogLevel, String) -> Unit,
    private val openStream: (Uri) -> InputStream?,
    private val sizeOf: (Uri) -> Long?,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        appLog: AppLogRepository,
    ) : this(
        store = MapFileStore(File(context.filesDir, "map")),
        scope = scope,
        log = { level, message -> appLog.log(level, message) },
        openStream = { context.contentResolver.openInputStream(it) },
        sizeOf = { uri ->
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it >= 0 } else null
                }
            } catch (_: Exception) {
                null
            }
        },
    )

    private val _state = MutableStateFlow<MapState>(MapState.Loading)

    /** Zuerst [MapState.Loading], dann was im App-Ordner liegt; folgt jedem Einspielen und Entfernen. */
    val state: StateFlow<MapState> = _state.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    /** Liest erneut, was im App-Ordner liegt. */
    suspend fun refresh() {
        _state.value = store.inspect()
    }

    /** Spielt die Datei hinter [uri] ein. Eine kaputte oder falsche Datei laesst die bisherige Karte stehen; der Fehler steht im App-Log (nur seine Art). */
    suspend fun import(uri: Uri): MapImportResult = importFrom({ openStream(uri) }, sizeOf(uri))

    internal suspend fun importFrom(open: () -> InputStream?, knownSize: Long?): MapImportResult {
        val result = store.import(open, knownSize)
        when (result) {
            is MapImportResult.Success -> _state.value = MapState.Ready(result.info)
            // Eine gescheiterte Datei laesst die bisherige Karte stehen (MapFileStore); der Zustand bleibt, wie er ist.
            is MapImportResult.Failure -> log(LogLevel.WARN, "Map import failed: ${result.problem}")
        }
        return result
    }

    /** Loescht die Karte. */
    suspend fun remove() {
        store.remove()
        refresh()
    }

    /** Der Leser der Karte oder `null` (keine Karte oder eine, die nicht taugt). */
    suspend fun archive(): PmTilesArchive? = store.archive()
}
