package de.adv990de.obd2.ui.logbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.logbook.LogEntry
import de.adv990de.obd2.logbook.LogFilter
import de.adv990de.obd2.logbook.LogType
import de.adv990de.obd2.ui.components.AppIcons
import de.adv990de.obd2.ui.components.EmptyState
import de.adv990de.obd2.ui.components.SectionLabel
import de.adv990de.obd2.ui.components.SwipeToDeleteRow
import de.adv990de.obd2.ui.components.UndoSnackbarEffect
import de.adv990de.obd2.transfer.DataPart
import de.adv990de.obd2.ui.navigation.Screen
import de.adv990de.obd2.ui.transfer.TransferMode
import de.adv990de.obd2.ui.theme.PreviewTheme
import java.time.LocalDate
import java.util.Locale

/** Glove-friendly touch size of the row and top-bar buttons (CLAUDE.md design principle 4). */
private val RowButtonSize = 56.dp

/** Platz unter der letzten Zeile, damit der Knopf "Eintrag" sie nicht verdeckt. */
private val FabClearance = 88.dp

/** Test tags. */
internal const val LOGBOOK_ADD_TEST_TAG = "logbook_add"
internal const val LOGBOOK_EXPORT_TEST_TAG = "logbook_export"
internal const val LOGBOOK_IMPORT_TEST_TAG = "logbook_import"
internal const val LOGBOOK_LIST_TEST_TAG = "logbook_list"
internal fun logbookRowTestTag(id: Long) = "logbook_row_$id"

/** Schluessel der unsichtbaren Marke ganz oben in der Liste (s. [LogbookScreen]); die Eintraege haben Long-Schluessel, die Marke und die Abschnitte einen Text. */
private const val LIST_TOP_ANCHOR_KEY = "list_top_anchor"
private const val OVERVIEW_KEY = "overview"
private const val STATS_KEY = "stats"
private const val ENTRIES_HEADER_KEY = "entries_header"
private const val EMPTY_FILTER_KEY = "empty_filter"

/** Hoehe des Hinweises "Keine Eintraege dieser Art" in der Liste (der Rest der Seite ist die Auswertung darueber). */
private val EmptyFilterHeight = 160.dp
internal fun logbookFilterTestTag(filter: LogFilter) = "logbook_filter_${filter.name}"

