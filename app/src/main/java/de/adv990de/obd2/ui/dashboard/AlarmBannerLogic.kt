package de.adv990de.obd2.ui.dashboard

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Zeigt das Band die Live-Zahl (Volt, °C)? Nur, solange Werte fließen: außerhalb von [DashboardPhase.Live] stehen die Kacheln auf „--“ („Nur Live zeigt Zahlen“, Dashboard-Phasen),
 * und das Band zeigte sonst eine bis zu 30 s alte Zahl (die Schonfrist nach „veraltet“, [AlarmStaleGrace]) neben leeren Kacheln. Dann nennt es nur die Art („Kühlmittel heiß“).
 * Fehlercodes sind keine Live-Zahl und bleiben immer stehen.
 */
internal fun alarmBannerShowsValue(phase: DashboardPhase): Boolean = phase == DashboardPhase.Live

/**
 * Der Text des Alarmbands: [message] (der fertige Wortlaut von `alarmMessage`) mit [style] auf der Stelle, an der [value] (die Zahl bzw. die Codes, `alarmValueText`) darin steht.
 * Die Zahl erscheint so in der Zahlenschrift (Plex Mono, `tnum`: stabile Ziffernbreite, Design-Prinzip 3), die Worte und die Einheit bleiben in der Text-Schrift — und der Text bleibt **ein**
 * Knoten mit genau dem Wortlaut von [message] (TalkBack liest ihn ganz, Tests finden ihn am Text), statt aus drei Teilen zusammengesetzt zu sein.
 *
 * Gesucht wird die erste Stelle von [value] im fertigen Text, nicht ein eigener Formatierer: eine Übersetzung, die den Platzhalter anders setzt, bringt die Zahl trotzdem mit.
 * Fehlt sie im Text (eine Übersetzung ohne Platzhalter) oder ist sie leer, gibt es keine Hervorhebung, aber der ganze Text bleibt — das Band darf an einem Schreibfehler nicht scheitern.
 */
internal fun emphasizeValue(message: String, value: String, style: SpanStyle): AnnotatedString {
    val start = if (value.isEmpty()) -1 else message.indexOf(value)
    if (start < 0) return AnnotatedString(message)
    val end = start + value.length
    return buildAnnotatedString {
        append(message.substring(0, start))
        withStyle(style) { append(message.substring(start, end)) }
        append(message.substring(end))
    }
}
