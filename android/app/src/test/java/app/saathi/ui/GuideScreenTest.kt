package app.saathi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guide mode is push-to-talk ONLY. Its descriptions contain the very words a command uses, so if
 * the Guide screen opened the microphone after Saathi spoke, Saathi could hear itself say "ADD,
 * Amul Taaza" and act on it (the 13 Oct incidents). This reads the screen's source so a later
 * change can't quietly add auto-listening.
 */
class GuideScreenTest {
    private val src: String = listOf(
        File("src/main/java/app/saathi/ui/screens/GuideScreen.kt"),
        File("app/src/main/java/app/saathi/ui/screens/GuideScreen.kt"),
    ).first { it.isFile }.readText()

    private fun code(s: String): String = s.lines()
        .filterNot { it.trimStart().let { l -> l.startsWith("*") || l.startsWith("/*") || l.startsWith("//") } }
        .joinToString("\n") { it.substringBefore(" // ") }

    @Test
    fun `listening starts only from the user's own press (Speak, talk button, Volume-down)`() {
        val c = code(src)
        val starts = c.lines().filter { "listener.start()" in it }
        assertEquals("only the Volume-down push-to-talk may call start()", 1, starts.size)
        assertTrue(starts.single().contains("TaskRunner.guideTalk.collect"))
        assertFalse("must not wait for Saathi's speech and then listen", "awaitSpeechEnd(" in c)
        assertFalse("must not open the microphone itself", "startListening(" in c)
        assertTrue("Speak toggles listening", "listener.toggle()" in c)
    }

    @Test
    fun `commands carry their timing to the session (echo timing check)`() {
        assertTrue("ScreenParts.rememberCommandListener" in src)
        assertFalse("rememberScreenListener(" in src)
    }

    @Test
    fun `the guide can't answer a Confirm or pick sheet`() {
        val c = code(src)
        assertFalse(Regex("TaskRunner\\.(answer|choose|pick|pickItem)\\(").containsMatchIn(c))
        assertFalse("AskCapsule(" in c)
    }
}
