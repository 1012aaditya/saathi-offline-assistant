package app.saathi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Floating Saathi (the bubble + panel over other apps) reads these sources so a later change can't
 * quietly weaken it: push-to-talk only (like the Guide screen), questions only through the shared
 * answer sheets, never bringing Saathi's screen forward, never reading or pressing its own windows,
 * one shared Voice (one speak / listen interlock).
 */
class FloatingOverlayTest {
    private fun src(path: String): String =
        listOf(File("src/main/java/app/saathi/$path"), File("app/src/main/java/app/saathi/$path")).first { it.isFile }.readText()

    private fun code(s: String): String = s.lines()
        .filterNot { it.trimStart().let { l -> l.startsWith("*") || l.startsWith("/*") || l.startsWith("//") } }
        .joinToString("\n") { it.substringBefore(" // ") }

    private val panel = code(src("ui/screens/FloatingPanel.kt"))
    private val sheets = code(src("ui/screens/FloatingSheets.kt"))
    private val manager = code(src("overlay/OverlayManager.kt"))
    private val service = code(src("agent/SaathiAccessibilityService.kt"))
    private val runner = code(src("agent/TaskRunner.kt"))

    @Test
    fun `the panel listens only after the user's own press`() {
        val starts = panel.lines().filter { "listener.start()" in it }
        assertEquals("one place may call start(): the push-to-talk gate", 1, starts.size)
        assertTrue(starts.single().contains("if (ptt.mayListen()) listener.start()"))
        assertTrue("a press arms the gate", "ptt.press(at)" in panel)
        assertFalse("toggle() would start listening without the gate", "listener.toggle()" in panel)
        assertFalse("must not wait for Saathi's speech and then listen", "awaitSpeechEnd(" in panel)
        assertFalse("must not open the microphone itself", "startListening(" in panel)
        assertTrue("commands carry their timing", "ScreenParts.rememberCommandListener" in panel)
        assertFalse("rememberScreenListener(" in panel)
    }

    @Test
    fun `no listening survives into Saathi's next turn, and the panel never speaks by itself`() {
        assertTrue(Regex("if \\(!last\\.fromUser\\) \\{\\s*ptt\\.turnSpoken\\(\\)\\s*listener\\.cancel\\(\\)").containsMatchIn(panel))
        assertFalse(Regex("voice\\.speak\\(").containsMatchIn(panel))
    }

    @Test
    fun `the panel can't answer a question itself, only the shared sheets can`() {
        assertFalse(Regex("TaskRunner\\.(answer|choose|pick|pickItem)\\(").containsMatchIn(panel))
        assertTrue("FloatingConfirmSheet(" in panel && "FloatingPickSheet(" in panel)
        assertTrue("ScreenParts.rememberAnswerListener(" in sheets)
        assertTrue("ConfirmDialog(" in sheets && "PickDialog(" in sheets)
        assertTrue("acting taps are armed", Regex("AnswerArming\\.armed\\(").findAll(sheets).count() >= 2)
        assertTrue("closing without an answer cancels", "TaskRunner.answer(Answer.NO, \"floating sheet closed\")" in sheets)
        assertTrue("closing without an answer cancels", "TaskRunner.pickItem(null, \"floating sheet closed\")" in sheets)
    }

    @Test
    fun `the overlay never brings Saathi forward or opens apps`() {
        for ((name, s) in listOf("OverlayManager" to manager, "FloatingPanel" to panel, "FloatingSheets" to sheets)) {
            assertFalse("$name: bringSaathiToFront", "bringSaathiToFront" in s)
            assertFalse("$name: startActivity", "startActivity(" in s)
            assertFalse("$name: MainActivity", "MainActivity" in s)
        }
    }

    @Test
    fun `overlay windows are accessibility overlays that never take focus`() {
        assertTrue("TYPE_ACCESSIBILITY_OVERLAY" in manager)
        assertFalse("no 'display over other apps' permission", "TYPE_APPLICATION_OVERLAY" in manager)
        assertTrue("FLAG_NOT_FOCUSABLE" in manager)
    }

    @Test
    fun `one Voice for the whole app`() {
        assertTrue("SharedDeps.acquire(" in manager)
        assertFalse("Voice(" in manager)
        val activity = code(src("MainActivity.kt"))
        assertTrue("SharedDeps.acquire(" in activity)
        assertFalse(Regex("\\bVoice\\(").containsMatchIn(activity))
    }

    @Test
    fun `Saathi reads only app windows and injected touches pass through the overlay`() {
        assertTrue(".filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }" in service)
        assertTrue("currentRoot falls back only to a non-overlay root", "TYPE_ACCESSIBILITY_OVERLAY" in service)
        val tapAt = service.substringAfter("private suspend fun tapAt(").substringBefore("private suspend fun gesture(")
        assertTrue("taps by coordinates go through the overlay check", "throughOverlay(listOf(p))" in tapAt)
        assertTrue("swipes too", Regex("throughOverlay\\(listOf\\(Point\\(x1").containsMatchIn(tapAt))
        assertTrue("clicks on Saathi's own nodes are refused", "refused click on Saathi's own UI" in service)
    }

    @Test
    fun `a floating run is never brought to Saathi's screen to ask`() {
        val fronts = runner.lines().filter { "bringSaathiToFront(context)" in it && "fun bringSaathiToFront" !in it }
        assertTrue(fronts.isNotEmpty())
        for (line in fronts) assertTrue("unguarded: $line", "isFloatingRun(id)" in line)
    }
}
