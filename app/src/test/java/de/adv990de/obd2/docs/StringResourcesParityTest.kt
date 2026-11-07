package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Alle Sprachen der App sind gleich vollstaendig (Auftrag des Nutzers 2026-11-02: „Gesamttest“). Die Funktionsbereiche haben eigene Paritaetstests (Logbuch, Alarme, Gelaende,
 * Fehlercodes ...); dieser vergleicht die ganze `strings.xml` jeder Sprache mit der Standardsprache `values/` (Englisch):
 *  - dieselben Texte und Mengenformen, keine mehr, keine weniger, keine doppelt;
 *  - je Sprache die Mengenkategorien, die sie braucht (Spanisch kennt zusaetzlich `many`);
 *  - jeder Text behaelt die Platzhalter des Originals (`%1$s`, `%2$d` ...) — ein verlorener Platzhalter zeigt im Betrieb ein falsches Format oder stuerzt ab;
 *  - kein Text ist leer;
 *  - ein Text darf nur dann dem englischen gleichen, wenn er technisch ist (Formate, Einheiten, Kuerzel) oder in [SAME_AS_DEFAULT] steht — sonst ist er vermutlich unuebersetzt.
 *
 * Eine neue Sprache (`values-xx/strings.xml`) braucht: ihre Mengenkategorien in [PLURAL_QUANTITIES], eine Zeile in beiden ausfuehrlichen READMEs (Abschnitt „Lokalisierung“ bzw. „Localization“) und ggf. Eintraege in [SAME_AS_DEFAULT].
 * Die Sprache der App folgt der Systemsprache des Geraets (s. CLAUDE.md „Sprachen“).
 */
class StringResourcesParityTest {

    private val resDir = File(projectRoot, "app/src/main/res")

    private class Resources(
        val stringNames: List<String>,
        val strings: Map<String, String>,
        val pluralNames: List<String>,
        val plurals: Map<String, Map<String, String>>,
    )

    private fun load(folder: String): Resources {
        val file = File(resDir, "$folder/strings.xml")
        assertTrue("${file.relativeTo(projectRoot)} fehlt", file.isFile)
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val stringNames = mutableListOf<String>()
        val strings = linkedMapOf<String, String>()
        val pluralNames = mutableListOf<String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
        for (i in 0 until root.childNodes.length) {
            val node = root.childNodes.item(i) as? Element ?: continue
            when (node.tagName) {
                "string" -> {
                    stringNames += node.getAttribute("name")
                    strings[node.getAttribute("name")] = node.textContent
                }
                "plurals" -> {
                    pluralNames += node.getAttribute("name")
                    val items = linkedMapOf<String, String>()
                    for (j in 0 until node.childNodes.length) {
                        val item = node.childNodes.item(j) as? Element ?: continue
                        items[item.getAttribute("quantity")] = item.textContent
                    }
                    plurals[node.getAttribute("name")] = items
                }
            }
        }
        return Resources(stringNames, strings, pluralNames, plurals)
    }

    /** Die Ordner `values-xx` (oder `values-xx-rYY`) mit einer `strings.xml` — nicht `values-night` und andere Qualifizierer. */
    private val localeFolders: List<String> = (resDir.listFiles().orEmpty())
        .filter { it.isDirectory && Regex("values-[a-z]{2,3}(-r[A-Z]{2})?").matches(it.name) && File(it, "strings.xml").isFile }
        .map { it.name }
        .sorted()

    private fun languageOf(folder: String) = folder.removePrefix("values-").substringBefore("-r")

    private val default = load("values")

    private fun List<String>.preview() = take(12).joinToString(", ") + if (size > 12) " … (+${size - 12})" else ""

