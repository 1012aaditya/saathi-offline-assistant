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
        assertEquals("one place may call start(): the push-to-talk gate", 1, starts.size)
        assertTrue("start() only after a fresh press", starts.single().contains("if (ptt.mayListen()) listener.start()"))
        assertTrue("a press arms the gate", "ptt.press(at)" in c)
        assertFalse("toggle() would start listening without the gate", "listener.toggle()" in c)
        assertTrue("Speak and the talk button press through talk()", Regex("SpeakButton\\(listener.active\\) \\{ talk\\(\\) }").containsMatchIn(c) && "onTalk = { talk() }" in c)
        assertTrue("Volume-down presses through talk() with its own time", "TaskRunner.guideTalk.collect { at -> if (!listener.active) talk(at) }" in c)
        assertFalse("must not wait for Saathi's speech and then listen", "awaitSpeechEnd(" in c)
        assertFalse("must not open the microphone itself", "startListening(" in c)
    }

    @Test
    fun `no listening survives into Saathi's next turn (14 Oct 00-40, 'read more' heard back)`() {
        val c = code(src)
        assertTrue(
            "each new Saathi turn disarms the press and cancels listening",
            Regex("if \\(!last\\.fromUser\\) \\{\\s*ptt\\.turnSpoken\\(\\)\\s*listener\\.cancel\\(\\)").containsMatchIn(c),
        )
        assertTrue("the read-more hint is shown on screen", "lastSaathi?.hint" in c)
        assertFalse("the screen never speaks by itself", Regex("voice\\.speak\\(").containsMatchIn(c))
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
