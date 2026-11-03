package de.adv990de.obd2.docs

import de.adv990de.obd2.docs.ReadmeFiles.DE
import de.adv990de.obd2.docs.ReadmeFiles.EN
import de.adv990de.obd2.docs.ReadmeFiles.SHORT
import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Die drei READMEs (2026-11-03, Auftrag des Nutzers: "eine kurze, praegnante Readme in deutsch und englisch und dann eine ausfuehrliche Readme in englisch und deutsch").
 *
 *  - `README.md`: die Kurzfassung, Englisch zuerst, Deutsch darunter wie SECURITY.md und CONTRIBUTING.md; sie nennt **keine Version** (die steht nur in den ausfuehrlichen
 *    Fassungen, sonst muesste ein Release drei Stellen anfassen), wiederholt aber die Warnungen in beiden Sprachen.
 *  - `docs/README.en.md` und `docs/README.de.md`: die ausfuehrlichen Fassungen, ein Text in zwei Sprachen. Zwei Dateien laufen auseinander, sobald jemand nur eine
 *    pflegt; der Test haelt fest, was sich maschinell vergleichen laesst: die Gliederung, die Code-Bloecke (ohne Kommentare und Platzhalter), die Tabellen, die Link-Ziele,
 *    die Zahlen und die Code-Begriffe im Text. Ob ein Satz in der anderen Sprache dasselbe sagt, prueft er nicht.
 *  - Jeder relative Link und jedes Bild aller drei fuehrt zu einer Datei, die es gibt, und zu einer Ueberschrift, die es gibt.
 */
class ReadmeFilesTest {

    private val emailAddress = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+""")
    private val versionNumber = Regex("""\b\d+\.\d+\.\d+\b""")
    private val bigNumber = Regex("""\b\d{3,}\b""")

    // ── Bausteine ─────────────────────────────────────────────────────────

    /** Text ausserhalb der Code-Bloecke (Zeilen zwischen ```), Zeile fuer Zeile. */
    private fun proseLines(text: String): List<String> {
        var inFence = false
        val out = mutableListOf<String>()
        for (line in text.lines()) {
            if (line.trim().startsWith("```")) { inFence = !inFence; continue }
            if (!inFence) out += line
        }
        return out
    }

    /** Die Code-Bloecke, jeweils als Liste ihrer Zeilen (ohne die Zaeune). */
    private fun fencedBlocks(text: String): List<List<String>> {
        val blocks = mutableListOf<List<String>>()
        var current: MutableList<String>? = null
        for (line in text.lines()) {
            if (line.trim().startsWith("```")) {
                if (current == null) current = mutableListOf() else { blocks += current; current = null }
            } else current?.add(line)
        }
        return blocks
    }

    /** Ein Code-Block ohne Kommentare (Zeilenende und ganze Zeilen) und mit gleichgemachten Platzhaltern `<…>`: Kommentare und Platzhalter sind uebersetzt. */
    private fun normalized(block: List<String>): List<String> = block
        .map { it.replace(Regex("""\s+#.*$"""), "") }
        .filterNot { it.trimStart().startsWith("#") }
        .map { it.replace(Regex("<[^>]*>"), "<>").trimEnd() }

    private fun headings(text: String): List<Pair<Int, String>> = proseLines(text)
        .mapNotNull { Regex("""^(#{1,6}) (.*)$""").find(it)?.let { m -> m.groupValues[1].length to m.groupValues[2] } }

    /** Der Anker, den GitHub aus einer Ueberschrift macht: klein, ohne Satzzeichen, Leerzeichen zu Bindestrichen. */
    private fun slug(heading: String): String =
        heading.lowercase().replace(Regex("""[^\p{L}\p{N}\s_-]"""), "").trim().replace(Regex("""\s"""), "-")

    private fun linkTargets(text: String): List<String> = Regex("""\]\(([^)\s]+)\)""").findAll(text).map { it.groupValues[1] }.toList()

    private fun tableShape(text: String): List<List<Int>> {
        val tables = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        for (line in proseLines(text)) {
            if (line.startsWith("|")) current += Regex("""(?<!\\)\|""").findAll(line).count()
            else if (current.isNotEmpty()) { tables += current; current = mutableListOf() }
        }
        if (current.isNotEmpty()) tables += current
        return tables
    }

    private fun codeSpans(text: String): List<String> =
        Regex("""`([^`\n]+)`""").findAll(proseLines(text).joinToString("\n")).map { it.groupValues[1].replace(Regex("<[^>]*>"), "<>") }.toList()