    @Test
    fun `there are language folders to compare and each one is known`() {
        assertTrue("keine Sprachordner gefunden — der Test saehe nichts", localeFolders.isNotEmpty())
        for (folder in localeFolders) {
            assertTrue(
                "$folder: die Mengenkategorien der Sprache '${languageOf(folder)}' fehlen in PLURAL_QUANTITIES (StringResourcesParityTest)",
                languageOf(folder) in PLURAL_QUANTITIES,
            )
        }
    }

    @Test
    fun `the default language has no duplicate names and only one and other in its plurals`() {
        assertEquals("doppelte Namen in values/", emptyList<String>(), default.stringNames.groupBy { it }.filter { it.value.size > 1 }.keys.toList())
        assertEquals("doppelte Mengenformen in values/", emptyList<String>(), default.pluralNames.groupBy { it }.filter { it.value.size > 1 }.keys.toList())
        for ((name, items) in default.plurals) {
            assertEquals("values/ $name: Mengen", setOf("one", "other"), items.keys)
        }
    }

    @Test
    fun `every language has exactly the texts of the default language`() {
        for (folder in localeFolders) {
            val loc = load(folder)
            val dup = loc.stringNames.groupBy { it }.filter { it.value.size > 1 }.keys.toList()
            assertTrue("$folder: doppelte Texte: ${dup.preview()}", dup.isEmpty())
            val missing = (default.strings.keys - loc.strings.keys).sorted()
            val extra = (loc.strings.keys - default.strings.keys).sorted()
            assertTrue("$folder: ${missing.size} Texte fehlen: ${missing.preview()}", missing.isEmpty())
            assertTrue("$folder: ${extra.size} Texte gibt es nur hier: ${extra.preview()}", extra.isEmpty())
        }
    }

    @Test
    fun `every language has exactly the plurals of the default language with the quantities it needs`() {
        for (folder in localeFolders) {
            val loc = load(folder)
            val dup = loc.pluralNames.groupBy { it }.filter { it.value.size > 1 }.keys.toList()
            assertTrue("$folder: doppelte Mengenformen: ${dup.preview()}", dup.isEmpty())
            val missing = (default.plurals.keys - loc.plurals.keys).sorted()
            val extra = (loc.plurals.keys - default.plurals.keys).sorted()
            assertTrue("$folder: ${missing.size} Mengenformen fehlen: ${missing.preview()}", missing.isEmpty())
            assertTrue("$folder: ${extra.size} Mengenformen gibt es nur hier: ${extra.preview()}", extra.isEmpty())
            val needed = PLURAL_QUANTITIES.getValue(languageOf(folder))
            val wrong = loc.plurals.filter { it.value.keys != needed }.keys.sorted()
            assertTrue("$folder: Mengen weichen von $needed ab bei: ${wrong.preview()}", wrong.isEmpty())
        }
    }

    @Test
    fun `every text keeps the placeholders of the default text`() {
        for (folder in localeFolders) {
            val loc = load(folder)
            val bad = mutableListOf<String>()
            for ((name, text) in default.strings) {
                val other = loc.strings[name] ?: continue
                if (placeholders(text) != placeholders(other)) bad += "$name ${placeholders(text)} != ${placeholders(other)}"
            }
            for ((name, items) in default.plurals) {
                for ((quantity, text) in loc.plurals[name].orEmpty()) {
                    // Wie das Original derselben Menge, sonst wie dessen "other": Spanisch hat `many`, das Englische nicht.
                    val reference = items[quantity] ?: items.getValue("other")
                    if (placeholders(reference) != placeholders(text)) bad += "$name[$quantity] ${placeholders(reference)} != ${placeholders(text)}"
                }
            }
            assertTrue("$folder: ${bad.size} Texte mit anderen Platzhaltern: ${bad.preview()}", bad.isEmpty())
        }
    }

    @Test
    fun `no text is empty`() {
        for (folder in localeFolders + "values") {
            val loc = load(folder)
            val empty = loc.strings.filter { it.value.isBlank() }.keys + loc.plurals.flatMap { (n, items) -> items.filter { it.value.isBlank() }.keys.map { "$n[$it]" } }
            assertTrue("$folder: leere Texte: ${empty.toList().preview()}", empty.isEmpty())
        }
    }

