package app.saathi.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every sheet that waits for a spoken answer must hear it through the ONE shared pipeline:
 * ScreenParts.rememberAnswerListener -> its dialog's hear(heard, sources) -> VoiceAnswer (agent
 * module). 13 Oct: three sheets built in parallel each had their own checks, and the grocery
 * "which one?" sheet took Saathi's own words as a pick. This test reads the screen sources, so a
 * new sheet can't quietly use the generic listener (or no checks at all).
 */
class AnswerSheetsTest {
    private val screens: File = listOf(File("src/main/java/app/saathi/ui/screens"), File("app/src/main/java/app/saathi/ui/screens"))
        .first { it.isDirectory }

    private fun sources(): Map<String, String> =
        screens.listFiles { f -> f.extension == "kt" }!!.associate { it.name to it.readText() }

    /** Calls that act on a sheet's answer (send / choose / pick / open an app). */
    private val acting = Regex("TaskRunner\\.(answer|choose|pick|pickItem)\\(|LaunchRequests\\.(approve|decline)\\(")

    private val dialogs = listOf("ConfirmDialog(", "DraftChoiceDialog(", "PickDialog(", "ContactChoiceDialog(")

    /** A screen is an answer sheet if it acts on an answer or shows the "Needs your answer" capsule / pane. */
    private fun isAnswerSheet(src: String) =
        acting.containsMatchIn(src) || "AskCapsule(" in src || "paneTitle = \"Needs your answer\"" in src

    @Test
    fun `the known answer sheets are found`() {
        val sheets = sources().filter { (name, src) -> name != "ScreenParts.kt" && isAnswerSheet(src) }.keys
        for (expected in listOf("ConfirmScreen.kt", "DraftChoiceScreen.kt", "OrderPickScreen.kt", "ContactChoiceScreen.kt", "AppApprovalScreen.kt", "FloatingSheets.kt")) {
            assertTrue("$expected should be detected as an answer sheet (found $sheets)", expected in sheets)
        }
    }

    @Test
    fun `every answer sheet hears only through the shared pipeline`() {
        for ((name, src) in sources()) {
            if (name == "ScreenParts.kt" || !isAnswerSheet(src)) continue
            assertTrue("$name must use ScreenParts.rememberAnswerListener", "ScreenParts.rememberAnswerListener(" in src)
            assertFalse("$name must not use the generic rememberScreenListener", "rememberScreenListener(" in src)
            assertTrue("$name must answer with one of the shared dialogs $dialogs", dialogs.any { it in src })
            assertTrue("$name must pass the timed transcript and sources to its dialog", Regex("dialog\\.hear\\(heard,").containsMatchIn(src))
            assertFalse("$name must not start the microphone itself", "startListening(" in src)
            assertFalse("$name must not read raw transcripts", "Voice.Listen.Heard" in src)
        }
    }

    @Test
    fun `screens with the generic listener never act on an answer`() {
        for ((name, src) in sources()) {
            if (name == "ScreenParts.kt" || "rememberScreenListener(" !in src) continue
            assertFalse("$name uses the generic listener and must not send / choose / pick / open", acting.containsMatchIn(src))
        }
    }

    @Test
    fun `only ScreenParts and the Listening screen open the microphone`() {
        for ((name, src) in sources()) {
            if (name == "ScreenParts.kt" || name == "ListeningScreen.kt") continue
            assertFalse("$name must not call startListening", "startListening(" in src)
            assertFalse("$name must not read raw transcripts", "Voice.Listen.Heard" in src)
        }
    }

    @Test
    fun `the generic listener has no switch to turn its echo check off`() {
        val parts = sources().getValue("ScreenParts.kt")
        assertFalse("genericEchoCheck" in parts)
    }

    /** The code without comments (KDoc / line comments may explain the rules in words). */
    private fun code(src: String): String = src.lines()
        .filterNot { it.trimStart().let { l -> l.startsWith("*") || l.startsWith("/*") || l.startsWith("//") } }
        .joinToString("\n") { it.substringBefore(" // ") }

    @Test
    fun `pick sheets never say a position (it would make a real first look like an echo)`() {
        val src = sources()
        for (name in listOf("OrderPickScreen.kt", "ContactChoiceScreen.kt", "FloatingSheets.kt")) {
            val s = code(src.getValue(name))
            assertFalse("$name: no \"Option N\"", "Option \${" in s)
            assertFalse("$name: no \"N of M\"", "of \${options.size}" in s)
            assertFalse("$name: no \"first\" / \"second\" in its texts", Regex("[“\"](first|second|third)[”\"]", RegexOption.IGNORE_CASE).containsMatchIn(s))
        }
    }
}
