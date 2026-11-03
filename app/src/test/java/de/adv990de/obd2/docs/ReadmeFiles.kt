package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertTrue
import java.io.File

/**
 * Die drei READMEs (seit 2026-11-03): die Kurzfassung `README.md` (Englisch zuerst, Deutsch darunter, ohne Versionsangabe) und die ausfuehrlichen Fassungen
 * `docs/README.en.md` und `docs/README.de.md`, die Version, Testzahlen, Build, Release und Architektur nennen. Tests, die eine Tatsache im README pruefen, nehmen
 * eine dieser Listen statt eines festen Pfads: Tatsachen und Befehle gelten fuer beide ausfuehrlichen Fassungen, Aussagen in einer Sprache nur fuer die eine.
 */
internal object ReadmeFiles {
    const val SHORT = "README.md"
    const val DE = "docs/README.de.md"
    const val EN = "docs/README.en.md"

    /** Die ausfuehrlichen Fassungen: dort stehen Version, Zahlen, Befehle, Berechtigungen, Lizenzabschnitt. */
    val detailed = listOf(DE, EN)

    /** Alle drei, fuer Regeln, die ueberall gelten (keine E-Mail-Adresse, kein BLE-Versprechen). */
    val all = listOf(SHORT, DE, EN)

    fun read(path: String): String {
        val f = File(projectRoot, path)
        assertTrue("$path fehlt", f.isFile)
        return f.readText()
    }
}