    @Test
    fun `a text equals the English one only if it is technical or listed - otherwise it is probably untranslated`() {
        for (folder in localeFolders) {
            val loc = load(folder)
            val same = mutableListOf<String>()
            for ((name, text) in default.strings) {
                if (loc.strings[name] == text && !isTechnical(text)) same += name
            }
            for ((name, items) in default.plurals) {
                for ((quantity, text) in items) {
                    val other = loc.plurals[name]?.get(quantity) ?: loc.plurals[name]?.get("other")
                    if (other == text && !isTechnical(text)) same += "$name[$quantity]"
                }
            }
            val allowed = SAME_AS_DEFAULT[folder].orEmpty()
            assertEquals(
                "$folder: Texte, die dem Englischen gleichen und nicht in SAME_AS_DEFAULT stehen (vermutlich unuebersetzt) bzw. Eintraege, die es nicht mehr sind",
                allowed.sorted(),
                same.sorted(),
            )
        }
    }

    @Test
    fun `the README names every language folder`() {
        // Beide ausfuehrlichen Fassungen haben den Abschnitt "Lokalisierung" bzw. "Localization".
        for (path in ReadmeFiles.detailed) {
            val readme = ReadmeFiles.read(path)
            for (folder in localeFolders) {
                assertTrue("$path nennt `$folder/` nicht (Abschnitt „Lokalisierung“ bzw. „Localization“)", readme.contains("$folder/"))
            }
            assertTrue("$path nennt `values/` (Standardsprache) nicht", readme.contains("`values/`"))
        }
    }

    private companion object {
        /** Die Mengenkategorien je Sprache (CLDR): Deutsch und Englisch `one`/`other`, Spanisch zusaetzlich `many` (grosse runde Zahlen, Android 14 und neuer). */
        val PLURAL_QUANTITIES: Map<String, Set<String>> = mapOf(
            "de" to setOf("one", "other"),
            "es" to setOf("one", "many", "other"),
        )

        /**
         * Texte, die in einer Sprache dem englischen gleichen duerfen, weil das Wort dort gleich geschrieben wird oder ein Eigenname ist
         * („Live“, „Reserve“, „Freeze Frame“, „VIN“). Rein technische Texte (nur Platzhalter, Einheiten, Kuerzel) brauchen keinen Eintrag.
         * Der Test verlangt die Liste genau: ein Eintrag, der keiner mehr ist, faellt auf.
         */
        val SAME_AS_DEFAULT: Map<String, Set<String>> = mapOf(
            "values-de" to setOf(
                "app_info_build_label", "app_info_name_label", "app_info_version_label", "app_label", "app_name", "freeze_frame_title",
                "history_detail_stats_max", "history_detail_stats_min", "nav_live", "settings_fuel_reserve_label", "settings_fuel_reserve_title",
                "settings_theme_amber", "settings_theme_system", "transfer_format_csv",
            ),
            "values-es" to setOf(
                "app_info_name_label", "app_label", "app_name", "freeze_frame_error_prefix", "freeze_frame_title", "pid_rpm", "transfer_format_csv", "vehicle_info_label_vin",
            ),
        )

        private val PLACEHOLDER = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]""")
        private val WORD = Regex("""\p{L}{3,}""")

        /** Die Formatangaben eines Textes, sortiert — gleich, wenn dieselben Platzhalter vorkommen (die Reihenfolge im Satz darf sich aendern). */
        fun placeholders(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

        /** Technisch: ohne ein Wort aus drei oder mehr Buchstaben, wenn man die Platzhalter weglaesst („%1$s km“, „+%1$d“, „Ø %1$s“, „OK“). */
        fun isTechnical(text: String): Boolean = !WORD.containsMatchIn(PLACEHOLDER.replace(text, ""))
    }
}
