package de.adv990de.obd2.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import de.adv990de.obd2.ui.theme.StatusColors

/**
 * Blinkperiode des Schaltblitzes: 0 (kein Blinken) ausser wenn live verbunden, ein Schwellwert
 * gesetzt ist ([threshold] > 0) und die aktuelle Drehzahl ihn ueberschritten hat. Dann skaliert
 * die Periode linear von 500ms (an der Schwelle) auf 80ms (bei [RPM_BAR_MAX]) — je naeher am
 * Limit, desto hektischer der Blitz.
 *
 * Reine Funktion ohne Compose-Bezug, direkt JVM-testbar (Projekt-Konvention fuer Entscheidungslogik,
 * vgl. `determineGear`, `statusDotColor`). Aus [RpmBar] extrahiert, das sie pro Frame innerhalb der
 * Blink-Coroutine aufruft.
 */
internal fun rpmBlinkPeriodMs(rpm: Float, threshold: Int, isLive: Boolean): Long {
    if (!isLive || threshold <= 0 || rpm <= threshold) return 0L
    val f = if (threshold >= 10000) 1f
            else ((rpm - threshold) / (10000f - threshold)).coerceIn(0f, 1f)
    return (500f - 420f * f).toLong()  // 500ms at threshold → 80ms at max
}

/**
 * Farbe eines einzelnen Segments der [RpmBar]-Skala: unbeleuchtet ([idleColor]), wenn
 * [segmentIndex] >= [litSegments]; sonst beleuchtet — [redlineColor], wenn die Segmentposition
 * [segmentStartRpm] in der Redline-Zone liegt ([redlineFromRpm] oder darueber), sonst [litColor].
 * Die Redline-Zone-Pruefung greift nur fuer bereits beleuchtete Segmente — ein unbeleuchtetes
 * Segment jenseits der Schwelle bleibt [idleColor], nicht rot.
 *
 * Reine Funktion ohne Compose-Bezug (vgl. [rpmBlinkPeriodMs], `statusDotColor`) — die Farben selbst
 * kommen als Parameter herein, weil sie nur innerhalb der Komposition aus `MaterialTheme` bzw.
 * `StatusColors` lesbar sind.
 */
internal fun rpmSegmentColor(
    segmentIndex: Int,
    litSegments: Int,
    segmentStartRpm: Float,
    redlineFromRpm: Float,
    litColor: Color,
    redlineColor: Color,
    idleColor: Color,
): Color = when {
    segmentIndex < litSegments && segmentStartRpm >= redlineFromRpm -> redlineColor
    segmentIndex < litSegments                                     -> litColor
    else                                                            -> idleColor
}

/**
 * Skalierungsfaktor fuer den RPM-Blink-Schwellwert nach Motortemperatur (vier Baender), wenn
 * dynamische Skalierung aktiv ist (`rpmDynamicThreshold`-Einstellung). Fehlt ein plausibler
 * Messwert ([engineTempC] = `null`) oder liegt er unter 50 °C, gilt der konservativste Faktor
 * 0.50 — je waermer der Motor, desto naeher am konfigurierten Schwellwert (1.00 ab 85 °C).
 *
 * Reine Funktion ohne Compose-Bezug, direkt JVM-testbar (Projekt-Konvention fuer Entscheidungslogik,
 * vgl. [rpmBlinkPeriodMs]/[rpmSegmentColor]). Aus `DashboardScreen` extrahiert, das den
 * Rueckgabewert mit dem konfigurierten `rpmBlinkThreshold` multipliziert.
 */
internal fun rpmThresholdTempScale(engineTempC: Float?): Float = when {
    // Kein plausibler Wert (null, NaN) → konservativ. Mit `<`-Grenzen statt Float-Bereichen (`..49.9f`, `50f..69.9f`): zwischen
    // zwei Bereichen blieben Luecken (49.95, 69.95, 84.95), die in den "else"-Zweig fielen und den vollen Faktor 1.00 bekamen (UID-22).
    engineTempC == null || engineTempC.isNaN() -> 0.50f
    engineTempC < 50f                          -> 0.50f
    engineTempC < 70f                          -> 0.75f
    engineTempC < 85f                          -> 0.90f
    else                                       -> 1.00f
}

