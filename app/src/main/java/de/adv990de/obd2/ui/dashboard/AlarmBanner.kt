package de.adv990de.obd2.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.R
import de.adv990de.obd2.alarm.Alarm
import de.adv990de.obd2.alarm.AlarmKind
import de.adv990de.obd2.alarm.AlarmSeverity
import de.adv990de.obd2.alarm.alarmMessage
import de.adv990de.obd2.alarm.alarmSummary
import de.adv990de.obd2.alarm.alarmValueText
import de.adv990de.obd2.ui.theme.MIN_TOUCH_TARGET
import de.adv990de.obd2.ui.theme.PreviewTheme
import de.adv990de.obd2.ui.theme.StatusColors
import de.adv990de.obd2.ui.theme.asLiveValue

/** Test-only selectors: das ganze Band und der „+N“-Zähler rechts darin. */
internal const val ALARM_BANNER_TEST_TAG = "alarm_banner"
internal const val ALARM_BANNER_MORE_TEST_TAG = "alarm_banner_more"

/**
 * Mindesthöhe des Bandes: 72 dp (Entscheidung des Nutzers 2026-10-18: „höher mit größerer Schrift“), über dem Touch-Ziel von [MIN_TOUCH_TARGET]. Das Band **überlagert** die Kacheln und schiebt nichts
 * (s. [AlarmBanner]), die Höhe kostet also keinen Platz im Raster, sondern deckt die obere Kachelkante ab — das Wertefeld einer Kachel (unten) bleibt frei.
 */
internal val ALARM_BANNER_MIN_HEIGHT: Dp = 72.dp

/** Die Statusfarbe der Stufe: Warnung rot, Hinweis gelb — dieselbe Bedeutung wie bei den Chips und Kacheln (cross-theme, `StatusColors`), nicht eine feste Farbe an dieser Stelle. */
private fun alarmStatusColor(severity: AlarmSeverity): Color =
    if (severity == AlarmSeverity.WARNING) StatusColors.Critical else StatusColors.Warning

/**
 * Das Alarmband (Spezifikation „Alarmband“): **eine** volle Zeile, die **das Kachelraster am oberen Rand überlagert** und nichts verschiebt (`DashboardScreen`: `Box` um das Raster, das Band zuletzt) —
 * der wichtigste Alarm, „+N“ rechts, wenn [moreCount] weitere warten (nie eine Zeile je Alarm). **Ein Tipp** auf irgendeine Stelle quittiert ([onAcknowledge]); das Band ist ein Touch-Ziel von
 * mindestens [ALARM_BANNER_MIN_HEIGHT] Höhe über die ganze Breite (Handschuhe auf dem Motorrad) und wächst bei großer Systemschrift mit dem Text. Die Schrift ist `titleLarge` (wie die Ambient-Statuszeile):
 * das Band liegt über den Kacheln und muss beim Blick von 0,5 s auffallen.
 *
 * Die Stufe trägt die Farbe, nicht ein Wort: der Hintergrund ist die gedämpfte Statusfläche ([StatusColors.container] über `surface`, in jedem Farbschema — Warnung rot, Hinweis gelb), der Text
 * `onSurface` (Kontrast aus den Bildpunkten geprüft, `AlarmBannerDeviceTest`), das Symbol links in der vollen Statusfarbe (Gelb auf hellem Grund über [StatusColors.warningOn]).
 * Der Text ist der von `alarmMessage` (Benachrichtigung und Band sagen dasselbe), die Zahl darin in der Zahlenschrift ([emphasizeValue]); er nimmt höchstens zwei Zeilen (bei großer Systemschrift
 * oder vielen neuen Fehlercodes), danach folgt „…“ — das Band wächst nicht ohne Grenze und deckt nicht mehr Kachel ab als nötig, die Beschreibung für TalkBack nennt jeden Code. **Kein Blinken, keine Animation**
 * (Animationsdisziplin: Auffälligkeit durch Farbe, Größe und Platz); das Band hält keinen eigenen Zustand — kommt und geht mit dem Alarm.
 *
 * TalkBack: `liveRegion = Assertive` sagt einen neuen Alarm an, die Beschreibung nennt Stufe und Art (ohne die Live-Zahl, [alarmSummary]) und sagt, dass ein Tipp quittiert; „Alarm quittieren“ ist die
 * Bezeichnung des Tipps. Weil die Beschreibung die Zahl nicht trägt, wechselt sie nur mit einem anderen Alarm — die Ansage kommt nicht mit jedem Messwert neu (bis 1.36.0 tat sie das). Die Zahl
 * steht sichtbar im Text des Bandes und in der Beschreibung der Kachel.
 */
