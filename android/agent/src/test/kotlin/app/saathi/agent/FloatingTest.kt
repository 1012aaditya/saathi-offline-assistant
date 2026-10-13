package app.saathi.agent

import app.saathi.safety.Action
import app.saathi.safety.Answer
import app.saathi.safety.Apps
import app.saathi.safety.Bounds
import app.saathi.safety.OpenApp
import app.saathi.safety.Point
import app.saathi.safety.SafetyGate
import app.saathi.safety.Tap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Floating Saathi's pure parts: when the bubble / panel show, what a press means, the panel's
 * phase, never injecting a touch onto the overlay, and that the floating answer sheets hear only
 * through the same dialogs (ConfirmDialog / PickDialog -> VoiceAnswer) as the full-screen ones.
 */
class FloatingTest {
    private val own = "app.saathi"
    private val wa = "com.whatsapp"

    private fun vis(
        enabled: Boolean = true,
        front: String? = wa,
        locked: Boolean = false,
        sensitive: Boolean = false,
        hidden: Boolean = false,
        panel: Boolean = false,
    ) = Floating.visibility(Floating.Inputs(enabled, front, own, locked, sensitive, hidden, panel))

    // -- visibility --------------------------------------------------------------------------------

    @Test
    fun `bubble shows over other apps when the switch is on`() {
        val v = vis()
        assertTrue(v.bubble)
        assertFalse(v.panel)
        assertEquals(Floating.Why.SHOWN, v.why)
        assertTrue(vis(panel = true).panel)
    }

    @Test
    fun `nothing is drawn when off, locked, over Saathi itself, over system UI or with no app`() {
        for ((v, why) in listOf(
            vis(enabled = false, panel = true) to Floating.Why.OFF,
            vis(locked = true, panel = true) to Floating.Why.LOCKED,
            vis(front = own, panel = true) to Floating.Why.SAATHI_IN_FRONT,
            vis(front = "com.android.systemui", panel = true) to Floating.Why.SYSTEM_UI,
            vis(front = null, panel = true) to Floating.Why.NO_APP,
        )) {
            assertFalse(v.bubble, "$why: bubble")
            assertFalse(v.panel, "$why: panel")
            assertEquals(why, v.why)
        }
    }

    @Test
    fun `password and PIN screens hide the whole overlay, panel included`() {
        val v = vis(sensitive = true, panel = true)
        assertFalse(v.bubble)
        assertFalse(v.panel)
        assertEquals(Floating.Why.SENSITIVE, v.why)
    }

    @Test
    fun `a bubble the user hid stays hidden but an open session panel still shows`() {
        assertFalse(vis(hidden = true).bubble)
        assertFalse(vis(hidden = true).panel)
        val withSession = vis(hidden = true, panel = true)
        assertFalse(withSession.bubble)
        assertTrue(withSession.panel)
    }

    // -- presses -------------------------------------------------------------------------------------

    @Test
    fun `a press starts, talks, adopts or restarts on the app in front, never switching apps`() {
        assertEquals(Floating.Press.START, Floating.press(Floating.Run.NONE, null, wa, usable = true))
        assertEquals(Floating.Press.TALK, Floating.press(Floating.Run.FLOATING_GUIDE, wa, wa, usable = true))
        assertEquals(Floating.Press.RESTART, Floating.press(Floating.Run.FLOATING_GUIDE, "com.grofers.customerapp", wa, usable = true))
        assertEquals(Floating.Press.ADOPT, Floating.press(Floating.Run.APP_GUIDE, wa, wa, usable = true))
        assertEquals(Floating.Press.RESTART, Floating.press(Floating.Run.APP_GUIDE, null, wa, usable = true))
    }

    @Test
    fun `a running task is only shown (with Stop), nothing starts on unusable screens`() {
        assertEquals(Floating.Press.SHOW_BUSY, Floating.press(Floating.Run.TASK, null, wa, usable = true))
        assertEquals(Floating.Press.SHOW_BUSY, Floating.press(Floating.Run.TASK, null, wa, usable = false))
        for (run in listOf(Floating.Run.NONE, Floating.Run.FLOATING_GUIDE, Floating.Run.APP_GUIDE)) {
            assertEquals(Floating.Press.IGNORE, Floating.press(run, wa, wa, usable = false))
            assertEquals(Floating.Press.IGNORE, Floating.press(run, wa, null, usable = true))
        }
    }

    // -- panel phase -----------------------------------------------------------------------------------

