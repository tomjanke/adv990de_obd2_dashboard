package de.adv990de.obd2.ui.logbook

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.adv990de.obd2.data.VehicleLogRepository
import de.adv990de.obd2.fuel.FuelRepository
import de.adv990de.obd2.logbook.FormCheck
import de.adv990de.obd2.logbook.FormError
import de.adv990de.obd2.logbook.FormField
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogForm
import de.adv990de.obd2.logbook.LogIssue
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.logbook.checkForm
import de.adv990de.obd2.logbook.firstFieldWithError
import de.adv990de.obd2.logbook.formFromEntry
import de.adv990de.obd2.logbook.pricePerLiterCents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import javax.inject.Inject

/** Wie das Speichern ausging: fertig ([Done]) oder fertig, aber die Oberflaeche fragt noch, ob die Tankanzeige auf voll soll ([AskFuelGaugeReset]). */
enum class SaveOutcome { Done, AskFuelGaugeReset }

/** Die Seite soll zu [field] scrollen; [attempt] zaehlt die Speichern-Versuche mit Fehlern, damit derselbe Fehler beim naechsten Versuch wieder eine neue Anfrage ist. */
data class ScrollToError(val field: FormField, val attempt: Int)

/** Der Name des Navigationsarguments mit der Id des Eintrags (`logbook_entry?id={id}`); fehlt es oder ist es nicht positiv, ist der Eintrag neu. */
const val LOG_ENTRY_ID_ARG = "id"

/**
 * Das Formular eines Logbuch-Eintrags (neu oder bearbeiten). Die Texte stehen in [form]; was daraus wird, entscheidet die reine Funktion [checkForm]. Fehler sieht der
 * Nutzer an einem Feld erst, wenn er es angefasst oder das Speichern versucht hat ([visibleErrors]); Warnungen blockieren das Speichern bis zur Bestaetigung
 * ([pendingWarnings]). Nach einer neuen vollen Tankung von heute fragt [outcome] nach der Tankanzeige ([SaveOutcome.AskFuelGaugeReset]) — nie beim Bearbeiten.
 *
 * Der interne Konstruktor nimmt "heute" und das Dezimaltrennzeichen als Parameter (Tests); Hilt benutzt den oeffentlichen, der das Geraet fragt.
 */