@Composable
internal fun AlarmBanner(
    alarm: Alarm,
    moreCount: Int,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
    showValue: Boolean = true,
) {
    // Ohne Live-Zahl ([showValue] falsch, s. [alarmBannerShowsValue]) nennt das Band nur die Art; ein Fehler-Alarm hat keine Live-Zahl und bleibt, wie er ist.
    val plain = !showValue && alarm.kind != AlarmKind.FAULT_CODE
    val message = if (plain) alarmSummary(LocalResources.current, alarm) else alarmMessage(LocalResources.current, alarm)
    val severityLabel = stringResource(
        if (alarm.severity == AlarmSeverity.WARNING) R.string.alarm_severity_warning else R.string.alarm_severity_notice,
    )
    // Ohne die Live-Zahl: die Beschreibung ist Teil einer Assertive-Live-Region und darf nicht mit jedem Zyklus wechseln (s. [alarmSummary]).
    val description = stringResource(R.string.alarm_banner_desc, severityLabel, alarmSummary(LocalResources.current, alarm))
    val acknowledgeLabel = stringResource(R.string.alarm_acknowledge_action)
    val surface = MaterialTheme.colorScheme.surface
    val container = StatusColors.container(alarmStatusColor(alarm.severity), surface)
    val accent = if (alarm.severity == AlarmSeverity.WARNING) StatusColors.criticalOn(surface) else StatusColors.warningOn(surface)
    val titleStyle = MaterialTheme.typography.titleLarge
    val styledMessage = if (plain) AnnotatedString(message) else emphasizeValue(message, alarmValueText(alarm), titleStyle.asLiveValue().toSpanStyle())

    // Die Reihenfolge ist Absicht und weicht von "padding vor background" ab: der Rand liegt INNEN, hinter dem clickable — Fläche und Trefferfläche sind das ganze Band,
    // nicht nur das, was vom Rand übrig bleibt.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ALARM_BANNER_MIN_HEIGHT)
            .background(container, RectangleShape)
            .clickable(onClickLabel = acknowledgeLabel, role = Role.Button, onClick = onAcknowledge)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = description
            }
            .testTag(ALARM_BANNER_TEST_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Dekorativ: die Stufe und der Text stehen in der Beschreibung des Bandes.
        Icon(Icons.Default.Warning, contentDescription = null, tint = accent, modifier = Modifier.size(32.dp))
        Text(
            text = styledMessage,
            style = titleStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (moreCount > 0) {
            Text(
                text = stringResource(R.string.alarm_more_count, moreCount),
                style = titleStyle.asLiveValue(),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(ALARM_BANNER_MORE_TEST_TAG),
            )
        }
    }
}

// region Previews

@Composable
private fun AlarmBannerPreviewContent() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AlarmBanner(Alarm(AlarmKind.COOLANT, AlarmSeverity.WARNING, value = 104f), moreCount = 0, onAcknowledge = {})
        AlarmBanner(Alarm(AlarmKind.VOLTAGE, AlarmSeverity.NOTICE, value = 12.3f), moreCount = 1, onAcknowledge = {})
        AlarmBanner(Alarm(AlarmKind.FAULT_CODE, AlarmSeverity.WARNING, codes = listOf("P0301", "P0420")), moreCount = 0, onAcknowledge = {})
    }
}

@Preview(name = "AlarmBanner (light)", showBackground = true)
@Composable
private fun PreviewAlarmBannerLight() {
    PreviewTheme(dark = false) { AlarmBannerPreviewContent() }
}

@Preview(name = "AlarmBanner (dark)", showBackground = true)
@Composable
private fun PreviewAlarmBannerDark() {
    PreviewTheme(dark = true) { AlarmBannerPreviewContent() }
}

// endregion