/**
 * Ob die RPM-Demo-Sweep-Animation in `DashboardScreen` nach der 400ms-Anlaufverzoegerung starten
 * soll: nur, wenn bis dahin noch keine plausible echte Drehzahl eingetroffen ist. Die Frage "schon
 * gezeigt?" beantwortet diese Funktion NICHT — der Sweep laeuft einmal je App-Start, das entscheidet
 * `DashboardViewModel.consumeDemoSweepOffer()` (der Aufrufer verknuepft beide). Dieses Gate ist die
 * Entscheidung "Drehzahl schon da?" und damit der testbare Teil.
 *
 * Reine Funktion ohne Compose-Bezug (vgl. [rpmThresholdTempScale]).
 */
internal fun shouldStartRpmDemoSweep(rpmValue: Float): Boolean = rpmValue < 1f

/**
 * Ob der [RpmBar] ab der Schwelle rot faerbt und blinkt: bei stehender Verbindung mit fliessenden Werten ([isLive]) **oder** solange der Screen die Start-Animation
 * zeigt ([demoRpm] != null, der animierte Wert). Die Start-Animation zeigt Rot und Blinken damit in jedem Fall, auch ohne Verbindung (Nutzerwunsch 2026-10-30;
 * seit `7bcf34a` blinkte sie nur live). Ausserhalb der Animation bleibt es dabei: ohne Verbindung kein Alarmrot und kein Blinken. Mit Drehzahl 0 (vor und nach dem
 * Sweep) ist nichts zu sehen, auch wenn der Wert `true` ist.
 *
 * Reine Funktion ohne Compose-Bezug (vgl. [rpmDisplayThreshold]).
 */
internal fun rpmAlertsEnabled(isLive: Boolean, demoRpm: Float?): Boolean = isLive || demoRpm != null

/** Schwelle der Start-Animation, wenn keine Blinkgrenze eingestellt ist (0): so zeigt der Sweep die Schaltblitz-Vorschau auch vor der ersten Einstellung. */
internal const val RPM_DEMO_FALLBACK_THRESHOLD = 7000

/**
 * Die Blink-/Redline-Schwelle, mit der `DashboardScreen` den [RpmBar] zeichnet.
 *
 * - **Zeigt der Screen die Start-Animation** ([demoRpm] != null, das ist der animierte Wert): nur die eingestellte Blinkgrenze ([configuredThreshold]), bei 0 die
 *   [RPM_DEMO_FALLBACK_THRESHOLD] — und auch nur, solange die Animation läuft ([demoRpm] > 0; davor und danach gibt es nichts zu blinken). Die dynamische
 *   Skalierung nach Motortemperatur ([dynamicScaling]) gilt dort **nicht** (Nutzerwunsch 2026-10-30): der Sweep zeigt immer die Grenze, die der Nutzer eingestellt hat.
 * - **Echte Drehzahl** ([demoRpm] == null): die eingestellte Grenze, mit eingeschalteter Skalierung mal [rpmThresholdTempScale]; 0 = kein Blinken.
 *
 * Reine Funktion ohne Compose-Bezug (vgl. [rpmThresholdTempScale]).
 */
internal fun rpmDisplayThreshold(
    configuredThreshold: Int,
    dynamicScaling: Boolean,
    engineTempC: Float?,
    demoRpm: Float?,
): Int = when {
    demoRpm != null && configuredThreshold > 0 -> configuredThreshold
    demoRpm != null -> if (demoRpm > 0f) RPM_DEMO_FALLBACK_THRESHOLD else 0
    configuredThreshold <= 0 -> 0
    !dynamicScaling -> configuredThreshold
    else -> (configuredThreshold * rpmThresholdTempScale(engineTempC)).toInt()
}

