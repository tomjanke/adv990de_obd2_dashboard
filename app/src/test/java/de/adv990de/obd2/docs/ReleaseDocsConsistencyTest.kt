package de.adv990de.obd2.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Version und versionCode stehen an drei Stellen: `app/build.gradle.kts` (die Quelle), `README.md` ("Aktuelle
 * Version") und der neueste Eintrag von `RELEASE_NOTES.md`. Die README nannte bis zur QS (B39) noch 1.17.7, obwohl
 * 1.23.0 ausgeliefert war — Doku-Drift, den kein Test bemerkte. Diese Pruefung haelt die drei zusammen; sie laeuft in
 * jedem `testDebugUnitTest` und scheitert, sobald ein Release die README oder die Release-Notes vergisst.
 */
class ReleaseDocsConsistencyTest {

    /** Das Projektverzeichnis: von der Arbeitsverzeichnis des Test-JVM (Gradle: das Modul `app/`) nach oben bis `settings.gradle.kts`. */
    private val projectRoot: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("settings.gradle.kts nicht gefunden, ausgehend von ${System.getProperty("user.dir")}")

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
    fun `the README names the version and version code of the build`() {
        val build = buildVersion()
        val line = read("README.md").lineSequence().firstOrNull { it.contains("Aktuelle Version:") }
        assertNotNull("README.md braucht eine Zeile 'Aktuelle Version: **x** (versionCode n)'", line)

        val match = Regex("""Aktuelle Version: \*\*([^*]+)\*\* \(versionCode (\d+)\)""").find(line!!)
        assertNotNull("Format der README-Zeile: $line", match)
        assertEquals("README-Version", build.name, match!!.groupValues[1])
        assertEquals("README-versionCode", build.code, match.groupValues[2].toInt())
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
    fun `the README documents KEYSTORE_FILE and the certificate pin`() {
        val readme = read("README.md")
        assertTrue(readme.contains("KEYSTORE_FILE"))
        assertTrue(readme.contains("release-cert.sha256"))
    }
}