    private fun <T> counts(items: List<T>): Map<T, Int> = items.groupingBy { it }.eachCount()

    /** Was in [a] haeufiger vorkommt als in [b] — fuer eine Fehlermeldung, die zeigt, WAS auseinanderlaeuft. */
    private fun <T> surplus(a: List<T>, b: List<T>): List<T> {
        val cb = counts(b)
        return counts(a).filter { (k, v) -> v > (cb[k] ?: 0) }.keys.toList()
    }

    // ── Die Kurzfassung ───────────────────────────────────────────────────

    @Test
    fun `the short README is English first, German below, and points to both detailed READMEs`() {
        val text = ReadmeFiles.read(SHORT)
        val english = text.indexOf("\n## English\n")
        val german = text.indexOf("\n## Deutsch\n")
        assertTrue("README.md hat den englischen Teil nicht", english >= 0)
        assertTrue("README.md hat den deutschen Teil nicht", german >= 0)
        assertTrue("README.md: der englische Teil muss vor dem deutschen stehen", english < german)
        assertTrue("README.md verweist nicht auf $EN", text.contains("](docs/README.en.md)"))
        assertTrue("README.md verweist nicht auf $DE", text.contains("](docs/README.de.md)"))
    }

    @Test
    fun `the short README repeats the warnings in both languages`() {
        val text = ReadmeFiles.read(SHORT)
        val english = text.substringBefore("\n## Deutsch\n")
        val german = text.substringAfter("\n## Deutsch\n")
        for (needle in listOf("Do not operate it while riding", "Read-only", "No warranty", "Apache License 2.0", "Not affiliated with ELM Electronics")) {
            assertTrue("Der englische Teil von README.md nennt '$needle' nicht", english.contains(needle))
        }
        for (needle in listOf("Nicht während der Fahrt bedienen", "Rein lesend", "Keine Gewähr", "Apache License 2.0", "Keine Verbindung zu ELM Electronics")) {
            assertTrue("Der deutsche Teil von README.md nennt '$needle' nicht", german.contains(needle))
        }
    }

    @Test
    fun `every README says that the code was written with the help of an AI - in the language of each part`() {
        // Auftrag des Nutzers (2026-11-03): der KI-Hinweis gehoert in die READMEs. Er steht in beiden Sprachen der Kurzfassung und in beiden ausfuehrlichen Fassungen.
        val short = ReadmeFiles.read(SHORT)
        val parts = mapOf(
            "Kurzfassung, englischer Teil" to (short.substringBefore("\n## Deutsch\n") to "Written with AI assistance"),
            "Kurzfassung, deutscher Teil" to (short.substringAfter("\n## Deutsch\n") to "Mit KI-Hilfe entstanden"),
            "$EN" to (ReadmeFiles.read(EN) to "Written with AI assistance"),
            "$DE" to (ReadmeFiles.read(DE) to "Mit KI-Hilfe entstanden"),
        )
        for ((where, pair) in parts) {
            val (text, heading) = pair
            assertTrue("$where nennt '$heading' nicht", text.contains(heading))
            assertTrue("$where nennt Claude (Anthropic) nicht", text.contains("Claude (Anthropic)"))
        }
    }

    @Test
    fun `the short README names no version - only the detailed READMEs do`() {
        // Die Version steht in docs/README.en.md und docs/README.de.md ("Current version" / "Aktuelle Version", ReleaseDocsConsistencyTest). Eine dritte Stelle waere bei
        // jedem Release eine mehr, die man vergessen kann.
        val text = ReadmeFiles.read(SHORT)
        assertFalse("README.md nennt eine Versionsnummer: ${versionNumber.findAll(text).map { it.value }.toList()}", versionNumber.containsMatchIn(text))
    }

    @Test
    fun `the short README links the files a visitor needs in both languages`() {
        val text = ReadmeFiles.read(SHORT)
        val parts = mapOf("englisch" to text.substringBefore("\n## Deutsch\n"), "deutsch" to text.substringAfter("\n## Deutsch\n"))
        for (target in listOf("CONTRIBUTING.md", "SECURITY.md", "CODE_OF_CONDUCT.md", "LICENSE", "NOTICE", "RELEASE_NOTES.md")) {
            for ((language, part) in parts) assertTrue("Der $language Teil von README.md verlinkt $target nicht", part.contains("]($target)"))
        }
        for ((language, part) in parts) assertTrue("Der $language Teil von README.md nennt den Bau-Befehl nicht", part.contains("./gradlew assembleDebug"))
    }