    @Test
    fun `panel phase follows the session`() {
        val p = Floating::phase
        assertEquals(Floating.Phase.STARTING, p(Floating.RunView.Starting, false, false))
        assertEquals(Floating.Phase.YOUR_MOVE, p(Floating.RunView.Turn(lastFromUser = false), false, false))
        assertEquals(Floating.Phase.WORKING, p(Floating.RunView.Turn(lastFromUser = true), false, false))
        assertEquals(Floating.Phase.LISTENING, p(Floating.RunView.Turn(false), true, false))
        assertEquals(Floating.Phase.THINKING, p(Floating.RunView.Turn(true), false, true))
        assertEquals(Floating.Phase.CONFIRM, p(Floating.RunView.Confirm, true, true))
        assertEquals(Floating.Phase.PICK, p(Floating.RunView.Pick, false, false))
        assertEquals(Floating.Phase.YOUR_TURN, p(Floating.RunView.Ended(handedOver = true), false, false))
        assertEquals(Floating.Phase.ENDED, p(Floating.RunView.Ended(handedOver = false), false, false))
        assertEquals(Floating.Phase.BUSY, p(Floating.RunView.Busy, false, false))
    }

    @Test
    fun `Speak works only while the conversation waits for the user (never during a question)`() {
        val on = Floating.Phase.entries.filter(Floating::speakEnabled).toSet()
        assertEquals(setOf(Floating.Phase.YOUR_MOVE, Floating.Phase.LISTENING), on)
    }

    // -- never touching the overlay ----------------------------------------------------------------------

    private val panel = Bounds(0, 1400, 1080, 2340)
    private val bubble = Bounds(1000, 600, 1080, 680)

    @Test
    fun `a tap on or next to the overlay is caught, elsewhere it is not`() {
        val rects = listOf(panel, bubble)
        assertTrue(Floating.gestureHitsOverlay(listOf(Point(540, 1500)), rects))
        assertTrue(Floating.gestureHitsOverlay(listOf(Point(1040, 640)), rects))
        assertTrue(Floating.gestureHitsOverlay(listOf(Point(540, 1395)), rects), "within the finger margin")
        assertFalse(Floating.gestureHitsOverlay(listOf(Point(540, 1300)), rects))
        assertFalse(Floating.gestureHitsOverlay(listOf(Point(540, 1500)), emptyList()))
    }

    @Test
    fun `a swipe crossing the panel is caught even when both ends are outside`() {
        val rects = listOf(Bounds(0, 1000, 1080, 1100))
        assertTrue(Floating.gestureHitsOverlay(listOf(Point(540, 900), Point(540, 1300)), rects))
        assertFalse(Floating.gestureHitsOverlay(listOf(Point(540, 300), Point(540, 900)), rects))
    }

    @Test
    fun `bubble snaps to the nearer edge and stays on screen`() {
        assertEquals(0, Floating.snapX(300, 160, 1080))
        assertEquals(920, Floating.snapX(700, 160, 1080))
        assertEquals(100, Floating.clampY(-50, 160, 2340, top = 100))
        assertEquals(2180, Floating.clampY(5000, 160, 2340))
    }

    // -- answer sheets: same arming, same dialogs ------------------------------------------------------

    @Test
    fun `acting taps are armed only after 700 ms`() {
        assertFalse(AnswerArming.armed(1_000, 1_000))
        assertFalse(AnswerArming.armed(1_000, 1_699))
        assertTrue(AnswerArming.armed(1_000, 1_700))
    }

    @Test
    fun `nothing the floating panel shows or says contains a yes word or a position word`() {
        for (s in FloatingTexts.all) {
            assertFalse(ConfirmDialog.containsYesWord(s), "yes word in: $s")
            assertFalse(Ordinals.containsOrdinal(s), "position word in: $s")
        }
    }

    private val en = Texts(Lang.EN)
    private val question = en.confirmQuestion("Message yourself", "saathi test two")
    private val confirmSources = FloatingTexts.all + listOf(
        question, en.confirmHint, FloatingTexts.cancelSendDesc("Your text stays as it was."),
        FloatingTexts.sendDesc("saathi test two", "Message yourself"), "saathi test two", "Message yourself",
    )

    private fun windows(text: String): List<String> {
        val w = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        return (1..w.size).flatMap { n -> w.windowed(n).map { it.joinToString(" ") } }
    }

    @Test
    fun `the floating Confirm never sends on its own words, at any timing`() {
        for (src in confirmSources) for (heard in windows(src)) for (ms in listOf(0L, 1_200L, 2_500L, Heard.LONG_AFTER)) {
            val d = ConfirmDialog()
            assertNotEquals(ConfirmDialog.Next.SEND, d.hear(Heard(heard, ms), confirmSources), "\"$heard\" at $ms ms")
        }
    }

