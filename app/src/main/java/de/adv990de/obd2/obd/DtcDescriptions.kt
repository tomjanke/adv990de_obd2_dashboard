package de.adv990de.obd2.obd

import androidx.annotation.StringRes
import de.adv990de.obd2.R

/**
 * Die Beschreibung eines Fehlercodes als String-Ressource: eine Auswahl haeufiger SAE-J2012-Codes (die Zahl steht hier bewusst nicht,
 * sie ist schon von 35 auf 37 gedriftet; `DtcDescriptionTest` haelt Liste und Ressourcen zusammen), alles andere `dtc_unknown`.
 *
 * Eine Ressource statt eines Textes, damit die Beschreibung der Sprache des Geraets folgt (`values/` englisch, `values-de/` deutsch) —
 * sie war vorher fest englisch im Code. Die deutschen Texte sind eine eigene Uebersetzung der SAE-Namen, kein amtlicher Wortlaut; der Code
 * selbst steht in der Oberflaeche immer daneben, damit er sich nachschlagen laesst.
 *
 * Eine reine Funktion mit Absicht (frueher ein privater Helfer des [DtcParser]): `DtcEntry` und die Fehlerhistorie speichern nur den Code und
 * loesen den Text erst bei der Anzeige auf — die Beschreibung braucht damit weder eine Datenbankspalte noch ein Hilt-Binding. Der Abgleich ist
 * exakt, ohne Trimmen und ohne Gross-/Kleinschreibung.
 */
@StringRes
fun dtcDescriptionRes(code: String): Int = when (code) {
    "P0100" -> R.string.dtc_p0100
    "P0101" -> R.string.dtc_p0101
    "P0102" -> R.string.dtc_p0102
    "P0103" -> R.string.dtc_p0103
    "P0110" -> R.string.dtc_p0110
    "P0115" -> R.string.dtc_p0115
    "P0120" -> R.string.dtc_p0120
    "P0130" -> R.string.dtc_p0130
    "P0171" -> R.string.dtc_p0171
    "P0172" -> R.string.dtc_p0172
    "P0175" -> R.string.dtc_p0175
    "P0200" -> R.string.dtc_p0200
    "P0300" -> R.string.dtc_p0300
    "P0301" -> R.string.dtc_p0301
    "P0302" -> R.string.dtc_p0302
    "P0303" -> R.string.dtc_p0303
    "P0304" -> R.string.dtc_p0304
    "P0305" -> R.string.dtc_p0305
    "P0306" -> R.string.dtc_p0306
    "P0325" -> R.string.dtc_p0325
    "P0335" -> R.string.dtc_p0335
    "P0340" -> R.string.dtc_p0340
    "P0400" -> R.string.dtc_p0400
    "P0401" -> R.string.dtc_p0401
    "P0420" -> R.string.dtc_p0420
    "P0421" -> R.string.dtc_p0421
    "P0430" -> R.string.dtc_p0430
    "P0440" -> R.string.dtc_p0440
    "P0442" -> R.string.dtc_p0442
    "P0455" -> R.string.dtc_p0455
    "P0500" -> R.string.dtc_p0500
    "P0505" -> R.string.dtc_p0505
    "P0600" -> R.string.dtc_p0600
    "P0700" -> R.string.dtc_p0700
    "P0711" -> R.string.dtc_p0711
    "P0730" -> R.string.dtc_p0730
    "P0740" -> R.string.dtc_p0740
    else -> R.string.dtc_unknown
}
