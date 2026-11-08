package de.adv990de.obd2.docs

import de.adv990de.obd2.projectRoot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * B31: Die Pruefungen, die `scripts/release.sh` vor dem Signieren macht — ob Keystore und Gradle-Properties nur der
 * Eigentuemer lesen darf und ob das APK mit dem erwarteten Zertifikat signiert ist — liegen in `scripts/release-lib.sh`
 * und werden hier ECHT ausgefuehrt (bash, temporaere Dateien), nicht nur als Text geprueft. Ein falscher Schluessel
 * (z. B. nach dem Umzug des Keystores) wuerde ein Update erzeugen, das sich ueber die installierte App nicht einspielen laesst.
 */
class ReleaseScriptLibTest {

    private lateinit var tmp: File

    @Before fun setup() {
        assumeTrue("bash fehlt", File("/bin/bash").exists())
        tmp = Files.createTempDirectory("release-lib-test").toFile()
    }

    @After fun teardown() { if (::tmp.isInitialized) tmp.deleteRecursively() }

    private class Result(val exit: Int, val out: String)

    /** Fuehrt [script] in bash aus, nachdem `scripts/release-lib.sh` eingelesen wurde. */
    private fun bash(script: String, env: Map<String, String> = emptyMap(), stdin: String? = null): Result {
        val pb = ProcessBuilder("bash", "-c", "source '${File(projectRoot, "scripts/release-lib.sh")}'\n$script")
            .redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("bash haengt", p.waitFor(30, TimeUnit.SECONDS))
        return Result(p.exitValue(), out.trim())
    }

