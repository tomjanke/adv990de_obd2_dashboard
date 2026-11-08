package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

/**
 * Die Suchmuster fuer Begriffe, die nie im oeffentlichen Repository stehen duerfen (Firmenkennung des Arbeitgebers: alter Schluesselname, alter Paketname, Organisation im alten
 * Zertifikat), und das Absuchen des Repositorys danach. Anlass (2026-11-08): beim Dokumentieren des Schluesselwechsels hatte ich die Kennung in `CLAUDE.md` wieder ausgeschrieben —
 * in drei noch nicht veroeffentlichten Commits —, und kein Test merkte es; aufgefallen ist es bei der Pruefung vor dem Push.
 *
 * **Die Muster stehen nicht im Repository.** Ein Muster, das die Kennung nennt, waere selbst eine Fundstelle. Sie liegen in [ForbiddenTerms.FILE] (steht in der `.gitignore`, je Zeile
 * eine Java-Regex, ohne Unterscheidung von Gross- und Kleinschreibung, `#` und Leerzeilen zaehlen nicht). **Fehlt die Datei (ein frischer Klon, der Workflow auf GitHub), werden die
 * Pruefungen uebersprungen — der Schutz gilt also nur dort, wo die Datei liegt.** Die Fehlermeldungen nennen den Fundort und die Nummer des Musters, nicht den Begriff.
 */
internal object ForbiddenTerms {
    /** Relativ zum Projektverzeichnis; der Ordner steht in der `.gitignore`. */
    const val FILE = "local/forbidden-terms.txt"

    private const val MAX_FILE_BYTES = 20L * 1024 * 1024
    private const val BINARY_PROBE_BYTES = 8000

    /** [number] ist die Zeilennummer in der Musterdatei (so laesst sich ein Fund einem Muster zuordnen, ohne den Begriff zu nennen). */
    class Pattern(val number: Int, val regex: Regex)

    /** Die Muster aus den Zeilen der Datei. Eine Datei ohne ein einziges Muster waere ein Test, der immer gruen ist: das ist ein Fehler. */
    fun load(lines: List<String>): List<Pattern> {
        val patterns = lines.withIndex().mapNotNull { (index, raw) ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val regex = try {
                Regex(line, RegexOption.IGNORE_CASE)
            } catch (_: java.util.regex.PatternSyntaxException) {
                throw IllegalArgumentException("Zeile ${index + 1} der Musterdatei ist kein gueltiger regulaerer Ausdruck")
            }
            Pattern(index + 1, regex)
        }
        require(patterns.isNotEmpty()) { "Die Musterdatei enthaelt kein Muster (nur Leerzeilen und Kommentare): ein leerer Test waere immer gruen" }
        return patterns
    }

    /** Die Nummern der Muster, die in [text] treffen. */
    fun matching(text: String, patterns: List<Pattern>): List<Int> = patterns.filter { it.regex.containsMatchIn(text) }.map { it.number }

    /** Eine Datei mit einem NUL-Byte am Anfang gilt als binaer (Bild, Schrift, Archiv) und wird nicht gelesen. */
    fun looksBinary(bytes: ByteArray): Boolean = bytes.take(BINARY_PROBE_BYTES).any { it == 0.toByte() }

    // ── Git ───────────────────────────────────────────────────────────────

    /** Die Fehlerausgabe geht in eine Temp-Datei (nicht in den Strom, den wir lesen; `Redirect.DISCARD` gibt es in der Klassenbibliothek der Unit-Tests nicht). */
    private fun start(dir: File, vararg args: String): Process =
        ProcessBuilder(listOf("git", *args)).directory(dir).redirectError(File.createTempFile("git-stderr", ".txt").apply { deleteOnExit() }).start()