/**
 * Farbe der [OilTemperatureBarStrip]: [idleColor] (grau), solange keine Verbindung besteht oder
 * noch keine Farbe bestimmt ist; sonst eine feste Ampelfarbe je [OilBarColor] — Rot
 * [StatusColors.Critical], Gelb [StatusColors.Warning], Gruen [StatusColors.TrafficLightGreen].
 *
 * Gruen ist bewusst NICHT `colorScheme.tertiary` (so bis zum finalen Review des Features): das ist
 * nur im gruenen Cockpit-Schema tatsaechlich gruen — hellblau im blauen, amber im amber-farbenen
 * (dort kaum von Gelb zu unterscheiden) und mauve bzw. rosa im hellen/dunklen Default-Schema. Eine
 * Ampel muss in jedem Schema als Ampel lesbar sein.
 *
 * Reine Funktion ohne Compose-Bezug (vgl. [rpmSegmentColor]) — [idleColor] kommt als Parameter
 * herein, weil `surfaceVariant` nur innerhalb der Komposition aus `MaterialTheme` lesbar ist.
 */
internal fun oilBarStripColor(color: OilBarColor?, isLive: Boolean, idleColor: Color): Color =
    if (!isLive) idleColor else when (color) {
        null               -> idleColor
        OilBarColor.Red    -> StatusColors.Critical
        OilBarColor.Yellow -> StatusColors.Warning
        OilBarColor.Green  -> StatusColors.TrafficLightGreen
    }

/**
 * Der Anteil des Oel-Streifens, der waehrend der Timerphase bereits gruen ist (Nutzerwunsch 2026-10-16: der Timer als fortlaufender Farbwechsel
 * des gelben Balkens von links nach rechts): `1 - Restzeit / 10 Minuten`, auf 0..1 begrenzt. **Nur wenn der Timer wirklich laeuft:** live **und**
 * gelb **und** Restzeit unter der vollen Dauer — in jedem anderen Zustand 0 (Rot, Gruen und Grau zeigen ihre eigene Farbe; ein Timer, der nicht
 * laeuft, meldet die volle Dauer, s. [oilBarGreenInMillis]). Reine Funktion; mit der Restzeit 0 ist der Streifen ganz gruen, genau dann
 * wechselt auch die Farbe ([oilBarColor]) auf Gruen.
 */
internal fun oilBarGreenShare(color: OilBarColor?, isLive: Boolean, greenInMillis: Long): Float =
    if (!isLive || color != OilBarColor.Yellow) 0f
    else (1f - greenInMillis.toFloat() / OIL_BAR_GREEN_HOLD_MS).coerceIn(0f, 1f)

/** Was die Gang-Box neben dem RPM-Balken zeigt: ein Gang, Leerlauf ("N") oder "–". */
internal sealed interface GearBoxContent {
    data object Neutral : GearBoxContent
    data class Engaged(val gear: Int) : GearBoxContent
    data object Unknown : GearBoxContent
}

/**
 * Praezedenz der Gang-Box: ohne Verbindung immer [GearBoxContent.Unknown]; sonst gewinnt "N" vor
 * einem noch gesetzten Gang (die ECU meldet den Stillstand sofort, die GPS-Uebersetzung hinkt nach).
 */
internal fun gearBoxContent(gear: Int?, isNeutral: Boolean, isLive: Boolean): GearBoxContent = when {
    !isLive -> GearBoxContent.Unknown
    isNeutral -> GearBoxContent.Neutral
    gear != null -> GearBoxContent.Engaged(gear)
    else -> GearBoxContent.Unknown
}

/**
 * "N" ist wie die Leerlauf-Kontrollleuchte am Motorrad in jedem Farbschema das feste Ampel-Gruen
 * (dritte dokumentierte Gruen-Ausnahme, s. CLAUDE.md); Gang und Platzhalter nehmen Theme-Farben.
 */
internal fun gearBoxColor(content: GearBoxContent, engagedColor: Color, unknownColor: Color): Color =
    when (content) {
        GearBoxContent.Neutral -> StatusColors.TrafficLightGreen
        is GearBoxContent.Engaged -> engagedColor
        GearBoxContent.Unknown -> unknownColor
    }

/** Die Drehzahl, wie der Screenreader sie ansagt: auf 100 gerundet und nie negativ. */
internal fun rpmForSpeech(rpm: Float): Int =
    if (rpm.isNaN() || rpm.isInfinite() || rpm <= 0f) 0 else (Math.round(rpm / 100f) * 100)