/**
 * Das Logbuch (vierter Bereich von "Fahrten"): Kopfleiste mit den Abkuerzungen fuer Export und Import, Filter, die Liste (neueste zuerst) und der Knopf "Eintrag". Eine Zeile oeffnet den
 * Eintrag zum Bearbeiten; Loeschen nach dem Loesch-Vertrag (Papierkorb oder Wischen, ohne Dialog, nur Rueckgaengig). Eigenes Scaffold wie die Fahrtenliste; der Umschalter
 * des Hosts steht als [header] ueber der Liste.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogbookScreen(
    navController: NavController,
    header: @Composable () -> Unit = {},
    vm: LogbookViewModel = hiltViewModel(),
) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val insights by vm.insights.collectAsStateWithLifecycle()
    val maintenance by vm.maintenance.collectAsStateWithLifecycle()
    val hasEntries by vm.hasEntries.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val undoBatchId by vm.undoBatchId.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // Der Dialog "Intervall festlegen" einer Wartungsart (Tipp auf eine Zelle der Uebersicht); der Name der Art ueberlebt eine Drehung.
    var intervalDialogFor by rememberSaveable { mutableStateOf<String?>(null) }

    // Der Loesch-Vertrag: Papierkorb oder Wischen -> der Eintrag verschwindet sofort -> "Rueckgaengig" so lange wie das Fenster -> erst dann wirklich geloescht.
    UndoSnackbarEffect(
        batchId = undoBatchId,
        message = stringResource(R.string.logbook_deleted),
        undoLabel = stringResource(R.string.undo),
        snackbarHostState = snackbarHostState,
        onUndo = vm::undoDelete,
    )

    intervalDialogFor?.let { name ->
        val type = LogType.entries.firstOrNull { it.name == name }
        if (type == null) {
            intervalDialogFor = null
        } else {
            MaintenanceIntervalDialog(
                type = type,
                initialKm = maintenance.rows.firstOrNull { it.item.type == type }?.intervalKm,
                onSave = { km ->
                    vm.setInterval(type, km)
                    intervalDialogFor = null
                },
                onDismiss = { intervalDialogFor = null },
            )
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logbook_title)) },
                actions = {
                    // Abkuerzungen auf die Seite „Daten sichern und wiederherstellen“: Exportieren mit dem Logbuch vorgewaehlt (CSV; ohne sichtbaren Eintrag gibt es nichts zu exportieren),
                    // Importieren mit dem Dateidialog gleich offen (immer moeglich).
                    IconButton(
                        onClick = { navController.navigate(Screen.DataTransfer.routeFor(TransferMode.EXPORT, DataPart.LOGBOOK)) },
                        enabled = hasEntries,
                        modifier = Modifier.size(RowButtonSize).testTag(LOGBOOK_EXPORT_TEST_TAG),
                    ) {
                        Icon(AppIcons.Download, contentDescription = stringResource(R.string.logbook_cd_export))
                    }
                    IconButton(
                        onClick = { navController.navigate(Screen.DataTransfer.routeFor(TransferMode.IMPORT, pick = true)) },
                        modifier = Modifier.size(RowButtonSize).testTag(LOGBOOK_IMPORT_TEST_TAG),
                    ) {
                        Icon(AppIcons.Upload, contentDescription = stringResource(R.string.logbook_cd_import))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { navController.navigate(Screen.LogEntry.routeFor(0)) },
                icon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.logbook_cd_add)) },
                text = { Text(stringResource(R.string.logbook_add)) },
                modifier = Modifier.testTag(LOGBOOK_ADD_TEST_TAG),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            header()
            LogbookFilterRow(selected = filter, onSelect = vm::setFilter)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!hasEntries) {
                    EmptyState(icon = AppIcons.Book, text = stringResource(R.string.logbook_empty))
                } else {
                    val sections = insightSectionsFor(filter)
                    val locale = LocalLocale.current.platformLocale
                    LazyColumn(Modifier.testTag(LOGBOOK_LIST_TEST_TAG), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = FabClearance)) {
                        // Eine Liste mit Schluesseln verankert sich am obersten sichtbaren Eintrag: ein neuer neuester Eintrag (Import, "Rueckgaengig" beim Loeschen des
                        // neuesten) laege sonst verdeckt oberhalb. Diese Marke ist 1 dp hoch (bei 0 dp zaehlt Compose sie nicht als sichtbar und verankert sich wieder am ersten Eintrag) und bleibt der Anker, solange die Liste ganz oben steht — Neues erscheint darunter.
                        item(key = LIST_TOP_ANCHOR_KEY) { Spacer(Modifier.height(1.dp)) }
                        // Was zum Filter ausgewertet wird (s. insightSectionsFor): die Uebersicht der Wartung und/oder die Karten, jeweils ueber den Eintraegen.
                        if (sections.overview) item(key = OVERVIEW_KEY) { LogbookOverview(maintenance, onEditInterval = { intervalDialogFor = it.name }) }
                        if (sections.cards.isNotEmpty()) {
                            item(key = STATS_KEY) {
                                LogbookStatsCards(sections.cards, insights, onOpen = { navController.navigate(Screen.LogbookStats.routeFor(it)) })
                            }
                        }
                        item(key = ENTRIES_HEADER_KEY) {
                            SectionLabel(stringResource(R.string.logbook_section_entries), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                        if (entries.isEmpty()) {
                            item(key = EMPTY_FILTER_KEY) {
                                Box(Modifier.fillMaxWidth().height(EmptyFilterHeight)) {
                                    EmptyState(icon = AppIcons.Book, text = stringResource(R.string.logbook_empty_filter))
                                }
                            }
                        }
                        items(entries, key = { it.id }) { entry ->
                            // Das Wischen ist nur eine Ergaenzung zum Papierkorb-Knopf.
                            SwipeToDeleteRow(onDelete = { vm.delete(entry.id) }, modifier = Modifier.testTag(logbookRowTestTag(entry.id))) {
                                LogbookRow(
                                    entry = entry,
                                    onOpen = { navController.navigate(Screen.LogEntry.routeFor(entry.id)) },
                                    onDelete = { vm.delete(entry.id) },
                                    consumption = insights.consumption[entry.id]?.let { consumptionText(it, locale) },
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

/** Die Filter als Chips in einer Zeile, die bei schmalem Fenster waagerecht scrollt; jeder Chip >= 56 dp hoch. */
@Composable
internal fun LogbookFilterRow(selected: LogFilter, onSelect: (LogFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LogFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(filter.labelRes())) },
                modifier = Modifier.heightIn(min = RowButtonSize).testTag(logbookFilterTestTag(filter)),
            )
        }
    }
}