    private fun finish(process: Process, what: String) {
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "git $what schlug fehl" }
    }

    /** Zeile fuer Zeile, ohne das Ergebnis im Speicher zu halten; ungueltige UTF-8-Folgen werden ersetzt, nicht als Fehler gemeldet. */
    private fun forEachLine(stream: InputStream, onLine: (String) -> Unit) {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        InputStreamReader(stream, decoder).buffered().forEachLine(onLine)
    }

    private fun streamGit(dir: File, vararg args: String, onLine: (String) -> Unit) {
        val process = start(dir, *args)
        process.inputStream.use { forEachLine(it, onLine) }
        finish(process, args.first())
    }

    fun isGitRepository(dir: File): Boolean = try {
        val process = ProcessBuilder("git", "rev-parse", "--git-dir").directory(dir).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (_: java.io.IOException) {
        false
    }

    // ── Die drei Pruefungen ───────────────────────────────────────────────

    /** Namen und Text aller getrackten Dateien (binaere und ueber 20 MB grosse werden uebergangen). Fundort: `Pfad:Zeile`. */
    fun scanTracked(dir: File, patterns: List<Pattern>): List<String> {
        val process = start(dir, "ls-files", "-z")
        val raw = process.inputStream.use { it.readBytes() }
        finish(process, "ls-files")
        val hits = mutableListOf<String>()
        for (path in String(raw, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }) {
            for (n in matching(path, patterns)) hits += "$path (Dateiname) [Muster Nr. $n]"
            val file = File(dir, path)
            if (!file.isFile || file.length() > MAX_FILE_BYTES) continue
            val bytes = file.readBytes()
            if (looksBinary(bytes)) continue
            String(bytes, Charsets.UTF_8).lineSequence().forEachIndexed { index, line ->
                for (n in matching(line, patterns)) hits += "$path:${index + 1} [Muster Nr. $n]"
            }
        }
        return hits
    }

    /** Commit-Nachrichten, Autoren und Committer aller Referenzen, Tag-Texte und Namen der Referenzen. */
    fun scanMetadata(dir: File, patterns: List<Pattern>): List<String> {
        val hits = linkedSetOf<String>()
        var where = "?"
        fun scan(line: String) {
            for (n in matching(line, patterns)) hits += "$where [Muster Nr. $n]"
        }
        streamGit(dir, "log", "--all", "--format=%x01Commit %h%n%an%n%ae%n%cn%n%ce%n%B") { line ->
            if (line.startsWith("\u0001")) where = line.substring(1) else scan(line)
        }
        where = "Referenzname"
        streamGit(dir, "for-each-ref", "--format=%(refname)") { scan(it) }
        streamGit(dir, "for-each-ref", "refs/tags", "--format=%01Tag %(refname:short)%0a%(taggername)%0a%(taggeremail)%0a%(contents)") { line ->
            if (line.startsWith("\u0001")) where = line.substring(1) else scan(line)
        }
        return hits.toList()
    }

    /** Jede Zeile jedes Patches der ganzen Historie (auch Text, der spaeter wieder entfernt wurde: er steht im Verlauf). Fundort: `Commit Datei`. */
    fun scanHistory(dir: File, patterns: List<Pattern>): List<String> {
        val hits = linkedSetOf<String>()
        var commit = "?"
        var file = "(Kopf)"
        streamGit(dir, "log", "--all", "-p", "--no-ext-diff", "--format=%x01%h") { line ->
            if (line.startsWith("\u0001")) {
                commit = line.substring(1)
                file = "(Kopf)"
            } else if (line.startsWith("diff --git ")) {
                file = line.substringAfterLast(" b/", line.removePrefix("diff --git "))
            }
            for (n in matching(line, patterns)) hits += "Commit $commit $file [Muster Nr. $n]"
        }
        return hits.toList()
    }
}

/** Das Absuchen des echten Repositorys — nur, wo die Musterdatei liegt. */
class ForbiddenTermsTest {

    private val patternFile = File(projectRoot, ForbiddenTerms.FILE)

    private fun patternsOrSkip(): List<ForbiddenTerms.Pattern> {
        assumeTrue("${ForbiddenTerms.FILE} fehlt: die Pruefung gegen die Firmenkennung wird uebersprungen", patternFile.isFile)
        assumeTrue("kein Git-Repository oder kein git verfuegbar", ForbiddenTerms.isGitRepository(projectRoot))
        return ForbiddenTerms.load(patternFile.readLines())
    }

    private fun assertNoHits(what: String, hits: List<String>) {
        assertTrue("$what (${hits.size} Fundstellen, hoechstens 20 genannt): ${hits.take(20)}", hits.isEmpty())
    }

    @Test
    fun `the gitignore ignores the folder of the local pattern file`() {
        // Immer, auch ohne Musterdatei: ohne diese Zeile koennte sie versehentlich committet werden und waere dann selbst die Fundstelle.
        val lines = File(projectRoot, ".gitignore").readLines().map { it.trim() }
        assertTrue("/local/ fehlt in der .gitignore", "/local/" in lines)
    }

