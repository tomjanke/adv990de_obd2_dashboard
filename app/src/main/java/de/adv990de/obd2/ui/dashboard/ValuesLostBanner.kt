package de.adv990de.obd2.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.ui.theme.MIN_TOUCH_TARGET
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.StatusColors

/** Test-only selector: das ganze Band. */
internal const val VALUES_LOST_BANNER_TEST_TAG = "values_lost_banner"

/**
 * Mindesthoehe des Bandes: 56 dp, das Touch-Ziel der App ([MIN_TOUCH_TARGET]) — obwohl es nichts anzutippen gibt: das Band soll beim Blick von 0,5 s auffallen und ist deshalb so hoch wie die
 * grossen Ziele, nicht so schmal wie das Vorschau-Band (rund 32 dp). Es steht in der Spalte ueber dem Raster und kostet dort Kachelhoehe; mehr als diese Hoehe darf es im Querformat nicht kosten
 * (s. `inline` an [ValuesLostBanner]).
 */
internal val VALUES_LOST_BANNER_MIN_HEIGHT: Dp = MIN_TOUCH_TARGET

/**
 * Das laute Band fuer fehlende Fahrzeugwerte (Spezifikation 2026-10-24): "Werte eingefroren", "Keine aktuellen Werte" oder "Werte eingefroren · verbinde neu 1/5" ([valuesBanner]). Es ersetzt
 * in diesen Zustaenden das schmale [PreviewBanner], das die Nutzerin bei einer Fahrt nicht bemerkt hatte (32 dp, Text 5,5:1, Bandgrund kaum vom Hintergrund zu unterscheiden).
 *
 * Aussehen wie das Alarmband ([AlarmBanner]), nur ohne Tipp: die gedaempfte Statusflaeche in Gelb ([StatusColors.container] ueber `surface`, Entscheidung D1 — die Werte sind zurueckgehalten, nicht falsch),
 * Text `onSurface` in `titleLarge`, die Detailzeile ebenfalls `onSurface`, nur kleiner (`onSurfaceVariant` erreichte auf der gelben Flaeche im Cockpit-Schema Amber nur 4,4988:1, knapp unter den 4,5:1 fuer kleinen Text),
 * Warnsymbol links in der Statusfarbe ([StatusColors.warningOn]). **Nicht anklickbar** (es gibt nichts zu quittieren, der Zustand endet von selbst), **kein Blinken**
 * (Animationsdisziplin). Es steht in der Spalte ueber dem Raster und ueberlagert nichts — kein Streit mit dem Alarmband, das das Raster ueberlagert.
 *
 * [inline]: die Detailzeile steht hinter dem Titel auf **einer** Zeile (kleinere Schrift, gedaempft) statt darunter. Das Querformat ist knapp an Hoehe: ein zweizeiliges Band kostete die Kacheln
 * mehr als die 56 dp, die die Rechnung des Entwurfs annahm; im Hochformat ist Platz, dort steht die Detailzeile darunter. Beides nimmt hoechstens zwei Zeilen und wird sonst mit "…" gekuerzt;
 * das Band waechst bei grosser Systemschrift.
 *
 * TalkBack: `liveRegion = Assertive` sagt den Zustand an; die Beschreibung ist der Titel samt Detail (beim Neuverbinden steht die Versuchsnummer darin, sie wechselt hoechstens fuenfmal).
 */
@Composable
internal fun ValuesLostBanner(banner: ValuesBanner, modifier: Modifier = Modifier, inline: Boolean = false) {
    val title = stringResource(banner.titleRes, *banner.titleArgs.toTypedArray())
    val detail = banner.detailRes?.let { stringResource(it) }
    val surface = MaterialTheme.colorScheme.surface
    val container = StatusColors.container(StatusColors.Warning, surface)
    val accent = StatusColors.warningOn(surface)
    val titleStyle = MaterialTheme.typography.titleLarge
    val detailStyle = MaterialTheme.typography.bodyMedium
    val description = if (detail == null) title else "$title. $detail"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = VALUES_LOST_BANNER_MIN_HEIGHT)
            .background(container, RectangleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = description
            }
            .testTag(VALUES_LOST_BANNER_TEST_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Dekorativ: der Zustand steht in der Beschreibung des Bandes.
        Icon(Icons.Default.Warning, contentDescription = null, tint = accent, modifier = Modifier.size(32.dp))
        if (detail != null && inline) {
            val onSurface = MaterialTheme.colorScheme.onSurface
            Text(
                text = buildAnnotatedString {
                    withStyle(titleStyle.toSpanStyle().copy(color = onSurface)) { append(title) }
                    withStyle(detailStyle.toSpanStyle().copy(color = onSurface)) { append("   $detail") }
                },
                style = titleStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = detailStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// region Previews

@Composable
private fun ValuesLostBannerPreviewContent() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ValuesLostBanner(ValuesBanner(R.string.values_lost_frozen_title, detailRes = R.string.values_lost_frozen_detail))
        ValuesLostBanner(ValuesBanner(R.string.values_lost_frozen_title, detailRes = R.string.values_lost_frozen_detail), inline = true)
        ValuesLostBanner(ValuesBanner(R.string.values_lost_silent_title))
        ValuesLostBanner(ValuesBanner(R.string.values_lost_frozen_reconnecting, titleArgs = listOf(1, 5)))
    }
}

@Preview(name = "ValuesLostBanner (light)", showBackground = true)
@Composable
private fun PreviewValuesLostBannerLight() {
    PreviewTheme(dark = false) { ValuesLostBannerPreviewContent() }
}

@Preview(name = "ValuesLostBanner (dark)", showBackground = true)
@Composable
private fun PreviewValuesLostBannerDark() {
    PreviewTheme(dark = true) { ValuesLostBannerPreviewContent() }
}

// endregion