/** Eine Zeile des Logbuchs: Art (bei Tanken, Oel und Kuehlfluessigkeit mit Menge), Datum und Stand, darunter der abgeleitete Verbrauch ([consumption], falls der Eintrag ein Intervall schliesst), Kosten, Teiltankung, Fabrikat oder Notiz. */
@Composable
internal fun LogbookRow(entry: LogEntry, onOpen: () -> Unit, onDelete: () -> Unit, consumption: String? = null) {
    val locale = LocalLocale.current.platformLocale
    val label = stringResource(entry.type.labelRes())
    val amount = entryAmountText(entry, locale)
    val headline = if (amount != null) stringResource(R.string.logbook_row_join, label, amount) else label
    val dateAndStand = stringResource(
        R.string.logbook_row_join,
        formatLogDate(entry.date, locale),
        stringResource(R.string.logbook_fmt_km, formatOdometer(entry.odometerKm, locale)),
    )
    val extras = buildList {
        consumption?.let { add(it) }
        entry.costCents?.let { add(stringResource(R.string.logbook_fmt_euro, formatEuros(it, locale))) }
        if (entry.type == LogType.FUEL && entry.fullTank == false) add(stringResource(R.string.logbook_row_partial))
        entry.brand?.let { add(it) }
        entry.note?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    ListItem(
        // Die ganze Zeile oeffnet den Eintrag; Loeschen bleibt ein eigener Knopf.
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.logbook_cd_open), onClick = onOpen),
        headlineContent = { Text(headline) },
        supportingContent = {
            Column {
                Text(dateAndStand)
                if (extras.isNotEmpty()) {
                    Text(extras.reduce { a, b -> stringResource(R.string.logbook_row_join, a, b) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete, modifier = Modifier.size(RowButtonSize)) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.logbook_cd_delete))
            }
        },
    )
}

/** Die Menge eines Eintrags mit Einheit (Liter beim Tanken, Milliliter bei Oel und Kuehlfluessigkeit), `null` bei den Arten ohne Menge. */
@Composable
internal fun entryAmountText(entry: LogEntry, locale: Locale): String? {
    val ml = entry.amountMl ?: return null
    return when (entry.type) {
        LogType.FUEL -> stringResource(R.string.logbook_fmt_liters, formatLiters(ml, locale))
        LogType.OIL, LogType.COOLANT -> stringResource(R.string.logbook_fmt_ml, formatMl(ml, locale))
        else -> null
    }
}

// region — Previews
//
// LogbookScreen selbst braucht `hiltViewModel()` (und ein Scaffold mit Dateidialogen); Filter und Zeile sind zustandslos.

private fun previewEntry(id: Long, type: LogType, km: Int, amountMl: Int? = null, full: Boolean? = null, cost: Int? = null, note: String? = null, brand: String? = null) =
    LogEntry(id, type, LocalDate.of(2026, 10, 2), km, amountMl, full, cost, note, brand)

@Composable
private fun LogbookRowsPreviewContent() {
    Column {
        LogbookFilterRow(selected = LogFilter.All, onSelect = {})
        LogbookRow(previewEntry(1, LogType.FUEL, 40_362, 14_370, true, 2_743), onOpen = {}, onDelete = {}, consumption = "7,9 l/100 km")
        LogbookRow(previewEntry(2, LogType.FUEL, 40_100, 5_000, false, note = "Reserve, kurz vor Hamburg und dann noch ein sehr langer Text, der abgeschnitten wird"), onOpen = {}, onDelete = {})
        LogbookRow(previewEntry(3, LogType.OIL, 40_100, 150), onOpen = {}, onDelete = {}, consumption = "361 ml/1000 km")
        LogbookRow(previewEntry(4, LogType.TIRE_CHANGE, 37_000, brand = "Michelin Anakee Adventure 150/70-18"), onOpen = {}, onDelete = {})
        LogbookRow(previewEntry(5, LogType.CHAIN_KIT_CHANGE, 36_000), onOpen = {}, onDelete = {})
    }
}

@Preview(name = "Logbook rows (light)", showBackground = true)
@Composable
private fun PreviewLogbookRowsLight() {
    PreviewTheme(dark = false) { LogbookRowsPreviewContent() }
}

@Preview(name = "Logbook rows (dark)", showBackground = true)
@Composable
private fun PreviewLogbookRowsDark() {
    PreviewTheme(dark = true) { LogbookRowsPreviewContent() }
}

// endregion
