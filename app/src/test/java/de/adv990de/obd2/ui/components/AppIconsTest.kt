package de.adv990de.obd2.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.security.MessageDigest

/**
 * [AppIcons]: die sieben Symbole, die aus `material-icons-extended` uebernommen wurden, als Quelltext. Vor dem Entfernen des Pakets wurde jedes gegen das Original geprueft (jede
 * Eigenschaft und jeder Pfad-Knoten gleich); der Fingerabdruck der Knoten haelt diesen Stand fest — wer einen Pfad aendert, tut es mit Absicht und zieht ihn hier nach.
 */
class AppIconsTest {

    private class Spec(val name: String, val icon: ImageVector, val nodes: Int, val sha256: String, val autoMirror: Boolean = false)

    private val specs = listOf(
        Spec("Download", AppIcons.Download, 15, "db3f55853ca414cefbaefc7999c318a82d0b007419e232ca34b426509711acd1"),
        Spec("Upload", AppIcons.Upload, 15, "70ddef214241fc5fea686a57f2ca92db15f43159e5e7f86e6116a7b96e8d34f6"),
        Spec("Book", AppIcons.Book, 17, "33140f6397bf2eedaddf8fa8feb4fd7c9101a8f8a9244e56e2720817dbade784"),
        Spec("PowerSettingsNew", AppIcons.PowerSettingsNew, 18, "81269949dac404491cb54be24f9265313a76dc16e602524fef460b3049a9856c"),
        Spec("LocalGasStation", AppIcons.LocalGasStation, 40, "2a17dd321a06e11ea5f18fe75aa0bda7b0e8f2cf67cb456a4af32f610da86049"),
        Spec("CalendarToday", AppIcons.CalendarToday, 24, "2f977f4d77546e00cbfa26e182475356bf90f5ad30750188033b00d8f078e4a6"),
        Spec("OpenInNew", AppIcons.OpenInNew, 26, "4fc9c61ba03f77bd84c55bb08a58d52eedaaa2cf8333251685598e46915d2466", autoMirror = true),
    )

    private fun path(icon: ImageVector): VectorPath = icon.root.iterator().asSequence().single() as VectorPath

    /** Jeder Knoten als Text; `Close` ist ein einfaches `object` ohne eigenes `toString()` (es enthaelt die Identitaet und wechselt von Lauf zu Lauf), deshalb steht es fest da. */
    private fun fingerprint(icon: ImageVector): String =
        MessageDigest.getInstance("SHA-256")
            .digest(path(icon).pathData.joinToString("|") { if (it == PathNode.Close) "Close" else it.toString() }.toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `every icon is a 24 dp Material icon with one black filled path`() {
        for (spec in specs) {
            val icon = spec.icon
            assertEquals("${spec.name} width", 24.dp, icon.defaultWidth)
            assertEquals("${spec.name} height", 24.dp, icon.defaultHeight)
            assertEquals("${spec.name} viewport width", 24f, icon.viewportWidth, 0f)
            assertEquals("${spec.name} viewport height", 24f, icon.viewportHeight, 0f)
            val path = path(icon)
            assertEquals("${spec.name} fill", SolidColor(Color.Black), path.fill)
            assertNull("${spec.name} has no outline stroke", path.stroke)
            assertEquals("${spec.name} fill type", PathFillType.NonZero, path.pathFillType)
            assertEquals("${spec.name} fill alpha", 1f, path.fillAlpha, 0f)
        }
    }

    @Test
    fun `every path is exactly the one copied from the Material icons`() {
        val changed = specs.filter { fingerprint(it.icon) != it.sha256 }.map { "${it.name}: ${fingerprint(it.icon)}" }
        assertEquals(
            "These paths changed. Intended? Then update the fingerprints here (the paths come from Icons.Filled.<Name>.root of material-icons-extended 1.7.8). Now: $changed",
            emptyList<String>(), changed,
        )
    }

    @Test
    fun `every icon has the number of path nodes of the original`() {
        for (spec in specs) assertEquals("${spec.name}: number of path nodes", spec.nodes, path(spec.icon).pathData.size)
    }

    @Test
    fun `only OpenInNew mirrors in right-to-left layouts`() {
        for (spec in specs) assertEquals("${spec.name} autoMirror", spec.autoMirror, spec.icon.autoMirror)
    }

    @Test
    fun `an icon is built once and handed out again`() {
        assertSame(AppIcons.Download, AppIcons.Download)
        assertSame(AppIcons.LocalGasStation, AppIcons.LocalGasStation)
    }

    @Test
    fun `the seven icons are different drawings`() {
        assertEquals(7, specs.map { fingerprint(it.icon) }.toSet().size)
    }
}