@HiltViewModel
class LogEntryViewModel internal constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: VehicleLogRepository,
    private val fuelRepository: FuelRepository,
    private val today: () -> LocalDate,
    private val decimalSeparator: Char,
) : ViewModel() {

    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        repository: VehicleLogRepository,
        fuelRepository: FuelRepository,
    ) : this(savedStateHandle, repository, fuelRepository, { LocalDate.now() }, DecimalFormatSymbols.getInstance().decimalSeparator)

    /** Die Id des bearbeiteten Eintrags, 0 beim neuen. */
    private val entryId: Long = (savedStateHandle.get<Long>(LOG_ENTRY_ID_ARG) ?: 0L).coerceAtLeast(0L)

    val isEditing: Boolean = entryId > 0

    private val _form = MutableStateFlow(LogForm(date = today()))
    val form: StateFlow<LogForm> = _form.asStateFlow()

    private val _loaded = MutableStateFlow(!isEditing)

    /** Wahr, sobald das Formular benutzbar ist: sofort beim neuen Eintrag, beim Bearbeiten nach dem Lesen des Eintrags. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _notFound = MutableStateFlow(false)

    /** Wahr, wenn der Eintrag zum Bearbeiten nicht mehr da ist (zwischenzeitlich geloescht): die Oberflaeche geht zurueck. */
    val notFound: StateFlow<Boolean> = _notFound.asStateFlow()

    /** Der Eintrag mit dem hoechsten Stand — der Hinweis "Letzter Stand: …". */
    private val _lastEntry = MutableStateFlow<LogEntry?>(null)
    val lastEntry: StateFlow<LogEntry?> = _lastEntry.asStateFlow()

    init {
        viewModelScope.launch {
            _lastEntry.value = repository.latestByOdometer()
            if (isEditing) {
                val entry = repository.get(entryId)
                if (entry == null) {
                    _notFound.value = true
                } else {
                    _form.value = formFromEntry(entry, decimalSeparator)
                    _loaded.value = true
                }
            }
        }
    }

    /** Die uebrigen Eintraege (ohne den bearbeiteten) — die Grundlage der Warnungen. */
    private val others: StateFlow<List<LogEntry>> =
        repository.entries.map { all -> all.filter { it.id != entryId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Das gelesene und geprueifte Formular: der Eintrag (wenn alles stimmt), die Fehler je Feld und die Warnungen. */
    val check: StateFlow<FormCheck> =
        combine(_form, others) { form, others -> checkForm(form, entryId, others, today()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), checkForm(_form.value, entryId, emptyList(), today()))

    private val touched = MutableStateFlow<Set<FormField>>(emptySet())
    private val saveAttempted = MutableStateFlow(false)

    /** Die Fehler, die die Oberflaeche zeigt: nur an angefassten Feldern, nach einem Speichern-Versuch an allen. */
    val visibleErrors: StateFlow<Map<FormField, FormError>> =
        combine(check, touched, saveAttempted) { check, touched, attempted ->
            if (attempted) check.errors else check.errors.filterKeys { it in touched }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Preis je Liter in Cent, solange Kosten und Liter lesbar sind (nur beim Tanken). */
    val pricePerLiterCents: StateFlow<Int?> = check.map { c ->
        val form = _form.value
        if (form.type != LogType.FUEL) return@map null
        // Kosten und Menge stehen nur im Eintrag, wenn das ganze Formular stimmt; fuer die Anzeige reicht es, wenn diese zwei lesbar sind.
        val partial = checkForm(form.copy(odometer = "0", note = "", brand = ""), 0, emptyList(), LocalDate.MAX)
        val entry = partial.entry ?: return@map null
        val cost = entry.costCents ?: return@map null
        val amount = entry.amountMl ?: return@map null
        pricePerLiterCents(cost, amount)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ── Eingaben ──

    private fun touch(field: FormField) {
        touched.value = touched.value + field
    }

    /** Beim Bearbeiten ist die Art gesperrt. */
    fun setType(type: LogType) {
        if (!isEditing) _form.value = _form.value.copy(type = type)
    }

    fun setDate(date: LocalDate) { touch(FormField.DATE); _form.value = _form.value.copy(date = date) }
    fun setOdometer(text: String) { touch(FormField.ODOMETER); _form.value = _form.value.copy(odometer = text) }
    fun setAmount(text: String) { touch(FormField.AMOUNT); _form.value = _form.value.copy(amount = text) }
    fun setCost(text: String) { touch(FormField.COST); _form.value = _form.value.copy(cost = text) }
    fun setNote(text: String) { touch(FormField.NOTE); _form.value = _form.value.copy(note = text) }
    fun setBrand(text: String) { touch(FormField.BRAND); _form.value = _form.value.copy(brand = text) }
    fun setFullTank(full: Boolean) { _form.value = _form.value.copy(fullTank = full) }

    // ── Speichern ──

    private val _pendingWarnings = MutableStateFlow<List<LogIssue.Warning>>(emptyList())

    /** Nicht leer, solange die Rueckfrage "Trotzdem speichern?" offen ist. */
    val pendingWarnings: StateFlow<List<LogIssue.Warning>> = _pendingWarnings.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val _outcome = MutableStateFlow<SaveOutcome?>(null)

    /** Nicht null, sobald gespeichert ist: die Oberflaeche geht zurueck ([SaveOutcome.Done]) oder fragt erst nach der Tankanzeige. */
    val outcome: StateFlow<SaveOutcome?> = _outcome.asStateFlow()

    private val _scrollToError = MutableStateFlow<ScrollToError?>(null)
    private var scrollAttempts = 0

    /**
     * Nicht null nach einem Speichern-Versuch mit Fehlern: das oberste Feld mit Fehler. Die Seite scrollt dorthin — im Querformat passt sie nicht auf den Schirm, der Knopf
     * steht unten, der Fehler oben, und ohne das saehe der Nutzer nach dem Tippen auf "Speichern" nichts. Jeder Versuch ist eine **neue** Anfrage (auch fuer dasselbe Feld).
     */
    val scrollToError: StateFlow<ScrollToError?> = _scrollToError.asStateFlow()

    /** Frisch aus der Datenbank geprueft (nicht aus dem Fluss der Anzeige, der nur mit Sammler laeuft). */
    private suspend fun currentCheck(): FormCheck =
        checkForm(_form.value, entryId, repository.entries.first().filter { it.id != entryId }, today())

    /** Speichern: mit Fehlern nur die Fehler zeigen; mit Warnungen erst fragen; sonst schreiben. Waehrend gespeichert wird, zaehlt ein zweiter Aufruf nicht. */
    fun save() {
        if (!_loaded.value || _isSaving.value) return
        saveAttempted.value = true
        viewModelScope.launch {
            val c = currentCheck()
            val entry = c.entry
            if (entry == null) {
                firstFieldWithError(c.errors)?.let { _scrollToError.value = ScrollToError(it, ++scrollAttempts) }
                return@launch
            }
            if (c.warnings.isNotEmpty()) _pendingWarnings.value = c.warnings else write(entry)
        }
    }

    /** "Trotzdem speichern". */
    fun confirmWarnings() {
        if (_isSaving.value) return
        _pendingWarnings.value = emptyList()
        viewModelScope.launch {
            val entry = currentCheck().entry ?: return@launch
            write(entry)
        }
    }

    fun dismissWarnings() {
        _pendingWarnings.value = emptyList()
    }

    private suspend fun write(entry: LogEntry) {
        if (_isSaving.value) return
        _isSaving.value = true
        try {
            repository.save(entry)
            val askGauge = !isEditing && entry.type == LogType.FUEL && entry.fullTank == true && entry.date == today() && fuelRepository.state.value != null
            _outcome.value = if (askGauge) SaveOutcome.AskFuelGaugeReset else SaveOutcome.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _saveError.value = e.message ?: e.javaClass.simpleName
        } finally {
            _isSaving.value = false
        }
    }

    fun consumeSaveError() {
        _saveError.value = null
    }

    /** "Ja": die Tankanzeige auf voll setzen — dann ist alles erledigt. */
    fun resetFuelGauge() {
        fuelRepository.reset()
        _outcome.value = SaveOutcome.Done
    }

    /** "Nein": die Tankanzeige bleibt — dann ist alles erledigt. */
    fun declineFuelGaugeReset() {
        _outcome.value = SaveOutcome.Done
    }
}
