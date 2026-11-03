package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Version und versionCode stehen an vier Stellen: `app/build.gradle.kts` (die Quelle), `docs/README.de.md` ("Aktuelle Version"), `docs/README.en.md` ("Current
 * version") und der neueste Eintrag von `RELEASE_NOTES.md`. (Die Kurzfassung `README.md` nennt keine Version, `ReadmeFilesTest` haelt das fest.) Die README nannte
 * bis zur QS (B39) noch 1.17.7, obwohl 1.23.0 ausgeliefert war — Doku-Drift, den kein Test bemerkte. Diese Pruefung haelt sie zusammen; sie laeuft in
 * jedem `testDebugUnitTest` und scheitert, sobald ein Release eine der READMEs oder die Release-Notes vergisst.
 */
class ReleaseDocsConsistencyTest {

    /** Das Projektverzeichnis: von der Arbeitsverzeichnis des Test-JVM (Gradle: das Modul `app/`) nach oben bis `settings.gradle.kts`. */
    private fun read(relative: String): String = File(projectRoot, relative).readText()

    private data class Version(val name: String, val code: Int)

    private fun buildVersion(): Version {
        val gradle = read("app/build.gradle.kts")
        val name = Regex("""val appVersionName = "([^"]+)"""").find(gradle)?.groupValues?.get(1)
        val code = Regex("""val appVersionCode = (\d+)""").find(gradle)?.groupValues?.get(1)?.toInt()
        assertNotNull("appVersionName in build.gradle.kts", name)
        assertNotNull("appVersionCode in build.gradle.kts", code)
        return Version(name!!, code!!)
    }

    @Test
    fun `the detailed READMEs name the version and version code of the build`() {
        val build = buildVersion()
        for ((path, label) in listOf(ReadmeFiles.DE to "Aktuelle Version", ReadmeFiles.EN to "Current version")) {
            val line = read(path).lineSequence().firstOrNull { it.contains("$label:") }
            assertNotNull("$path braucht eine Zeile '$label: **x** (versionCode n)'", line)

            val match = Regex("""$label: \*\*([^*]+)\*\* \(versionCode (\d+)\)""").find(line!!)
            assertNotNull("Format der Zeile in $path: $line", match)
            assertEquals("Version in $path", build.name, match!!.groupValues[1])
            assertEquals("versionCode in $path", build.code, match.groupValues[2].toInt())
        }
    }

    @Test
    fun `the detailed READMEs and CLAUDE_md name the target and compile SDK of the build`() {
        // targetSdk/compileSdk stehen in beiden ausfuehrlichen READMEs ("targetSdk **37** (Android 17) · compileSdk **37**") und CLAUDE.md ("targetSdk 37 / compileSdk 37").
        // Der Sprung auf compileSdk 37 (v1.26.0) und auf targetSdk 37 haette in allen Dateien sonst stillschweigend alt weiterbehauptet.
        val gradle = read("app/build.gradle.kts")
        val compile = Regex("""compileSdk = (\d+)""").find(gradle)?.groupValues?.get(1)
        val target = Regex("""targetSdk = (\d+)""").find(gradle)?.groupValues?.get(1)
        assertNotNull("compileSdk in build.gradle.kts", compile)
        assertNotNull("targetSdk in build.gradle.kts", target)

        for ((path, label) in listOf(ReadmeFiles.DE to "Aktuelle Version", ReadmeFiles.EN to "Current version")) {
            val readme = read(path).lineSequence().first { it.contains("$label:") }
            assertEquals("targetSdk in $path", target, Regex("""targetSdk \*\*(\d+)\*\*""").find(readme)?.groupValues?.get(1))
            assertEquals("compileSdk in $path", compile, Regex("""compileSdk \*\*(\d+)\*\*""").find(readme)?.groupValues?.get(1))
        }

        val claude = Regex("""targetSdk (\d+) / compileSdk (\d+)""").find(read("CLAUDE.md"))
        assertNotNull("CLAUDE.md braucht 'targetSdk N / compileSdk N' im Projekt-Kontext", claude)
        assertEquals("CLAUDE.md targetSdk", target, claude!!.groupValues[1])
        assertEquals("CLAUDE.md compileSdk", compile, claude.groupValues[2])
    }

    @Test
    fun `the newest release notes entry is the version of the build`() {
        val build = buildVersion()
        val header = read("RELEASE_NOTES.md").lineSequence().firstOrNull { it.startsWith("## Version ") }
        assertNotNull("RELEASE_NOTES.md braucht einen Eintrag '## Version x (versionCode n)'", header)

        val match = Regex("""## Version (\S+) \(versionCode (\d+)\)""").find(header!!)
        assertNotNull("Format der Kopfzeile: $header", match)
        assertEquals("Release-Notes-Version", build.name, match!!.groupValues[1])
        assertEquals("Release-Notes-versionCode", build.code, match.groupValues[2].toInt())
    }

    @Test
    fun `the version code only ever goes up between consecutive release notes entries`() {
        val entries = Regex("""## Version (\S+) \(versionCode (\d+)\)""").findAll(read("RELEASE_NOTES.md"))
            .map { it.groupValues[2].toInt() }.toList()

        assertEquals("die Eintraege stehen neueste zuerst, jeder hat einen hoeheren Code als der naechste", entries.sortedDescending(), entries)
        assertEquals("kein versionCode doppelt", entries.size, entries.toSet().size)
    }

    @Test
    fun `the release script is syntactically valid bash`() {
        val script = File(projectRoot, "scripts/release.sh")
        assertTrue("scripts/release.sh fehlt", script.isFile)

        val process = ProcessBuilder("bash", "-n", script.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals("bash -n: $output", 0, process.waitFor())
    }

    // ── B31: Signatur und Geheimnisse ─────────────────────────────────────

    @Test
    fun `the pinned release certificate is a sha-256 fingerprint`() {
        val pin = read("scripts/release-cert.sha256").trim()
        assertTrue("64 Hex-Zeichen erwartet, war: '$pin'", Regex("[0-9a-f]{64}").matches(pin))
    }

    @Test
    fun `the release script uses the checks of the lib - permissions of the secrets and the certificate pin`() {
        val script = read("scripts/release.sh")
        assertTrue("laedt die Pruefungen", script.contains("release-lib.sh"))
        assertTrue("prueft die Dateirechte", script.contains("is_private_file"))
        assertTrue("prueft das Zertifikat gegen den Pin", script.contains("signer_matches_pin") && script.contains("release-cert.sha256"))
    }

    @Test
    fun `the build reads the keystore location from KEYSTORE_FILE and signs whenever it is configured`() {
        val gradle = read("app/build.gradle.kts")
        assertTrue("KEYSTORE_FILE wird gelesen", gradle.contains("""prop("KEYSTORE_FILE")"""))
        assertTrue(
            "ein konfigurierter Pfad aktiviert die Signierung (AGP bricht ab, wenn er fehlt) statt still unsigniert zu bauen",
            gradle.contains("configuredKeystore != null || keystoreFile.exists()"),
        )
    }

    @Test
    fun `the detailed READMEs document KEYSTORE_FILE and the certificate pin`() {
        for (path in ReadmeFiles.detailed) {
            val readme = read(path)
            assertTrue("$path nennt KEYSTORE_FILE nicht", readme.contains("KEYSTORE_FILE"))
            assertTrue("$path nennt release-cert.sha256 nicht", readme.contains("release-cert.sha256"))
        }
    }

    // ── Tag-Befehl: eine Form an allen Stellen ────────────────────────────

    /** Die Zeilen von [relative], die [needle] nennen — mit Zeilennummer, damit ein Fehlschlag die Stelle zeigt. */
    private fun linesWith(relative: String, needle: String): List<String> =
        read(relative).lines().withIndex().filter { needle in it.value }.map { "$relative:${it.index + 1}: ${it.value.trim()}" }

    @Test
    fun `every place that names the release tag command uses an annotated tag with the release message`() {
        // README ("Tag-Konvention: annotierte Tags"), Schritt 4 der README, der Hinweis darunter, der Kopf von release.sh und dessen Abschlusszeile
        // nannten zwei Formen: `git tag -a … -m …` und das leichte `git tag vX.Y.Z`. Die ausgelieferten Tags sind annotiert ("Release 1.32.9 (versionCode 119)").
        // Im Skript steht der Befehl in einem `echo "…"`, die Anfuehrungszeichen sind dort maskiert (`\"`). Beide ausfuehrlichen READMEs nennen ihn gleich.
        val form = Regex("""git tag -a v\S+ -m \\?"Release \S+ \(versionCode \S+\)\\?"""")
        for (file in ReadmeFiles.detailed + "scripts/release.sh") {
            val mentions = linesWith(file, "git tag ")
            assertTrue("$file nennt den Tag-Befehl mindestens zweimal, war: $mentions", mentions.size >= 2)
            mentions.forEach { assertTrue("kein annotierter Tag mit Release-Meldung: $it", form.containsMatchIn(it)) }
        }
    }

    @Test
    fun `every place that pushes a release pushes main together with the tag, never all tags`() {
        // `git push && git push --tags` schickt jeden lokalen Tag mit; die Freigabeliste der Sitzung erlaubt nur `origin main` und `origin v*`.
        for (file in ReadmeFiles.detailed + "scripts/release.sh") {
            val mentions = linesWith(file, "git push")
            assertTrue("$file nennt den Push mindestens zweimal, war: $mentions", mentions.size >= 2)
            mentions.forEach {
                assertTrue("Push ohne `origin main v<Tag>`: $it", Regex("""git push origin main v\S+""").containsMatchIn(it))
                assertTrue("`--tags` schickt alle Tags: $it", "--tags" !in it)
            }
        }
    }
}
