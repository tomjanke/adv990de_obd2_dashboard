package de.adv990de.obd2

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Jedes `ChoiceSetting(…)` sagt, wie seine Segmente vorgelesen werden: `optionDescription` (Alarm-Schwellen: „12,5 Volt“) oder `optionDescriptionPlural` („20 Sekunden“). Ohne beides
 * läse TalkBack still nur die nackte Zahl vor — „20“ statt „20 Sekunden“ —, und ein neuer Aufrufer merkt es nicht. Ein Laufzeit-`require` im Composable wäre die Alternative gewesen;
 * es ließe die Einstellungsseite bei einem Programmierfehler abstürzen, dieser Test hält ihn stattdessen vor dem Commit auf.
 */
class ChoiceSettingAnnouncementSourceTest {

    private val main = File(projectRoot, "app/src/main/java")

    /** Der Text jedes Aufrufs `ChoiceSetting(…)` bis zur schließenden Klammer; nicht die Definition und nicht die Erwähnung in einem Kommentar. */
    private fun callsIn(text: String): List<String> {
        val calls = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = text.indexOf("ChoiceSetting(", from)
            if (start < 0) break
            from = start + 1
            if (start > 0 && text[start - 1].isLetterOrDigit()) continue                   // `VoltageChoiceSetting(` ist ein anderer Name
            val lineStart = text.lastIndexOf('\n', start) + 1
            val before = text.substring(lineStart, start)
            if (before.contains("fun ") || before.trimStart().startsWith("*") || before.trimStart().startsWith("//") || before.endsWith("`")) continue
            var depth = 0
            var end = start
            for (i in start until text.length) {
                when (text[i]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) { end = i; break } } }
            }
            calls += text.substring(start, end + 1)
        }
        return calls
    }

    private fun allCalls(): List<Pair<String, String>> =
        main.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file -> callsIn(file.readText()).map { file.name to it } }.toList()

    @Test
    fun `every ChoiceSetting call says how its options are spoken`() {
        val calls = allCalls()

        assertTrue("es sollten mindestens fuenf Aufrufe sein (Watchdog, Reconnect, die Wrapper fuer Spannung und Kuehlmittel, die Vorschau), gefunden: ${calls.size}", calls.size >= 5)
        for ((file, call) in calls) {
            assertTrue("$file: ChoiceSetting ohne optionDescription oder optionDescriptionPlural: ${call.take(90).replace('\n', ' ')}", call.contains("optionDescription"))
        }
    }

    @Test
    fun `the call finder does not count the definition or comments`() {
        val text = """
            // ChoiceSetting(a)
            /** Wie `ChoiceSetting(…)` */
            internal fun ChoiceSetting(headline: String)
            ChoiceSetting(headline = "x", optionDescription = { "y" })
        """.trimIndent()

        assertTrue(callsIn(text).size == 1)
    }
}
