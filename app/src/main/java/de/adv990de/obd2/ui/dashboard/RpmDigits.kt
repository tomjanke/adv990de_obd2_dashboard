package de.adv990de.obd2.ui.dashboard

import androidx.compose.material3.Typography
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.adv990de.obd2.obd.formatSensorNumber
import de.adv990de.obd2.ui.theme.asLiveValue
import kotlin.math.max
import kotlin.math.min

/*
 * Die Drehzahl als Ziffern links im Drehzahlbalken (Nutzerwunsch 2026-11-01).
 *
 * Gewaehlt ist das „Fenster“ (nach Vorabbildern, auf dem Pixel gerendert): eine dunkle Flaeche in `background` links in der Mitte des Balkens, darauf die Ziffern
 * in `onSurface` — der hoechste Kontrast in jedem Farbschema, ohne Naht und ohne Umrandung. Der Preis: das Fenster verdeckt die ersten Segmente in der Mitte
 * (Querformat rund 4, Hochformat rund 10); oben und unten bleibt je ein Streifen des Balkens sichtbar. Verworfen: Ziffern mit Kontur (matschig, besonders im hellen
 * Schema) und Ziffern, die je Segment die Farbe wechseln (kippen mitten in der Zahl, Naehte an den Luecken).
 *
 * Schrift: `titleLarge` (rund 26 sp), die Groesse der Ambient-Statuszeile — eine schon benutzte Groesse (Nutzerwunsch), in Plex Mono mit `tnum`, wie jeder Live-Wert.
 */

/** Hoechste angezeigte Drehzahl: fuenf Stellen. Der Wert des PID reicht bis 16 383,75; die Grenze schuetzt nur davor, dass ein unsinniger Wert das Fenster ueber den Balken schiebt. */
internal const val RPM_DIGITS_MAX = 99_999f

/** Das Fenster bleibt mindestens so breit wie vier Ziffern: der Wechsel 999 → 1000 verschiebt seinen rechten Rand nicht. */
internal const val RPM_DIGITS_MIN_CHARS = 4

/** Abstand der Ziffern vom linken Rand des Balkens und (gleich) vom rechten Rand des Fensters. */
internal val RPM_DIGITS_START_PADDING = 8.dp

/** Luft zwischen Ziffernhoehe und Ober- bzw. Unterkante des Fensters. */
internal val RPM_DIGITS_PLATE_PADDING = 5.dp

/** Hoehe der Ziffern von IBM Plex Mono im Verhaeltnis zur Schriftgroesse (Versalhoehe 698 von 1000 Einheiten; die Ziffern sind genauso hoch). */
internal const val PLEX_MONO_DIGIT_HEIGHT_EM = 0.698f

/**
 * Der Text der Ziffern: die Drehzahl auf ganze Umdrehungen gerundet, genau wie die Drehzahl-Kachel (`formatSensorNumber`, Geraetelocale, halbe Umdrehung rundet auf).
 * `null` — kein Fenster, keine Ziffern — wenn die Anzeige nicht freigegeben ist ([enabled], s. [rpmDigitsEnabled]) oder der Wert keine plausible Drehzahl ist
 * (keine Zahl, unendlich, negativ). Ueber [RPM_DIGITS_MAX] wird auf diese Grenze gehalten.
 *
 * Reine Funktion ohne Compose-Bezug (vgl. [rpmBlinkPeriodMs]).
 */
internal fun rpmDigitsText(rpm: Float, enabled: Boolean): String? {
    if (!enabled || !rpm.isFinite() || rpm < 0f) return null
    return formatSensorNumber(min(rpm, RPM_DIGITS_MAX), "rpm")
}

/**
 * Ob [RpmBar] die Ziffern zeigt: nur bei fliessenden Werten ([isLive]) **und** nur fuer die echte Drehzahl. Waehrend die Start-Animation laeuft ([demoRpm] > 0, der
 * animierte Wert) zeigt der Balken eine Bewegung, keine Drehzahl — eine Zahl dazu taeuschte eine Drehzahl vor, die es nicht gibt (Nutzerentscheidung 2026-11-01: ohne
 * Verbindung und beim Start-Sweep keine Ziffern). Das gilt auch bei bestehender Verbindung, wenn der Motor steht und der Sweep laeuft. Ein Motor in Ruhe ([demoRpm] = 0
 * oder `null`) zeigt „0“, wie die Kachel.
 *
 * Reine Funktion ohne Compose-Bezug (vgl. [rpmAlertsEnabled]).
 */
