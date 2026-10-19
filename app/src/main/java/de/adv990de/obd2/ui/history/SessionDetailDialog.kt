package de.adv990de.obd2.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import de.adv990de.obd2.R
import de.adv990de.obd2.data.db.entity.SessionEntity
import de.adv990de.obd2.data.MinMax
import de.adv990de.obd2.data.SessionStats
import de.adv990de.obd2.data.decodeSessionDtcs
import de.adv990de.obd2.obd.VinDecoder
import de.adv990de.obd2.ui.components.SectionLabel
import de.adv990de.obd2.ui.dtc.DtcRow
import de.adv990de.obd2.ui.formatDateTime
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.ProvideMinTouchTarget

/** Glove-friendly touch height of the dialog button (CLAUDE.md design principle 4); the M3 default of a dialog `TextButton` is only 40 dp. */
private val DialogButtonMinHeight = 56.dp

/** Mindestbreite des Dialoginhalts (s. [SessionDetailDialog]). */
private val DialogContentMinWidth = 400.dp

/** Rand zwischen Dialoginhalt und Fensterkante: je Seite 24 dp Fensterrand und 24 dp Dialogpolster. */
private val DialogSideMargins = 96.dp

/**
 * Die Mindestbreite des Dialoginhalts bei einer Fensterbreite: [DialogContentMinWidth], in einem schmalen Fenster der verfuegbare Platz
 * abzueglich der Raender ([DialogSideMargins]) — nie negativ.
 */
internal fun detailContentMinWidth(windowWidth: Dp): Dp = minOf(DialogContentMinWidth, (windowWidth - DialogSideMargins).coerceAtLeast(0.dp))

/** Test tag of the scrollable body of the details dialog. */
internal const val SESSION_DETAIL_TEST_TAG = "session_detail"

/**
 * Die Details einer Fahrt: der Beginn als Titel, die Fehlercodes, die das Fahrzeug bei Fahrtbeginn meldete — jeder mit Beschreibung
 * (dieselbe Zeile wie auf der DTC-Seite, [DtcRow]) —, davor in einer gedaempften Zeile Ende und FIN. Drei Zustaende der Codes: eine Liste · "gelesen, keine gefunden" · "nicht erfasst"
 * (Fahrt vor 1.33.0, oder das Auslesen scheiterte) — ein "keine Fehler" darf nie fuer ein "nicht erfasst" stehen. Darunter die Messwerte der Fahrt ([SessionStatsSection], F11):
 * kleinster und groesster Wert von Kuehlmittel und Spannung, aus den aufgezeichneten Werten gerechnet; [stats] liefert der Aufrufer (der Dialog liest nichts selbst).
 *
 * Ein einfacher [AlertDialog], bewusst keine eigene Seite und kein Bottom Sheet (CLAUDE.md "Verbotene Patterns"): ohne Diagramme waechst
 * die Ansicht nicht, und es braucht keine Routen-Argumente. Der Dialog ist ein eigenes Fenster, die 56-dp-Regel aus `AppTheme` gilt dort
 * nur, wenn sie im Slot erneut gesetzt wird ([ProvideMinTouchTarget]).
 */
