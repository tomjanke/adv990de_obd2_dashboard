package de.adv990de.obd2

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [projectRoot] is the one place that finds the project directory for the source-reading tests (the docs package, the
 * licence catalogue). Nine test classes used to carry their own copy of the lookup — each with a Kotlin compiler warning,
 * because `System.getProperty` is nullable.
 */
class ProjectRootTest {

    @Test
    fun `the project root is the directory with settings gradle kts`() {
        assertTrue(File(projectRoot, "settings.gradle.kts").isFile)
    }

    @Test
    fun `the project root holds the app module and the docs`() {
        assertTrue(File(projectRoot, "app/src/main/AndroidManifest.xml").isFile)
        assertTrue(File(projectRoot, "docs").isDirectory)
    }
}