internal fun rpmDigitsEnabled(isLive: Boolean, demoRpm: Float?): Boolean = isLive && (demoRpm == null || demoRpm <= 0f)

/** Wo das Fenster und die Ziffern stehen, in Bildpunkten im Segmentfeld (Ursprung links oben). */
internal data class RpmDigitsLayout(
    val plateLeft: Float,
    val plateTop: Float,
    val plateRight: Float,
    val plateBottom: Float,
    /** Linke Kante der Ziffern. */
    val textLeft: Float,
    /** y der Grundlinie: die Ziffern sitzen mit ihrer Mitte auf der Mitte des Fensters. */
    val baselineY: Float,
)

/**
 * Die Geometrie des Fensters: buendig am linken Rand des Balkens, die Ziffern [startPaddingPx] vom Rand, rechts dieselbe Luft; mindestens so breit wie vier Ziffern
 * ([minTextWidthPx]), damit eine kuerzere Zahl den Rand nicht verschiebt. Hoehe = Ziffernhoehe plus [plateVerticalPaddingPx] oben und unten, mittig im Balken, nie hoeher
 * als der Balken. Die Grundlinie ergibt sich aus der Mitte (Ziffern sind [digitHeightPx] hoch).
 *
 * Reine Funktion ohne Compose-Bezug, direkt JVM-testbar (vgl. [rpmSegmentColor]).
 */
internal fun rpmDigitsLayout(
    barHeightPx: Float,
    textWidthPx: Float,
    minTextWidthPx: Float,
    digitHeightPx: Float,
    startPaddingPx: Float,
    plateVerticalPaddingPx: Float,
): RpmDigitsLayout {
    val centerY = barHeightPx / 2f
    val halfPlate = digitHeightPx / 2f + plateVerticalPaddingPx
    return RpmDigitsLayout(
        plateLeft = 0f,
        plateTop = max(0f, centerY - halfPlate),
        plateRight = startPaddingPx + max(textWidthPx, minTextWidthPx) + startPaddingPx,
        plateBottom = min(barHeightPx, centerY + halfPlate),
        textLeft = startPaddingPx,
        baselineY = centerY + digitHeightPx / 2f,
    )
}

/** Die Schrift der Ziffern: `titleLarge` in Plex Mono mit `tnum` (wie die Ambient-Statuszeile), mittelstark wie jeder Live-Wert. */
internal fun rpmDigitsTextStyle(typography: Typography): TextStyle =
    typography.titleLarge.asLiveValue().copy(fontWeight = FontWeight.Medium)

/**
 * Zeichnet das Fenster und die Ziffern in das Segmentfeld. Gemessen wird mit der Systemschrift auf 1 fixiert (wie die Gang-Box): das Fenster hat feste Masse im
 * 40 dp hohen Segmentfeld, eine grosse Systemschrift liesse die Ziffern darin ueberlaufen. Ein einziges Text-Layout, ohne Zeichenstil — der `TextMeasurer` unterscheidet
 * im Zwischenspeicher nur, was das Layout veraendert, nicht Fuellung oder Kontur.
 */
internal fun DrawScope.drawRpmDigits(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
    plateColor: Color,
    textColor: Color,
) {
    val fixed = Density(density, fontScale = 1f)
    val layout = textMeasurer.measure(AnnotatedString(text), style, softWrap = false, maxLines = 1, density = fixed)
    val minWidth = textMeasurer
        .measure(AnnotatedString("0".repeat(RPM_DIGITS_MIN_CHARS)), style, softWrap = false, maxLines = 1, density = fixed)
        .size.width.toFloat()
    val digitHeight = PLEX_MONO_DIGIT_HEIGHT_EM * with(fixed) { style.fontSize.toPx() }
    val geometry = rpmDigitsLayout(
        barHeightPx = size.height,
        textWidthPx = layout.size.width.toFloat(),
        minTextWidthPx = minWidth,
        digitHeightPx = digitHeight,
        startPaddingPx = RPM_DIGITS_START_PADDING.toPx(),
        plateVerticalPaddingPx = RPM_DIGITS_PLATE_PADDING.toPx(),
    )
    drawRect(
        color = plateColor,
        topLeft = Offset(geometry.plateLeft, geometry.plateTop),
        size = Size(geometry.plateRight - geometry.plateLeft, geometry.plateBottom - geometry.plateTop),
    )
    drawText(layout, color = textColor, topLeft = Offset(geometry.textLeft, geometry.baselineY - layout.firstBaseline))
}
