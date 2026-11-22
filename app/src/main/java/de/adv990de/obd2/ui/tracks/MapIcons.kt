package de.adv990de.obd2.ui.tracks

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Die Symbole der Kartenknoepfe, selbst gezeichnet (24 x 24, die Farbe kommt vom `Icon`): ein Knopf mit Wort brach um oder schnitt ab („Anpasse“ statt „Anpassen“), ein Symbol tut das nie.
 * Das Core-Paket der Material-Symbole hat keines davon.
 */
internal object MapIcons {

    private fun build(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply(block).build()

    private val ink = SolidColor(Color.Black)

    /** Die vier Ecken eines Suchers: den ganzen Track ins Bild holen. */
    val Fit: ImageVector by lazy {
        build("TrackFit") {
            path(stroke = ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 9f); verticalLineTo(4f); horizontalLineTo(9f)
                moveTo(15f, 4f); horizontalLineTo(20f); verticalLineTo(9f)
                moveTo(20f, 15f); verticalLineTo(20f); horizontalLineTo(15f)
                moveTo(9f, 20f); horizontalLineTo(4f); verticalLineTo(15f)
            }
            path(fill = ink) {
                moveTo(9f, 12f); arcToRelative(3f, 3f, 0f, true, true, 6f, 0f); arcToRelative(3f, 3f, 0f, true, true, -6f, 0f); close()
            }
        }
    }

    /** Eine Raute: oben gefuellt (Norden), unten nur die Kontur — Norden ist oben. */
    val NorthUp: ImageVector by lazy {
        build("TrackNorthUp") {
            path(fill = ink) { moveTo(12f, 3f); lineTo(17f, 12f); lineTo(7f, 12f); close() }
            path(stroke = ink, strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round) { moveTo(7f, 12f); lineTo(17f, 12f); lineTo(12f, 21f); close() }
        }
    }

    /** Ein Navigationspfeil, gefuellt: der Pfeil zeigt in Fahrtrichtung nach oben. */
    val HeadingUp: ImageVector by lazy {
        build("TrackHeadingUp") {
            path(fill = ink) { moveTo(12f, 2f); lineTo(19f, 21f); lineTo(12f, 17f); lineTo(5f, 21f); close() }
        }
    }

    /** Ein Fadenkreuz mit Punkt: auf die eigene Position zentrieren. */
    val Recentre: ImageVector by lazy {
        build("TrackRecentre") {
            path(stroke = ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
                moveTo(6f, 12f); arcToRelative(6f, 6f, 0f, true, true, 12f, 0f); arcToRelative(6f, 6f, 0f, true, true, -12f, 0f)
                moveTo(12f, 2f); verticalLineTo(5f)
                moveTo(12f, 19f); verticalLineTo(22f)
                moveTo(2f, 12f); horizontalLineTo(5f)
                moveTo(19f, 12f); horizontalLineTo(22f)
            }
            path(fill = ink) { moveTo(10f, 12f); arcToRelative(2f, 2f, 0f, true, true, 4f, 0f); arcToRelative(2f, 2f, 0f, true, true, -4f, 0f); close() }
        }
    }
}