    @Test
    fun `the floating Confirm still hears a real short yes and a no`() {
        assertEquals(ConfirmDialog.Next.SEND, ConfirmDialog().hear(Heard("haan bhej do", 2_500), confirmSources))
        assertEquals(ConfirmDialog.Next.SEND, ConfirmDialog().hear(Heard("yes", 2_500), confirmSources))
        assertEquals(ConfirmDialog.Next.CANCEL, ConfirmDialog().hear(Heard("no", 2_500), confirmSources))
        // Too soon after Saathi spoke: unclear, then the safe default.
        val d = ConfirmDialog()
        assertEquals(ConfirmDialog.Next.ASK_AGAIN, d.hear(Heard("yes", 900), confirmSources))
        assertEquals(ConfirmDialog.Next.CANCEL, d.hear(Heard("yes", 900), confirmSources))
    }

    @Test
    fun `the floating pick hears a position, never an echo of its own sheet`() {
        val options = listOf("Mother Dairy Cow Toned Fresh Milk Pouch, 500 ml, ₹28", "Amul Taaza Toned Milk, 500 ml, ₹27")
        val q = "Which one: Mother Dairy Cow Toned Fresh Milk Pouch or Amul Taaza Toned Milk?"
        val sources = FloatingTexts.all + q + options.map { "$it. ${FloatingTexts.PICK_OPTION_SUFFIX}" }
        assertEquals(PickDialog.Next.Picked(1), PickDialog(options).hear(Heard("doosra", 2_500), sources))
        assertEquals(PickDialog.Next.Cancel, PickDialog(options).hear(Heard("cancel", 2_500), sources))
        for (src in sources + options) for (heard in windows(src)) {
            if (EchoGuard.tokens(heard).size <= VoiceAnswers.MAX_NAME_WORDS) continue // a bare name after the gap is how you pick
            for (ms in listOf(0L, 2_500L)) {
                assertFalse(PickDialog(options).hear(Heard(heard, ms), sources) is PickDialog.Next.Picked, "\"$heard\" at $ms ms")
            }
        }
        for (heard in options.flatMap(::windows)) {
            assertFalse(PickDialog(options).hear(Heard(heard, 900), sources) is PickDialog.Next.Picked, "echo timing: \"$heard\"")
        }
    }

    // -- a guide session driven from the floating panel never switches apps ---------------------------

    /** The floating panel's side of a session: [front] is the app in front; it can't be changed. */
    private class FloatingIO(private val front: String, commands: List<String>, override val floating: Boolean = true) : GuideIO {
        private val queue = ArrayDeque(commands.map { Heard(it) })
        val said = mutableListOf<String>()
        var showSaathiCalls = 0
        override suspend fun say(turn: GuideTurn) { said += turn.text }
        override suspend fun next(timeoutMs: Long): Heard? = queue.removeFirstOrNull()
        override suspend fun showApp(packageName: String): Boolean = packageName == front
        override suspend fun showSaathi() { showSaathiCalls++ }
        override suspend fun confirm(request: ConfirmRequest): Answer = Answer.NO
        override suspend fun pick(request: PickRequest): Int? = null
    }

    private val shopPkg = "com.example.shop"
    private fun shopApp() = StaticApp(shopPkg, listOf(S.el(0, text = "Details", cls = "Button", clickable = true), S.el(1, text = "Reviews", cls = "Button", clickable = true)))

    @Test
    fun `floating - the session works on the app in front`() = runBlocking {
        val app = shopApp()
        val io = FloatingIO(shopPkg, listOf("tap details", "done"))
        val out = GuideSession(GatedDevice(SafetyGate(), app), Lang.EN, io, target = shopPkg).run()
        assertEquals(listOf<Action>(Tap(0)), app.performed)
        assertTrue(out is Outcome.Answered, "$out")
    }

    @Test
    fun `floating - another app in front is never opened or switched to`() = runBlocking {
        val app = shopApp() // the shop is in front, but the session is about WhatsApp
        val io = FloatingIO(shopPkg, listOf("done"))
        val out = GuideSession(GatedDevice(SafetyGate(), app), Lang.EN, io, target = Apps.WHATSAPP).run()
        assertTrue(app.performed.none { it is OpenApp }, "${app.performed}")
        assertTrue(app.performed.isEmpty())
        assertEquals(Outcome.Failed(GuideTexts(Lang.EN).notInFront), out)
    }

    @Test
    fun `the Guide-screen session (not floating) still reopens a safety-list app through the gate`() = runBlocking {
        val app = shopApp()
        val io = FloatingIO(shopPkg, listOf("done"), floating = false)
        GuideSession(GatedDevice(SafetyGate(), app), Lang.EN, io, target = Apps.WHATSAPP).run()
        assertTrue(app.performed.any { it is OpenApp }, "${app.performed}")
    }
}
