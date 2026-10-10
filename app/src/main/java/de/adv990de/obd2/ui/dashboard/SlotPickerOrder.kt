package de.adv990de.obd2.ui.dashboard

import de.adv990de.obd2.obd.model.PidDefinition
import java.text.Collator

/**
 * Sortiert die Auswahl des Slot-Pickers nach dem Namen, den der Nutzer LIEST ([label], die uebersetzte Bezeichnung), nicht nach
 * dem englischen `PidDefinition.name`: Auf einem deutschen Geraet stand die Liste sonst scheinbar ungeordnet (UID-15).
 *
 * [collator] vergleicht sprachgerecht (Standard: die Sprache des Geraets): ein Umlaut steht bei seinem Grundbuchstaben, Gross-
 * und Kleinschreibung entscheiden nichts. Die Sortierung ist stabil — gleiche Namen behalten ihre Reihenfolge. Reine Funktion
 * ohne Compose-Bezug; der Aufrufer loest die Namen in der Komposition auf (`stringResource`).
 */
internal fun sortByDisplayLabel(
    pids: List<PidDefinition>,
    label: (PidDefinition) -> String,
    collator: Comparator<in String> = Collator.getInstance(),
): List<PidDefinition> = pids.sortedWith(compareBy(collator, label))