    // ── Alle drei ─────────────────────────────────────────────────────────

    @Test
    fun `no README names a mail address`() {
        for (path in ReadmeFiles.all) {
            assertFalse("$path nennt eine E-Mail-Adresse — im Repository steht bewusst keine", emailAddress.containsMatchIn(ReadmeFiles.read(path)))
        }
    }

    @Test
    fun `every relative link and image of the three READMEs points to a file and a heading that exist`() {
        for (path in ReadmeFiles.all) {
            val text = ReadmeFiles.read(path)
            val directory = File(projectRoot, path).parentFile
            for (target in linkTargets(text)) {
                if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) continue
                val file = target.substringBefore('#')
                val anchor = target.substringAfter('#', "")
                val resolved = if (file.isEmpty()) File(projectRoot, path) else File(directory, file).canonicalFile
                assertTrue("$path: der Link '$target' fuehrt zu keiner Datei ($resolved)", resolved.exists())
                if (anchor.isNotEmpty()) {
                    val slugs = headings(resolved.readText()).map { slug(it.second) }
                    assertTrue("$path: der Link '$target' fuehrt zu keiner Ueberschrift (es gibt $slugs)", anchor in slugs)
                }
            }
        }
    }

    // ── Die beiden ausfuehrlichen Fassungen laufen gleich ─────────────────

    @Test
    fun `the detailed READMEs have the same headings in the same order`() {
        val de = headings(ReadmeFiles.read(DE))
        val en = headings(ReadmeFiles.read(EN))
        assertEquals("Die Ebenen der Ueberschriften ($DE gegen $EN)", de.map { it.first }, en.map { it.first })
        // Ein Anker-Link in einer Datei meint eine Ueberschrift derselben Datei: dieselbe Stelle in der anderen Sprache
        // (Berechtigungen / Permissions) ist durch die gleiche Reihenfolge schon festgelegt.
    }

    @Test
    fun `the detailed READMEs have the same code blocks apart from comments and placeholders`() {
        val de = fencedBlocks(ReadmeFiles.read(DE)).map(::normalized)
        val en = fencedBlocks(ReadmeFiles.read(EN)).map(::normalized)
        assertEquals("Zahl der Code-Bloecke ($DE gegen $EN)", de.size, en.size)
        for (i in de.indices) assertEquals("Code-Block ${i + 1} ($DE gegen $EN), ohne Kommentare und Platzhalter", de[i], en[i])
    }

    @Test
    fun `the detailed READMEs have the same tables`() {
        assertEquals("Zeilen und Spalten der Tabellen ($DE gegen $EN)", tableShape(ReadmeFiles.read(DE)), tableShape(ReadmeFiles.read(EN)))
    }

    @Test
    fun `the detailed READMEs name the same files, numbers and code terms`() {
        val de = ReadmeFiles.read(DE)
        val en = ReadmeFiles.read(EN)
        // Der Link zur jeweils anderen Sprache ist der einzige, der sich unterscheiden muss.
        val sibling = { target: String -> if (target == "README.en.md" || target == "README.de.md") "<andere Sprache>" else target }
        val deLinks = linkTargets(de).filterNot { it.startsWith("#") }.map(sibling)
        val enLinks = linkTargets(en).filterNot { it.startsWith("#") }.map(sibling)
        assertEquals("Link-Ziele ($DE gegen $EN): nur deutsch ${surplus(deLinks, enLinks)}, nur englisch ${surplus(enLinks, deLinks)}", counts(deLinks), counts(enLinks))

        fun numbers(text: String) = proseLines(text).joinToString("\n").let { versionNumber.findAll(it).map { m -> m.value } + bigNumber.findAll(it).map { m -> m.value } }.toList()
        val deNumbers = numbers(de)
        val enNumbers = numbers(en)
        assertEquals("Versionen und Zahlen ab drei Stellen ($DE gegen $EN): nur deutsch ${surplus(deNumbers, enNumbers)}, nur englisch ${surplus(enNumbers, deNumbers)}", counts(deNumbers), counts(enNumbers))

        val deSpans = codeSpans(de)
        val enSpans = codeSpans(en)
        assertEquals("Code-Begriffe im Text ($DE gegen $EN): nur deutsch ${surplus(deSpans, enSpans)}, nur englisch ${surplus(enSpans, deSpans)}", counts(deSpans), counts(enSpans))
    }
}
