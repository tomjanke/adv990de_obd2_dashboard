package de.adv990de.obd2.docs

import de.adv990de.obd2.logbook.MAINTENANCE_TYPES
import de.adv990de.obd2.projectRoot
import de.adv990de.obd2.transfer.SettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Der Schutz gegen Vergessen: jeder Einstellungs-Schluessel im Quelltext muss entweder in ein Paket (`SettingsCatalog.exported`), in die Zaehler
 * (`counterKeys`) oder mit Begruendung auf die Liste der Ausnahmen (`excluded`). Wer einen Schalter einfuehrt, muss entscheiden.
 *
 * Gelesen wird **jede** Quelldatei unter `app/src/main`, nicht nur `*Preferences.kt`: ein Schluessel an anderer Stelle bliebe sonst unsichtbar.
 */
class PreferenceKeysExportGuardTest {

    private val mainRoot = File(projectRoot, "app/src/main/java")
    private val keyCall = Regex("""\b(?:string|int|boolean|float|long|double|stringSet)PreferencesKey\(\s*"([^"]+)"\s*\)""")

    /** Alle Schluesselnamen im Quelltext; ein Name mit `${…}` ist eine Familie. */
    private fun sourceKeys(root: File = mainRoot): Set<String> =
        root.walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> keyCall.findAll(f.readText()).map { it.groupValues[1] } }
            .toSet()

    private val anyKeyCall = Regex("""\b(?:string|int|boolean|float|long|double|stringSet)PreferencesKey\(""")

    /** Dateien mit einem Schluessel-Aufruf, dessen Name kein Zeichenketten-Literal ist (eine Konstante bliebe fuer den Schutz unsichtbar). */
    private fun nonLiteralCalls(root: File = mainRoot): List<String> =
        root.walkTopDown().filter { it.extension == "kt" }.mapNotNull { f ->
            val text = f.readText()
            val all = anyKeyCall.findAll(text).count()
            val literal = keyCall.findAll(text).count()
            if (all != literal) "${f.name}: $all Aufrufe, davon $literal mit Literal" else null
        }.toList()

    private val template = Regex("""\$\{[^}]*}""")

    /** Die Schluessel, die der Katalog fuer einen Quelltext-Namen fuehren muss: der Name selbst, bei einer Familie jede Ausprägung je Wartungsart. */
    private fun expand(name: String): List<String> =
        if (template.containsMatchIn(name)) MAINTENANCE_TYPES.map { template.replace(name, it.name.lowercase()) } else listOf(name)

    private fun covered(): List<String> =
        SettingsCatalog.exported.map { it.key } + SettingsCatalog.counterKeys + SettingsCatalog.excluded.keys

    @Test
    fun `every preference key is exported, a counter or an explained exception`() {
        val covered = covered().toSet()
        val missing = sourceKeys().flatMap(::expand).filter { it !in covered }.sorted()
        assertTrue(
            missing.joinToString("\n") { "neuer Schluessel `$it`: in `SettingsCatalog.exported` aufnehmen - oder mit Begruendung nach `SettingsCatalog.excluded`" } +
                "\n(ein Zaehler gehoert in `SettingsCatalog.counterKeys` und `counters.json`)",
            missing.isEmpty(),
        )
    }

    @Test
    fun `every key of the catalog still exists in the source`() {
        val inSource = sourceKeys().flatMap(::expand).toSet()
        val stale = covered().filter { it !in inSource }.sorted()
        assertTrue(
            stale.joinToString("\n") { "Schluessel `$it` steht im Katalog, aber nicht mehr im Quelltext: aus `SettingsCatalog` entfernen (oder die Umbenennung rueckgaengig machen: Schluessel sind persistierter Zustand)" },
            stale.isEmpty(),
        )
    }

    @Test
    fun `no key stands in two places and the three sets are disjoint`() {
        val all = covered()
        val twice = all.groupBy { it }.filter { it.value.size > 1 }.keys.sorted()
        assertTrue("Schluessel mehrfach im Katalog (exported, counterKeys, excluded): $twice", twice.isEmpty())
    }

    @Test
    fun `every key is declared with a string literal, a constant would hide it from the guard`() {
        val offenders = nonLiteralCalls()
        assertTrue(
            "Schluessel nur als Zeichenketten-Literal deklarieren (sonst sieht der Schutz ihn nicht): $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `a key declared through a constant is flagged`() {
        val dir = java.nio.file.Files.createTempDirectory("guard").toFile()
        try {
            File(dir, "Fine.kt").writeText("""val a = intPreferencesKey("fine")""")
            assertTrue(nonLiteralCalls(dir).isEmpty())
            File(dir, "Hidden.kt").writeText("""val k = stringPreferencesKey(KEY_NAME)""")
            assertEquals(listOf("Hidden.kt: 1 Aufrufe, davon 0 mit Literal"), nonLiteralCalls(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `every exception has a reason`() {
        val blank = SettingsCatalog.excluded.filterValues { it.isBlank() }.keys.sorted()
        assertTrue("Ausnahme ohne Begruendung in `SettingsCatalog.excluded`: $blank", blank.isEmpty())
    }

    // ── die Regel selbst ──────────────────────────────────────────────────

    @Test
    fun `the maintenance family is expanded to all six kinds`() {
        val keys = expand("maintenance_interval_\${type.name.lowercase()}_km")
        assertEquals(6, keys.size)
        assertTrue(keys.all { it.startsWith("maintenance_interval_") && it.endsWith("_km") && "$" !in it })
        assertEquals(listOf("theme"), expand("theme"))
    }

    @Test
    fun `a key anywhere in the source is found, not only in the preferences classes`() {
        val dir = java.nio.file.Files.createTempDirectory("guard").toFile()
        try {
            File(dir, "Elsewhere.kt").writeText("""val k = booleanPreferencesKey("brand_new_switch")""")
            File(dir, "Many.kt").writeText("""val a = intPreferencesKey("a_one"); val b = stringPreferencesKey( "b_two" )""")
            assertEquals(setOf("brand_new_switch", "a_one", "b_two"), sourceKeys(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the real source yields the keys the guard relies on`() {
        val keys = sourceKeys()
        assertTrue("theme" in keys && "slot_pids" in keys && "logbook_gps_km" in keys)
        assertTrue(keys.any { template.containsMatchIn(it) })
        assertTrue(keys.size >= 40)
    }
}
