package de.adv990de.obd2.obd

/**
 * Das eine Muster, an dem alle Parser Adapterantworten in Tokens zerlegen (ATS1: Leerzeichen an). Eine Konstante statt
 * `"\\s+".toRegex()` im Aufruf: das kompilierte das Muster bei jeder Antwort neu (PRO-15) — die Parser laufen je Messwert.
 */
internal val WHITESPACE = Regex("\\s+")
