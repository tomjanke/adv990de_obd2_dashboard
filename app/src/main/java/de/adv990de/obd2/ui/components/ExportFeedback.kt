package de.adv990de.obd2.ui.components

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Die Rueckmeldung eines CSV-Exports an die Oberflaeche — der gemeinsame Teil von `HistoryViewModel`, `AppLogsViewModel` und `LogbookViewModel`. Das ViewModel startet den Export
 * ([launchShare], [launchSave]), die Oberflaeche zeigt, was herauskam ([ExportFeedbackEffects]) und quittiert es mit den `consume…`-Funktionen: Teilen-Menue, Fehler und „nichts zu
 * exportieren“ sind je ein Zustand, der genau einmal angezeigt wird. Was „gespeichert“ meldet (eine Zahl bei den Fahrten, ein Ja bei App-Log und Logbuch), bleibt Sache des ViewModels
 * ([launchSave] ruft dafuer `onSaved`).
 *
 * Die Regeln stehen hier einmal: ein Abbruch ist nie ein Fehler (die `CancellationException` geht durch), jeder andere Fehler hat einen sichtbaren Text — die Meldung der Ausnahme,
 * ohne Meldung ihr Klassenname, nie leer —, und ein neuer Versuch loescht den alten Fehler.
 */
class ExportFeedback {

    private val _shareUri = MutableStateFlow<Uri?>(null)

    /** Nicht null, sobald „Teilen“ die Datei geschrieben hat: die Oberflaeche reicht sie dem Teilen-Menue und ruft [consumeShareUri]. */
    val shareUri: StateFlow<Uri?> = _shareUri.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Der Text des letzten Fehlers (die Meldung der Ausnahme, sonst ihr Klassenname), bis die Oberflaeche [consumeError] ruft oder ein neuer Versuch beginnt. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _nothingToExport = MutableStateFlow(false)

    /** Wahr, wenn exportiert werden sollte, aber nichts mehr zu exportieren ist (es wurde inzwischen geloescht oder ausgeblendet) — die Oberflaeche sagt es. */
    val nothingToExport: StateFlow<Boolean> = _nothingToExport.asStateFlow()

    /** Meldet „nichts zu exportieren“; das ViewModel prueft das vor dem Start, damit kein leerer Export entsteht. */
    fun reportNothingToExport() {
        _nothingToExport.value = true
    }

    /** „Teilen“: [export] schreibt die Datei und liefert ihre [Uri]; bei Erfolg steht sie in [shareUri]. */
    fun launchShare(scope: CoroutineScope, export: suspend () -> Uri) = launchGuarded(scope) { _shareUri.value = export() }

    /** „Speichern“: [export] schreibt in die gewaehlte Datei; erst danach, bei Erfolg, kommt [onSaved]. */
    fun launchSave(scope: CoroutineScope, onSaved: () -> Unit, export: suspend () -> Unit) = launchGuarded(scope) {
        export()
        onSaved()
    }

    fun consumeShareUri() {
        _shareUri.value = null
    }

    fun consumeError() {
        _error.value = null
    }

    fun consumeNothingToExport() {
        _nothingToExport.value = false
    }

    private fun launchGuarded(scope: CoroutineScope, block: suspend () -> Unit) {
        scope.launch {
            _error.value = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
            }
        }
    }
}