@Composable
internal fun SessionDetailDialog(
    session: SessionEntity,
    isRecording: Boolean,
    stats: SessionStatsState,
    useImperial: Boolean,
    onDismiss: () -> Unit,
) {
    val dtcs = remember(session.dtcCodes) { decodeSessionDtcs(session.dtcCodes) }
    // Breite: das Dialogfenster der Plattform ist im Querformat nur ~320 dp breit (am Pixel 8 Pro gesehen), das brach die Beschreibung eines
    // Codes auf drei Zeilen und die Zeile mit Ende und FIN auf zwei und schob den zweiten Code unter den Rand. Ohne die Plattformbreite
    // bestimmt der Inhalt die Breite: mindestens DialogContentMinWidth, in einem schmalen Fenster der verfuegbare Platz (detailContentMinWidth).
    // Der Dialog bleibt durch AlertDialog auf 560 dp begrenzt. Die Fensterbreite wird hier gelesen, in der Komposition des Aufrufers (dem Fenster der
    // Activity) und nicht im Slot des Dialogs: dort waere es das Fenster des Dialogs selbst, dessen Groesse von dieser Breite abhaengt.
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val contentMinWidth = detailContentMinWidth(windowWidth)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        // Der Beginn der Fahrt ist der Titel: er sagt, welche Fahrt es ist, und steht auch bei wenig Hoehe fest sichtbar.
        title = { Text(formatDateTime(session.startTime)) },
        text = {
            Column(Modifier.widthIn(min = contentMinWidth).verticalScroll(rememberScrollState()).testTag(SESSION_DETAIL_TEST_TAG)) {
                // Ende und FIN in EINER Zeile: der Dialog ist im Querformat nur ~320 dp hoch, zwei beschriftete Felder schoeben die Codes
                // unter den Rand (am Pixel 8 Pro gesehen). Die Zeile ist gedaempft, die Codes sind der Grund fuer den Dialog.
                val ended = when {
                    // Die laufende Aufzeichnung hat kein Ende; eine Fahrt ohne Ende, die nicht mehr aufgezeichnet wird, endete unsauber (Prozess beendet).
                    isRecording -> stringResource(R.string.history_recording_active)
                    session.endTime != null -> formatDateTime(session.endTime)
                    else -> stringResource(R.string.history_detail_ended_unknown)
                }
                // Eigene Zeichenkette ohne FIN: der Platzhalter "FIN unbekannt" traegt das Wort schon, hinter der Beschriftung stuende "FIN: FIN unbekannt"
                // (ein Motorrad, das keine FIN meldet, ist der Normalfall).
                val vin = session.vehicleVin?.takeIf(VinDecoder::isValidVin)
                Text(
                    if (vin != null) stringResource(R.string.history_detail_meta, ended, vin) else stringResource(R.string.history_detail_meta_no_vin, ended),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                SectionLabel(stringResource(R.string.history_detail_dtcs_heading))
                Spacer(Modifier.height(4.dp))
                when {
                    dtcs == null -> Text(stringResource(R.string.history_detail_dtcs_unknown), style = MaterialTheme.typography.bodyMedium)
                    dtcs.isEmpty() -> Text(stringResource(R.string.history_detail_dtcs_none), style = MaterialTheme.typography.bodyMedium)
                    else -> dtcs.forEach { DtcRow(it, containerColor = Color.Transparent) }
                }
                // Messwerte (F11) hinter den Codes: im Querformat ist die Hoehe knapp, die Codes sind der Grund fuer den Dialog; der Block ist erreichbar, weil der Inhalt scrollt.
                Spacer(Modifier.height(12.dp))
                SessionStatsSection(stats, useImperial)
            }
        },
        confirmButton = {
            ProvideMinTouchTarget {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = DialogButtonMinHeight)) {
                    Text(stringResource(R.string.close))
                }
            }
        },
    )
}

// region — Previews
//
// Ein Dialog ist ein eigenes Fenster: die Vorschau zeigt ihn in beiden Flaechen mit gemischten Codes (bestaetigt und ausstehend).

private val PREVIEW_TRIP = SessionEntity(
    id = 8,
    startTime = 1_791_000_000_000L,
    endTime = 1_791_003_600_000L,
    vehicleVin = "WP0ZZZ99ZTS392124",
    dtcCodes = "P0301,P0420?",
)

private val PREVIEW_TRIP_STATS = SessionStatsState.Ready(SessionStats(coolant = MinMax(14f, 93f), voltage = MinMax(12.1f, 14.3f)))

@Preview(name = "SessionDetailDialog · light", showBackground = true)
@Composable
private fun PreviewSessionDetailDialogLight() {
    PreviewTheme(dark = false) { SessionDetailDialog(PREVIEW_TRIP, isRecording = false, stats = PREVIEW_TRIP_STATS, useImperial = false, onDismiss = {}) }
}

@Preview(name = "SessionDetailDialog · dark", showBackground = true)
@Composable
private fun PreviewSessionDetailDialogDark() {
    PreviewTheme(dark = true) { SessionDetailDialog(PREVIEW_TRIP, isRecording = false, stats = PREVIEW_TRIP_STATS, useImperial = false, onDismiss = {}) }
}

// endregion