    @Test
    fun `the pattern file is not tracked and git ignores it`() {
        patternsOrSkip()
        val tracked = ProcessBuilder("git", "ls-files", "--", "local").directory(projectRoot).redirectErrorStream(true).start().let {
            val out = it.inputStream.bufferedReader().readLines()
            it.waitFor(30, TimeUnit.SECONDS)
            out
        }
        assertEquals("getrackte Dateien unter local/: $tracked", emptyList<String>(), tracked)
        val ignored = ProcessBuilder("git", "check-ignore", "-q", "--", ForbiddenTerms.FILE).directory(projectRoot).redirectErrorStream(true).start().let {
            it.inputStream.readBytes()
            it.waitFor(30, TimeUnit.SECONDS)
            it.exitValue()
        }
        assertEquals("git ignoriert ${ForbiddenTerms.FILE} nicht", 0, ignored)
    }

    @Test
    fun `no tracked file name or text names a forbidden term`() {
        val patterns = patternsOrSkip()
        assertNoHits("Verbotener Begriff in getrackten Dateien", ForbiddenTerms.scanTracked(projectRoot, patterns))
    }

    @Test
    fun `no commit message, author, committer, tag or reference name names a forbidden term`() {
        val patterns = patternsOrSkip()
        assertNoHits("Verbotener Begriff in Commit-Nachrichten, Autoren, Tags oder Referenznamen", ForbiddenTerms.scanMetadata(projectRoot, patterns))
    }

    @Test
    fun `no patch of the whole history shows a forbidden term`() {
        val patterns = patternsOrSkip()
        assertNoHits("Verbotener Begriff im Verlauf (auch Text, der spaeter entfernt wurde)", ForbiddenTerms.scanHistory(projectRoot, patterns))
    }
}

/**
 * Die Mechanik, ohne die echten Muster: die Muster-Datei, das Lesen und das Absuchen eines Wegwerf-Repositorys mit einem erfundenen Begriff. Das ist die Gegenprobe zu
 * [ForbiddenTermsTest]: ein Absuchen, das wegen eines Fehlers im Aufruf von git nie etwas findet, waere genauso gruen wie ein sauberes Repository.
 */
class ForbiddenTermsMechanicsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sample = ForbiddenTerms.load(listOf("# Kommentar", "", "  ZZQuux  "))

    // ── Die Musterdatei ───────────────────────────────────────────────────

    @Test
    fun `comments and blank lines are not patterns, a pattern is trimmed and matches regardless of case`() {
        val patterns = ForbiddenTerms.load(listOf("# eins", "", "   ", "alpha", "  Beta[0-9]  ", "#gamma"))

        assertEquals(listOf(4, 5), patterns.map { it.number })
        assertEquals(listOf(4), ForbiddenTerms.matching("an ALPHA here", patterns))
        assertEquals(listOf(5), ForbiddenTerms.matching("beta7", patterns))
        assertEquals(listOf(4, 5), ForbiddenTerms.matching("Alpha and BETA3", patterns))
        assertEquals(emptyList<Int>(), ForbiddenTerms.matching("beta and gamma", patterns))
    }

    @Test
    fun `a pattern file without any pattern is an error because its test would always pass`() {
        try {
            ForbiddenTerms.load(listOf("# nur Kommentare", ""))
            fail("eine Datei ohne Muster muss abgelehnt werden")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("kein Muster"))
        }
    }

    @Test
    fun `an invalid regular expression names its line and not the pattern`() {
        try {
            ForbiddenTerms.load(listOf("# eins", "gut", "[kaputt"))
            fail("ein ungueltiger Ausdruck muss abgelehnt werden")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("Zeile 3"))
            assertFalse("die Meldung nennt das Muster", e.message!!.contains("kaputt"))
        }
    }

    @Test
    fun `a file with a NUL byte at the start counts as binary`() {
        assertTrue(ForbiddenTerms.looksBinary(byteArrayOf(0x50, 0x4B, 0x00, 0x01)))
        assertFalse(ForbiddenTerms.looksBinary("nur Text".toByteArray()))
    }

    // ── Ein Wegwerf-Repository ────────────────────────────────────────────

    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(
            listOf("git", "-c", "user.name=Sample Author", "-c", "user.email=sample@example.invalid", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false", "-c", "init.defaultBranch=main", *args),
        ).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.toList()} schlug fehl: $output" }
    }

    private fun newRepository(): File {
        val dir = tmp.newFolder("repo")
        assumeTrue("kein git verfuegbar", try { git(dir, "init", "-q"); true } catch (_: Exception) { false })
        return dir
    }

    private fun commitAll(dir: File, message: String, vararg extra: String) {
        git(dir, "add", "-A")
        git(dir, *extra, "commit", "-q", "-m", message)
    }

    private fun scanAll(dir: File) = Triple(ForbiddenTerms.scanTracked(dir, sample), ForbiddenTerms.scanMetadata(dir, sample), ForbiddenTerms.scanHistory(dir, sample))

    @Test
    fun `a clean repository has no findings`() {
        val dir = newRepository()
        File(dir, "notes.txt").writeText("nothing to see\nhere\n")
        commitAll(dir, "first commit")

        val (tracked, metadata, history) = scanAll(dir)

        assertEquals(emptyList<String>(), tracked)
        assertEquals(emptyList<String>(), metadata)
        assertEquals(emptyList<String>(), history)
    }

    @Test
    fun `a term in the text of a tracked file is found with its file and line`() {
        val dir = newRepository()
        File(dir, "notes.txt").writeText("clean line\nthe zzquux is here\n")
        commitAll(dir, "first commit")

        val (tracked, _, history) = scanAll(dir)

        assertEquals(listOf("notes.txt:2 [Muster Nr. 3]"), tracked)
        assertTrue("der Verlauf kennt den Text auch: $history", history.isNotEmpty())
    }

    @Test
    fun `a term in the name of a tracked file is found`() {
        val dir = newRepository()
        File(dir, "zzquux-notes.txt").writeText("clean\n")
        commitAll(dir, "first commit")

        val (tracked, _, history) = scanAll(dir)

        assertEquals(listOf("zzquux-notes.txt (Dateiname) [Muster Nr. 3]"), tracked)
        assertTrue("der Verlauf kennt den Namen auch (Kopfzeile des Patches): $history", history.isNotEmpty())
    }

    @Test
    fun `a term that was removed again is gone from the files but still found in the history`() {
        val dir = newRepository()
        val file = File(dir, "notes.txt")
        file.writeText("the zzquux is here\n")
        commitAll(dir, "add it")
        file.writeText("clean again\n")
        commitAll(dir, "remove it")

        val (tracked, metadata, history) = scanAll(dir)

        assertEquals("die Datei ist wieder sauber", emptyList<String>(), tracked)
        assertEquals(emptyList<String>(), metadata)
        assertTrue("der Verlauf zeigt den entfernten Text: $history", history.any { it.contains("notes.txt") })
    }

    @Test
    fun `a term in a commit message, an author name or a tag text is found in the metadata`() {
        val dir = newRepository()
        File(dir, "notes.txt").writeText("clean\n")
        commitAll(dir, "mentions zzquux in the message")
        File(dir, "more.txt").writeText("clean too\n")
        commitAll(dir, "second commit", "-c", "user.name=ZZQuux Person")
        git(dir, "tag", "-a", "v1", "-m", "release zzquux")

        val metadata = ForbiddenTerms.scanMetadata(dir, sample)

        assertTrue("Commit-Nachricht nicht gefunden: $metadata", metadata.any { it.startsWith("Commit ") })
        assertTrue("Autorname nicht gefunden: $metadata", metadata.size >= 2)
        assertTrue("Tag-Text nicht gefunden: $metadata", metadata.any { it.startsWith("Tag v1") })
        assertEquals(emptyList<String>(), ForbiddenTerms.scanTracked(dir, sample))
    }

    @Test
    fun `a binary file that holds the term is skipped`() {
        val dir = newRepository()
        File(dir, "image.bin").writeBytes(byteArrayOf(0x00, 0x01) + "zzquux".toByteArray() + byteArrayOf(0x00))
        commitAll(dir, "binary")

        val (tracked, metadata, history) = scanAll(dir)

        assertEquals(emptyList<String>(), tracked)
        assertEquals(emptyList<String>(), metadata)
        assertEquals("git zeigt von einer Binaerdatei keinen Inhalt", emptyList<String>(), history)
    }
}
