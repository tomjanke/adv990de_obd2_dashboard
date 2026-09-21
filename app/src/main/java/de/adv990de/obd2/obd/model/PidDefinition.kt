package de.adv990de.obd2.obd.model

import androidx.annotation.StringRes

data class PidDefinition(
    val id: String,           // e.g. "010C"
    /** Stabiler, sprachunabhaengiger Bezeichner — Sortierung, Logs, CSV- und
     *  Freeze-Frame-Schluessel haengen daran. Nicht fuer die Anzeige verwenden. */
    val name: String,         // e.g. "RPM"
    /** Anzeigename fuer die UI, lokalisiert. */
    @StringRes val labelRes: Int,
    val unit: String,         // e.g. "rpm"
    val minValue: Float,
    val maxValue: Float,
    val formula: (ByteArray) -> Float   // raw bytes → engineering value
)
