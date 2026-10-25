package de.adv990de.obd2.ui.trips

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.dtc.DtcScreen
import de.adv990de.obd2.ui.history.HistoryScreen
import de.adv990de.obd2.ui.logbook.LogbookScreen
import de.adv990de.obd2.ui.theme.PreviewTheme

/**
 * Die vier Bereiche unter dem Menuepunkt "Fahrten": die Fahrten, die zwei Ansichten der Fehlercodes — was das Fahrzeug jetzt meldet ("Aktuell") und was es frueher
 * gemeldet hat ("Historie") — und das Logbuch (seit 1.42.0). "Aktuell" und "Historie" sind die Ansichten der DTC-Seite, ihre Texte bleiben.
 */
internal enum class TripsSection(@StringRes val labelRes: Int) {
    Trips(R.string.trips_section_trips),
    Current(R.string.dtc_tab_current),
    History(R.string.dtc_tab_history),
    Logbook(R.string.trips_section_logbook),
    ;

    /** Zeigt die Fehlerhistorie (lokale Aufzeichnung) statt der Live-Liste. */
    val showsFaultHistory: Boolean get() = this == History

    /** Gehoert zur DTC-Seite (Fehlercodes): "Aktuell" und "Historie", nicht die Fahrten und nicht das Logbuch. */
    val isFaultCodes: Boolean get() = this == Current || this == History
}

/** Glove-friendly touch size of the segments (CLAUDE.md design principle 4). */
private val SegmentMinHeight = 56.dp

/** Test tag of one segment of the switch (`trips_section_Trips`, …). */
internal fun tripsSectionTestTag(section: TripsSection) = "trips_section_${section.name}"

/**
 * Der Menuepunkt "Fahrten" (seit 1.33.0): die Fahrtenliste, die Fehlercodes (seit 1.42.0 auch das Logbuch) unter EINEM Umschalter statt mehrerer Reiter.
 * Die beiden Seiten behalten ihr Scaffold, ihre Top-Bar (Titel je Bereich: "Fahrtenverlauf" bzw. "Fehlercodes (DTCs)") und ihre Snackbar;
 * der Umschalter kommt als Slot in ihren Inhalt. Ein Umschalter statt zweier verschachtelter: im Querformat, das der Nutzer ueberwiegend
 * nutzt, ist die Hoehe knapp.
 *
 * Der Bereich ist gespeichert (`rememberSaveable`): eine Drehung und die Rueckkehr aus dem Freeze-Frame zeigen denselben Bereich. Beim
 * Wechsel zwischen den Bereichen gilt, was heute beim Wechsel zwischen Reitern gilt: die ViewModels der Seiten bleiben, eine offene Loeschung
 * (Rueckgaengig-Fenster) laeuft bis zu ihrem Ende weiter, auch wenn die Snackbar mit ihrem Scaffold verschwindet.
 *
 * [initialSection] ist nur der Startwert (Tests, die direkt eine Ansicht der DTC-Seite brauchen).
 */
@Composable
internal fun TripsAndFaultsScreen(navController: NavController, initialSection: TripsSection = TripsSection.Trips) {
    var section by rememberSaveable { mutableStateOf(initialSection) }
    val switch: @Composable () -> Unit = { SectionSwitch(selected = section, onSelect = { section = it }) }

    // EIN Aufrufpunkt fuer die DTC-Seite: ein Wechsel zwischen "Aktuell" und "Historie" baut sie nicht neu auf (sie liest beim Betreten vom
    // Fahrzeug); zwei Zweige liessen den LaunchedEffect bei jedem Wechsel erneut laufen.
    when {
        section == TripsSection.Trips -> HistoryScreen(navController = navController, header = switch)
        section == TripsSection.Logbook -> LogbookScreen(navController = navController, header = switch)
        else -> DtcScreen(navController = navController, showHistory = section.showsFaultHistory, header = switch)
    }
}

/** "Fahrten | Aktuell | Historie | Logbuch" — Segmente ≥ 56 dp, die Mindesthoehe sitzt an JEDEM Segment (die Zeile allein dehnt sie nicht). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SectionSwitch(selected: TripsSection, onSelect: (TripsSection) -> Unit) {
    val sections = TripsSection.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        sections.forEachIndexed { index, section ->
            SegmentedButton(
                selected = section == selected,
                onClick = { onSelect(section) },
                shape = SegmentedButtonDefaults.itemShape(index, sections.size),
                modifier = Modifier.heightIn(min = SegmentMinHeight).testTag(tripsSectionTestTag(section)),
            ) {
                Text(stringResource(section.labelRes))
            }
        }
    }
}

// region — Previews
//
// TripsAndFaultsScreen selbst braucht `hiltViewModel()` (beide Seiten); der Umschalter ist zustandslos.

@Preview(name = "SectionSwitch · trips (light)", showBackground = true)
@Composable
private fun PreviewSectionSwitchLight() {
    PreviewTheme(dark = false) { SectionSwitch(selected = TripsSection.Trips, onSelect = {}) }
}

@Preview(name = "SectionSwitch · fault history (dark)", showBackground = true)
@Composable
private fun PreviewSectionSwitchDark() {
    PreviewTheme(dark = true) { SectionSwitch(selected = TripsSection.History, onSelect = {}) }
}

// endregion