    private fun fileWithMode(name: String, mode: String, content: String = "x"): File =
        File(tmp, name).also {
            it.writeText(content)
            Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString(mode))
        }

    // ── is_private_file ───────────────────────────────────────────────────

    @Test
    fun `a file only the owner can access is private`() {
        for (mode in listOf("rw-------", "r--------", "rwx------")) {
            val f = fileWithMode("secret-$mode", mode)
            assertEquals("Modus $mode", 0, bash("is_private_file '$f'").exit)
        }
    }

    @Test
    fun `a file readable by group or others is not private - that was the finding (mode 644)`() {
        for (mode in listOf("rw-r--r--", "rw-r-----", "rw----r--", "rw-rw----", "rwxrwxrwx")) {
            val f = fileWithMode("open-$mode", mode)
            assertFalse("Modus $mode darf nicht als privat gelten", bash("is_private_file '$f'").exit == 0)
        }
    }

    // ── signer_sha256 ─────────────────────────────────────────────────────

    private val apksignerOutput = """
        Signer #1 certificate DN: CN=example, O=example, C=DE
        Signer #1 certificate SHA-256 digest: BE3DDD382CC6989E30F7B0EDBA88CA841CD5BCD13C5CB22FC56518EC4E9A10FA
        Signer #1 certificate SHA-1 digest: 9372cbe058642020100884d25a032e06aec61559
    """.trimIndent()

    @Test
    fun `signer_sha256 takes the SHA-256 digest of the first signer, lower case, not the SHA-1`() {
        val r = bash("signer_sha256", stdin = apksignerOutput)

        assertEquals("be3ddd382cc6989e30f7b0edba88ca841cd5bcd13c5cb22fc56518ec4e9a10fa", r.out)
    }

    @Test
    fun `signer_sha256 yields nothing when apksigner printed no certificate`() {
        assertEquals("", bash("signer_sha256", stdin = "Verifies\nDoes not verify").out)
    }

    // ── signer_matches_pin ────────────────────────────────────────────────

    private val pin = "be3ddd382cc6989e30f7b0edba88ca841cd5bcd13c5cb22fc56518ec4e9a10fa"

    @Test
    fun `the pinned certificate matches - regardless of case and trailing newline in the pin file`() {
        val pinFile = File(tmp, "pin").apply { writeText(pin.uppercase() + "\n") }

        assertEquals(0, bash("signer_matches_pin '$pinFile'", stdin = apksignerOutput).exit)
    }

    @Test
    fun `a different certificate does not match the pin - and the message names both`() {
        val pinFile = File(tmp, "pin").apply { writeText("0".repeat(64) + "\n") }

        val r = bash("signer_matches_pin '$pinFile'", stdin = apksignerOutput)

        assertTrue("Exit-Code ungleich 0, war ${r.exit}", r.exit != 0)
        assertTrue("nennt den tatsaechlichen Fingerabdruck: ${r.out}", pin in r.out)
        assertTrue("nennt den erwarteten: ${r.out}", "0".repeat(64) in r.out)
    }

    @Test
    fun `no certificate in the apksigner output never matches - an empty digest must not equal an empty pin`() {
        val emptyPin = File(tmp, "pin").apply { writeText("\n") }

        assertTrue(bash("signer_matches_pin '$emptyPin'", stdin = "nothing useful").exit != 0)
    }

    // ── gradle_property ───────────────────────────────────────────────────

    @Test
    fun `gradle_property prefers the environment over the properties file`() {
        val props = File(tmp, "gradle.properties").apply { writeText("KEYSTORE_FILE=/from/file\nOTHER=1\n") }

        val r = bash("gradle_property KEYSTORE_FILE '$props'", env = mapOf("KEYSTORE_FILE" to "/from/env"))

        assertEquals("/from/env", r.out)
    }

    @Test
    fun `gradle_property reads the key from the properties file and ignores other keys and comments`() {
        val props = File(tmp, "gradle.properties").apply {
            writeText("# KEYSTORE_FILE=/commented\nKEYSTORE_FILE_BACKUP=/other\nKEYSTORE_FILE=/from/file\n")
        }

        assertEquals("/from/file", bash("gradle_property KEYSTORE_FILE '$props'", env = mapOf("KEYSTORE_FILE" to "")).out)
    }

    @Test
    fun `gradle_property is empty when neither environment nor file define the key`() {
        val props = File(tmp, "gradle.properties").apply { writeText("OTHER=1\n") }

        assertEquals("", bash("gradle_property KEYSTORE_FILE '$props'", env = mapOf("KEYSTORE_FILE" to "")).out)
        assertEquals("", bash("gradle_property KEYSTORE_FILE '${File(tmp, "missing")}'", env = mapOf("KEYSTORE_FILE" to "")).out)
    }

    @Test
    fun `gradle_property never prints a secret value for a key it was not asked for`() {
        val props = File(tmp, "gradle.properties").apply { writeText("KEYSTORE_PASSWORD=geheim123\nKEYSTORE_FILE=/k\n") }

        val r = bash("gradle_property KEYSTORE_FILE '$props'", env = mapOf("KEYSTORE_FILE" to ""))

        assertFalse("geheim123" in r.out)
    }

    // ── expand_home ───────────────────────────────────────────────────────

    @Test
    fun `expand_home resolves a leading tilde like Gradle's KEYSTORE_FILE does`() {
        assertEquals("/home/x/keys/a.jks", bash("expand_home '~/keys/a.jks'", env = mapOf("HOME" to "/home/x")).out)
        assertEquals("/abs/a.jks", bash("expand_home '/abs/a.jks'", env = mapOf("HOME" to "/home/x")).out)
        assertEquals("rel/a~b.jks", bash("expand_home 'rel/a~b.jks'", env = mapOf("HOME" to "/home/x")).out)
    }

    // ── Veroeffentlichen als GitHub-Release (--publish) ───────────────────

    private val notesFile: String = """
        # Release Notes — OBD Dashboard

        ## Version 1.45.1 (versionCode 160)

        Neuer Signaturschluessel.

        ### Was sich aendert

        - Eine Zeile.

        ---

        ## Version 1.45.10 (versionCode 170)

        Nur ein Teilstring der anderen Version.

        ---

        ## Version 1.45.0 (versionCode 159)

        Die letzte Version ohne Trennlinie am Ende.
    """.trimIndent() + "\n"

    private fun notes(): File = File(tmp, "RELEASE_NOTES.md").also { it.writeText(notesFile) }

    @Test
    fun `release_notes_section prints the body of one version without its heading and without the separator`() {
        val r = bash("release_notes_section 1.45.1 '${notes()}'")
        assertEquals(0, r.exit)
        assertEquals("Neuer Signaturschluessel.\n\n### Was sich aendert\n\n- Eine Zeile.", r.out)
    }

    @Test
    fun `release_notes_section matches the version exactly - 1_45_1 is not 1_45_10`() {
        val r = bash("release_notes_section 1.45.10 '${notes()}'")
        assertEquals("Nur ein Teilstring der anderen Version.", r.out)
        assertFalse("Neuer Signaturschluessel" in r.out)
    }

    @Test
    fun `release_notes_section does not find 1_45_1 when the file only has 1_45_10`() {
        val only = File(tmp, "nur-zehn.md").also { it.writeText("## Version 1.45.10 (versionCode 170)\n\nNur die lange.\n") }
        val r = bash("release_notes_section 1.45.1 '$only'")
        assertTrue(r.exit != 0)
        assertEquals("", r.out)
    }

    @Test
    fun `release_notes_section stops at the next version heading even without a separator line`() {
        val noSeparators = File(tmp, "ohne-trenner.md").also {
            it.writeText("## Version 2.0.0 (versionCode 2)\n\nZwei.\n\n## Version 1.0.0 (versionCode 1)\n\nEins.\n")
        }
        assertEquals("Zwei.", bash("release_notes_section 2.0.0 '$noSeparators'").out)
    }

    @Test
    fun `release_notes_section starts at the first line of text - no blank line first`() {
        assertEquals("Neuer Signaturschluessel.", bash("release_notes_section 1.45.1 '${notes()}' | head -n 1").out)
    }

    @Test
    fun `release_notes_section reads the last section up to the end of the file`() {
        assertEquals("Die letzte Version ohne Trennlinie am Ende.", bash("release_notes_section 1.45.0 '${notes()}'").out)
    }

    @Test
    fun `release_notes_section fails and prints nothing when the version is not in the file`() {
        val r = bash("release_notes_section 9.9.9 '${notes()}'")
        assertTrue("Exit ${r.exit}", r.exit != 0)
        assertEquals("", r.out)
    }

    @Test
    fun `release_notes_section fails when the file does not exist`() {
        val r = bash("release_notes_section 1.45.1 '${File(tmp, "fehlt.md")}'")
        assertTrue(r.exit != 0)
        assertEquals("", r.out)
    }

    @Test
    fun `sha256_line has the format shasum -c reads - hash, two spaces, name`() {
        assertEquals("ab12  OBD-Dashboard.apk", bash("sha256_line ab12 OBD-Dashboard.apk").out)
    }

    @Test
    fun `the published file name is the fixed one the stable download link depends on`() {
        assertEquals("OBD-Dashboard.apk", bash("printf '%s' \"\$RELEASE_ASSET_NAME\"").out)
    }

    @Test
    fun `compose_release_notes carries the notes, the file name, the apk checksum and the certificate fingerprint`() {
        val apk = "a".repeat(64)
        val cert = "b".repeat(64)
        val r = bash("compose_release_notes 1.45.1 '${notes()}' $apk $cert")
        assertEquals(0, r.exit)
        assertTrue("Notizen fehlen", "Neuer Signaturschluessel." in r.out)
        assertTrue("Dateiname fehlt", "OBD-Dashboard.apk" in r.out)
        assertTrue("APK-Pruefsumme fehlt", apk in r.out)
        assertTrue("Zertifikat fehlt", cert in r.out)
        assertTrue("englischer und deutscher Hinweis", "**English:**" in r.out && "**Deutsch:**" in r.out)
        assertFalse("die Notizen einer anderen Version", "Teilstring" in r.out)
        assertTrue("Die Ueberschrift der Version gehoert nicht in den Text (sie steht im Titel)", "## Version" !in r.out)
    }

    @Test
    fun `compose_release_notes fails without text when the version has no notes - a release must not go out empty`() {
        val r = bash("compose_release_notes 9.9.9 '${notes()}' ${"a".repeat(64)} ${"b".repeat(64)}")
        assertTrue(r.exit != 0)
        assertEquals("", r.out)
    }

    @Test
    fun `compose_release_notes refuses a missing checksum or fingerprint`() {
        assertTrue(bash("compose_release_notes 1.45.1 '${notes()}' '' ${"b".repeat(64)}").exit != 0)
        assertTrue(bash("compose_release_notes 1.45.1 '${notes()}' ${"a".repeat(64)} ''").exit != 0)
    }

    @Test
    fun `publish_confirmed accepts exactly the version in the environment`() {
        assertEquals(0, bash("publish_confirmed 1.45.1", env = mapOf("RELEASE_PUBLISH_CONFIRM" to "1.45.1")).exit)
    }

    @Test
    fun `publish_confirmed refuses anything else - empty, y, another version, padded, a prefix`() {
        for (v in listOf("", "y", "yes", "1", "1.45", "1.45.10", "1.45.0", " 1.45.1", "1.45.1 ", "v1.45.1")) {
            val r = bash("publish_confirmed 1.45.1", env = mapOf("RELEASE_PUBLISH_CONFIRM" to v))
            assertTrue("'$v' darf nicht bestaetigen", r.exit != 0)
        }
    }

    @Test
    fun `publish_confirmed refuses without a terminal and without the environment variable`() {
        val r = bash("unset RELEASE_PUBLISH_CONFIRM; publish_confirmed 1.45.1", stdin = "1.45.1\n")
        assertTrue("eine Eingabe aus einer Pipe ist keine Bestaetigung", r.exit != 0)
    }

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        assertTrue("git haengt", p.waitFor(30, TimeUnit.SECONDS))
        assertEquals("git ${args.toList()}: $out", 0, p.exitValue())
        return out
    }

    @Test
    fun `tag_revision gives the commit an annotated tag points to - not the tag object`() {
        assumeTrue("git fehlt", File("/usr/bin/git").exists() || File("/bin/git").exists() || File("/opt/homebrew/bin/git").exists())
        val repo = File(tmp, "repo").also { it.mkdirs() }
        git(repo, "init", "-q")
        git(repo, "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "--allow-empty", "-m", "eins")
        val first = git(repo, "rev-parse", "HEAD")
        git(repo, "-c", "user.name=t", "-c", "user.email=t@example.invalid", "tag", "-a", "v1.0.0", "-m", "Release 1.0.0")
        git(repo, "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "--allow-empty", "-m", "zwei")
        assertEquals(first, bash("tag_revision v1.0.0 '$repo'").out)
        assertTrue("Der Tag-Objekt-Hash ist nicht die Revision", git(repo, "rev-parse", "v1.0.0") != first)
    }

    @Test
    fun `tag_revision fails and prints nothing for a tag that does not exist`() {
        assumeTrue("git fehlt", File("/usr/bin/git").exists() || File("/bin/git").exists() || File("/opt/homebrew/bin/git").exists())
        val repo = File(tmp, "repo2").also { it.mkdirs() }
        git(repo, "init", "-q")
        git(repo, "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "--allow-empty", "-m", "eins")
        val r = bash("tag_revision v9.9.9 '$repo'")
        assertTrue(r.exit != 0)
        assertEquals("", r.out)
    }

    // ── remote_tag_revision (Ausgabe von git ls-remote --tags) ────────────

    private val lsRemote = listOf(
        "1111111111111111111111111111111111111111\trefs/tags/v1.45.0",
        "2222222222222222222222222222222222222222\trefs/tags/v1.45.0^{}",
        "3333333333333333333333333333333333333333\trefs/tags/v1.45.10",
        "4444444444444444444444444444444444444444\trefs/tags/v1.45.10^{}",
        "5555555555555555555555555555555555555555\trefs/tags/v1.45.1",
    ).joinToString("\n") + "\n"

    @Test
    fun `remote_tag_revision takes the commit of an annotated tag - the peeled line`() {
        assertEquals("2".repeat(40), bash("remote_tag_revision v1.45.0", stdin = lsRemote).out)
    }

    @Test
    fun `remote_tag_revision takes the line of a lightweight tag when there is no peeled one`() {
        assertEquals("5".repeat(40), bash("remote_tag_revision v1.45.1", stdin = lsRemote).out)
    }

    @Test
    fun `remote_tag_revision does not take a tag that only starts like the one asked for`() {
        val r = bash("remote_tag_revision v1.45", stdin = lsRemote)
        assertTrue(r.exit != 0)
        assertEquals("", r.out)
        assertEquals("4".repeat(40), bash("remote_tag_revision v1.45.10", stdin = lsRemote).out)
    }

    @Test
    fun `remote_tag_revision fails when the remote knows no such tag - not pushed`() {
        assertTrue(bash("remote_tag_revision v9.9.9", stdin = lsRemote).exit != 0)
        assertTrue(bash("remote_tag_revision v1.45.1", stdin = "").exit != 0)
    }
}
