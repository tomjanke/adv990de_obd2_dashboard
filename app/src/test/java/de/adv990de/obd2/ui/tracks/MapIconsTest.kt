package de.adv990de.obd2.ui.tracks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die selbst gezeichneten Symbole der Kartenknoepfe: 24 x 24, nicht leer, und die vier sind verschieden. */
class MapIconsTest {

    private val all = mapOf(
        "Fit" to MapIcons.Fit,
        "NorthUp" to MapIcons.NorthUp,
        "HeadingUp" to MapIcons.HeadingUp,
        "Recentre" to MapIcons.Recentre,
    )

    @Test
    fun everyIconIs24By24AndHasContent() {
        for ((name, icon) in all) {
            assertEquals("$name Breite", 24f, icon.defaultWidth.value, 0f)
            assertEquals("$name Hoehe", 24f, icon.defaultHeight.value, 0f)
            assertEquals("$name Viewport", 24f, icon.viewportWidth, 0f)
            assertTrue("$name hat Pfade", icon.root.size > 0)
        }
    }

    @Test
    fun theIconsLookDifferent() {
        val shapes = all.values.map { it.root.toString() }
        assertEquals("keine zwei Symbole sind gleich", shapes.size, shapes.toSet().size)
        assertNotEquals(MapIcons.NorthUp.root.toString(), MapIcons.HeadingUp.root.toString())
    }
}
