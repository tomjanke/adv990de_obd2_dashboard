package de.adv990de.obd2.ui.components

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Die sieben Symbole, die nicht im kleinen Paket `material-icons-core` stehen, sondern nur in `material-icons-extended` standen: Download, Upload, Book, PowerSettingsNew,
 * LocalGasStation, CalendarToday und OpenInNew (die automatisch spiegelnde, umrandete Variante). Das Sammelpaket hat rund 10 700 Symbole, die App nutzt davon sieben — im Debug-Build
 * steckten alle im APK (die Release-Fassung raeumt R8 auf).
 *
 * **Die Pfade sind unveraendert aus `androidx.compose.material:material-icons-extended` 1.7.8 uebernommen** (Material Icons, Apache License 2.0, Copyright Google; der Lizenztext der
 * AndroidX-Bausteine liegt in `docs/licenses/` und steht auf der Lizenzseite der App) und mit denselben Bausteinen aufgebaut (`materialIcon`/`materialPath` aus `material-icons-core`):
 * dieselbe Zeichnung, dieselbe Groesse (24 dp, Viewport 24 × 24), dieselbe Fuellung. Sie wurden **maschinell aus den Symbolen der Bibliothek erzeugt**, nicht abgetippt; vor dem Entfernen
 * des Pakets stimmten die Pfad-Knoten und alle Eigenschaften mit den Originalen ueberein, und `AppIconsTest` haelt sie seitdem fest.
 *
 * Ein neues Symbol, das nur das Sammelpaket hat: nicht das Paket zurueckholen, sondern hier ergaenzen — der Pfad kommt aus dem Material-Symbol (`Icons.Filled.<Name>.root`), ein Test
 * haelt ihn fest.
 */
object AppIcons {

    val Download: ImageVector by lazy {
        materialIcon(name = "AppIcons.Download") {
            materialPath {
                moveTo(5.0f, 20.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(20.0f)
                close()
                moveTo(19.0f, 9.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineTo(5.0f)
                lineToRelative(7.0f, 7.0f)
                lineTo(19.0f, 9.0f)
                close()
            }
        }
    }

    val Upload: ImageVector by lazy {
        materialIcon(name = "AppIcons.Upload") {
            materialPath {
                moveTo(5.0f, 20.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(20.0f)
                close()
                moveTo(5.0f, 10.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(-6.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(-7.0f, -7.0f)
                lineTo(5.0f, 10.0f)
                close()
            }
        }
    }

    val Book: ImageVector by lazy {
        materialIcon(name = "AppIcons.Book") {
            materialPath {
                moveTo(18.0f, 2.0f)
                horizontalLineTo(6.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(16.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(6.0f, 4.0f)
                horizontalLineToRelative(5.0f)
                verticalLineToRelative(8.0f)
                lineToRelative(-2.5f, -1.5f)
                lineTo(6.0f, 12.0f)
                verticalLineTo(4.0f)
                close()
            }
        }
    }

    val PowerSettingsNew: ImageVector by lazy {
        materialIcon(name = "AppIcons.PowerSettingsNew") {
            materialPath {
                moveTo(13.0f, 3.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(10.0f)
                horizontalLineToRelative(2.0f)
                lineTo(13.0f, 3.0f)
                close()
                moveTo(17.83f, 5.17f)
                lineToRelative(-1.42f, 1.42f)
                curveTo(17.99f, 7.86f, 19.0f, 9.81f, 19.0f, 12.0f)
                curveToRelative(0.0f, 3.87f, -3.13f, 7.0f, -7.0f, 7.0f)
                reflectiveCurveToRelative(-7.0f, -3.13f, -7.0f, -7.0f)
                curveToRelative(0.0f, -2.19f, 1.01f, -4.14f, 2.58f, -5.42f)
                lineTo(6.17f, 5.17f)
                curveTo(4.23f, 6.82f, 3.0f, 9.26f, 3.0f, 12.0f)
                curveToRelative(0.0f, 4.97f, 4.03f, 9.0f, 9.0f, 9.0f)
                reflectiveCurveToRelative(9.0f, -4.03f, 9.0f, -9.0f)
                curveToRelative(0.0f, -2.74f, -1.23f, -5.18f, -3.17f, -6.83f)
                close()
            }
        }
    }

    val LocalGasStation: ImageVector by lazy {
        materialIcon(name = "AppIcons.LocalGasStation") {
            materialPath {
                moveTo(19.77f, 7.23f)
                lineToRelative(0.01f, -0.01f)
                lineToRelative(-3.72f, -3.72f)
                lineTo(15.0f, 4.56f)
                lineToRelative(2.11f, 2.11f)
                curveToRelative(-0.94f, 0.36f, -1.61f, 1.26f, -1.61f, 2.33f)
                curveToRelative(0.0f, 1.38f, 1.12f, 2.5f, 2.5f, 2.5f)
                curveToRelative(0.36f, 0.0f, 0.69f, -0.08f, 1.0f, -0.21f)
                verticalLineToRelative(7.21f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                reflectiveCurveToRelative(-1.0f, -0.45f, -1.0f, -1.0f)
                lineTo(17.0f, 14.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                horizontalLineToRelative(-1.0f)
                lineTo(14.0f, 5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                lineTo(6.0f, 3.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(16.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(-7.5f)
                horizontalLineToRelative(1.5f)
                verticalLineToRelative(5.0f)
                curveToRelative(0.0f, 1.38f, 1.12f, 2.5f, 2.5f, 2.5f)
                reflectiveCurveToRelative(2.5f, -1.12f, 2.5f, -2.5f)
                lineTo(20.5f, 9.0f)
                curveToRelative(0.0f, -0.69f, -0.28f, -1.32f, -0.73f, -1.77f)
                close()
                moveTo(12.0f, 10.0f)
                lineTo(6.0f, 10.0f)
                lineTo(6.0f, 5.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(5.0f)
                close()
                moveTo(18.0f, 10.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
                reflectiveCurveToRelative(1.0f, 0.45f, 1.0f, 1.0f)
                reflectiveCurveToRelative(-0.45f, 1.0f, -1.0f, 1.0f)
                close()
            }
        }
    }

    val CalendarToday: ImageVector by lazy {
        materialIcon(name = "AppIcons.CalendarToday") {
            materialPath {
                moveTo(20.0f, 3.0f)
                horizontalLineToRelative(-1.0f)
                lineTo(19.0f, 1.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(2.0f)
                lineTo(7.0f, 3.0f)
                lineTo(7.0f, 1.0f)
                lineTo(5.0f, 1.0f)
                verticalLineToRelative(2.0f)
                lineTo(4.0f, 3.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(16.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(20.0f, 21.0f)
                lineTo(4.0f, 21.0f)
                lineTo(4.0f, 8.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(13.0f)
                close()
            }
        }
    }

    val OpenInNew: ImageVector by lazy {
        materialIcon(name = "AppIcons.OpenInNew", autoMirror = true) {
            materialPath {
                moveTo(19.0f, 19.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(7.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(5.0f)
                curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-7.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(7.0f)
                close()
                moveTo(14.0f, 3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(3.59f)
                lineToRelative(-9.83f, 9.83f)
                lineToRelative(1.41f, 1.41f)
                lineTo(19.0f, 6.41f)
                verticalLineTo(10.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(3.0f)
                horizontalLineToRelative(-7.0f)
                close()
            }
        }
    }
}
