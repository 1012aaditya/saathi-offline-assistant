package app.saathi.agent

import app.saathi.safety.Answer
import app.saathi.safety.SafetyGate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 13 Oct, 21:30 on the tablet: a Reply reached Mummy's chat with leftover text "saathi test
 * please ignore"; with no human answer the draft sheet chose REPLACE and the Confirm sheet said
 * yes, and "saathi test two" was sent. These tests replay Saathi's own prompts from that run
 * as if the microphone had transcribed them, and check the speak/listen interlock.
 */
class EchoGuardTest {
    private val en = Texts(Lang.EN)
    private val hi = Texts(Lang.HI)
    private val leftover = "saathi test please ignore"
    private val reply = "saathi test two"

    // What was spoken on 13 Oct (the old confirm wording), and what is spoken now.
    private val oldConfirmEn = "Send to Mummy: \"$reply\"? Say yes or no."
    private val oldConfirmHi = "Mummy को भेजूँ: \"$reply\"? हाँ या नहीं बोलिए।"
    private val newConfirmEn = en.confirmQuestion("Mummy", reply)
    private val newConfirmHi = hi.confirmQuestion("Mummy", reply)
    private val draftEn = en.draftQuestion("Mummy", leftover, canChange = true)
    private val draftHi = hi.draftQuestion("Mummy", leftover, canChange = true)
    private val draftEnTwoWay = en.draftQuestion("Mummy", leftover, canChange = false)

    /** The gate's own Hindi read-back (what the Hindi Confirm sheet speaks), from a real run. */
    private val gateReadback: String by lazy { realReadback() }

    private val confirmPrompts get() = listOf(
        oldConfirmEn, oldConfirmHi, newConfirmEn, newConfirmHi, gateReadback,
        "Sorry, I'm not sure. $newConfirmEn", "Sorry, I'm not sure. $gateReadback",
    )
    private val draftPrompts get() = listOf(draftEn, draftHi, draftEnTwoWay, "Sorry, I'm not sure. $draftEn")

    /** Every run of consecutive words in [text] (what a recognizer could have caught of it). */
    private fun windows(text: String, minLen: Int = 1): List<String> {
        val w = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        return (minLen..w.size).flatMap { n -> w.windowed(n).map { it.joinToString(" ") } }
    }

    // -- the confirm question: an echo is never a yes ------------------------------------------

    @Test
    fun `today's confirm prompts replayed as transcripts never send`() {
        for (prompt in confirmPrompts) {
            for (heard in windows(prompt)) {
                // As the sheet does it: the prompt itself is an echo source.
                assertNotEquals(ConfirmDialog.Next.SEND, ConfirmDialog().hear(heard, listOf(prompt)), "echo \"$heard\" of \"$prompt\"")
            }
            // The whole prompt, twice: unclear, then cancel.
            val d = ConfirmDialog()
            assertEquals(ConfirmDialog.Next.ASK_AGAIN, d.hear(prompt, listOf(prompt)))
            assertEquals(ConfirmDialog.Next.CANCEL, d.hear(prompt, listOf(prompt)))
        }
    }

    @Test
    fun `the new confirm prompts can't produce a yes even with no echo sources`() {
        for (prompt in listOf(newConfirmEn, newConfirmHi, gateReadback, en.confirmHint, hi.confirmHint)) {
            assertFalse(ConfirmDialog.containsYesWord(prompt), prompt)
            for (heard in windows(prompt)) assertNotEquals(Answer.YES, ConfirmDialog.classify(heard), "\"$heard\"")
        }
    }

    @Test
    fun `send or bhejo alone is not a yes`() {
        listOf("send", "Send.", "send it", "send it now", "bhejo", "bhej do", "भेज", "भेज दो", "भेजो", "ok", "okay", "theek hai", "sure", "confirm", "Send to Mummy")
            .forEach { assertEquals(Answer.UNCLEAR, ConfirmDialog.classify(it), it) }
        assertEquals(ConfirmDialog.Next.ASK_AGAIN, ConfirmDialog().hear("send"))
        val d = ConfirmDialog()
        d.hear("bhejo")
        assertEquals(ConfirmDialog.Next.CANCEL, d.hear("send it")) // unclear twice -> cancel
    }

    @Test
    fun `a short explicit yes from the user sends`() {
        val yes = listOf("yes", "Yes.", "yeah", "haan", "haa", "ha", "हाँ", "हां", "ji haan", "haan ji", "yes send it", "haan bhej do", "हाँ भेज दो", "yes please")
        for (prompt in listOf(newConfirmEn, newConfirmHi, gateReadback)) {
            val sources = listOf(prompt, en.confirmHint, hi.confirmHint, "Send to Mummy?", reply)
            yes.forEach { assertEquals(ConfirmDialog.Next.SEND, ConfirmDialog().hear(it, sources), "\"$it\" after \"$prompt\"") }
        }
    }

    @Test
    fun `long, mixed or decorated answers are not a yes`() {
        listOf("yes I think so maybe", "yes send it to mummy now", "yes mummy", "haan saathi test two", "yes no", "haan nahi", "हाँ नहीं")
            .forEach { assertEquals(Answer.UNCLEAR, ConfirmDialog.classify(it), it) }
        listOf("no", "nahi", "नहीं", "don't send", "do not send", "cancel", "mat bhejo", "no don't")
            .forEach { assertEquals(Answer.NO, ConfirmDialog.classify(it), it) }
    }

    @Test
    fun `a yes word that is in the prompt is not trusted`() {
        // The message itself says "haan": a "haan" may be Saathi reading it, so it is unclear.
        val prompt = en.confirmQuestion("Mummy", "haan main aa rahi hoon")
        assertEquals(ConfirmDialog.Next.ASK_AGAIN, ConfirmDialog().hear("haan", listOf(prompt)))
        assertEquals(ConfirmDialog.Next.ASK_AGAIN, ConfirmDialog().hear("yes", listOf(oldConfirmEn))) // 13 Oct wording
        // ...but "no" still cancels (the safe side).
        assertEquals(ConfirmDialog.Next.CANCEL, ConfirmDialog().hear("no", listOf(prompt)))
    }

    // -- the draft question: an echo never changes the user's text ------------------------------

    @Test
    fun `today's draft prompts replayed as transcripts never replace, add or send`() {
        val sheet = listOf("Replace with my message", "Add my message after it", "Send it as it is", "Keep it. This is the default. I won't change or send anything.")
        for (prompt in draftPrompts) {
            // Any 3+ word stretch of the question (or the whole of it): never a change.
            for (heard in windows(prompt, minLen = 3)) {
                val next = DraftChoiceDialog().hear(heard, canChange = true, echoSources = listOf(prompt) + sheet)
                assertTrue(next == DraftChoiceDialog.Next.ASK_AGAIN || next == DraftChoiceDialog.Next.KEEP, "echo \"$heard\" -> $next")
            }
            val d = DraftChoiceDialog()
            assertEquals(DraftChoiceDialog.Next.ASK_AGAIN, d.hear(prompt, true, listOf(prompt)))
            assertEquals(DraftChoiceDialog.Next.KEEP, d.hear(prompt, true, listOf(prompt))) // twice -> keep
        }
        // TalkBack reading a button aloud is an echo too.
        assertEquals(DraftChoiceDialog.Next.ASK_AGAIN, DraftChoiceDialog().hear("replace with my message", true, sheet))
        assertEquals(DraftChoiceDialog.Next.ASK_AGAIN, DraftChoiceDialog().hear("add my message after it", true, sheet))
    }

    @Test
    fun `short choices from the user still work`() {
        val sources = listOf(draftEn, draftHi)
        assertEquals(DraftChoiceDialog.Next.REPLACE, DraftChoiceDialog().hear("badal do", true, sources))
        assertEquals(DraftChoiceDialog.Next.REPLACE, DraftChoiceDialog().hear("बदल दो", true, sources))
        assertEquals(DraftChoiceDialog.Next.ADD, DraftChoiceDialog().hear("add my message", true, sources))
        assertEquals(DraftChoiceDialog.Next.ADD, DraftChoiceDialog().hear("jod do", true, sources))
        assertEquals(DraftChoiceDialog.Next.SEND, DraftChoiceDialog().hear("bhej do", true, sources))
        assertEquals(DraftChoiceDialog.Next.KEEP, DraftChoiceDialog().hear("rehne do", true, sources))
        // The Hindi question's own "बदल दूँ" is an echo; the user's "बदल दो" isn't.
        assertEquals(DraftChoiceDialog.Next.ASK_AGAIN, DraftChoiceDialog().hear("बदल दूँ", true, sources))
        // A change has to be short.
        assertNull(DraftChoiceDialog.classify("please replace my old text with the new one now"))
    }

    // -- end to end: the 13 Oct run, with the sheets hearing only Saathi -----------------------

    @Test
    fun `replay of 13 Oct - sheets that hear only Saathi keep the text and send nothing`() {
        val fake = draftWorld()
        val out = runReply(
            fake,
            choose = { req ->
                val q = en.draftQuestion(req.chat, req.existing, req.canChange)
                val d = DraftChoiceDialog()
                // The mic catches the question (and its repeat) instead of the user.
                var next = d.hear(q, req.canChange, listOf(q))
                if (next == DraftChoiceDialog.Next.ASK_AGAIN) next = d.hear("keep it replace it with your message", req.canChange, listOf(q))
                assertEquals(DraftChoiceDialog.Next.KEEP, next)
                DraftChoice.KEEP
            },
            confirm = { error("nothing may reach the confirmation") },
        )
        assertEquals(en.draftKept, out.message)
        assertTrue(fake.sent.isEmpty())
        assertEquals(leftover, fake.drafts["Mummy"])
    }

    @Test
    fun `replay of 13 Oct - a confirm sheet that hears only Saathi cancels and restores the text`() {
        val fake = draftWorld()
        var asked: ConfirmRequest? = null
        val out = runReply(
            fake,
            choose = { DraftChoice.REPLACE }, // say the user really chose Replace
            confirm = { req ->
                asked = req
                val spoken = listOf(req.readback, en.confirmQuestion(req.recipient, req.message), oldConfirmEn)
                val d = ConfirmDialog()
                var next = ConfirmDialog.Next.ASK_AGAIN
                // Every fragment of what Saathi said, as the transcript: never SEND.
                for (s in spoken) for (heard in windows(s)) {
                    assertNotEquals(ConfirmDialog.Next.SEND, ConfirmDialog().hear(heard, spoken), "\"$heard\"")
                }
                for (s in spoken.take(2)) next = d.hear(s, spoken)
                assertEquals(ConfirmDialog.Next.CANCEL, next)
                Answer.NO
            },
        )
        assertTrue(out is Outcome.Cancelled, out.toString())
        assertEquals(reply, asked?.message)
        assertTrue(fake.sent.isEmpty(), "nothing may be sent")
        assertEquals(leftover, fake.drafts["Mummy"]) // the user's own text is back
    }

    // -- the interlock: never listening while Saathi speaks -------------------------------------

    private class Clock(var t: Long = 0L) { fun now() = t }

    @Test
    fun `the mic can't open while Saathi speaks, nor sooner than 700 ms after`() {
        val c = Clock()
        val v = VoiceInterlock(c::now)
        assertFalse(v.speechStarted(newConfirmEn)) // nothing was listening
        assertTrue(v.speaking)
        val id = v.requestListen() // e.g. the talk button pressed mid-question (Voice stops speech first)
        assertNull(v.msUntilMicMayOpen())
        assertFalse(v.open(id))
        c.t = 3_000; v.speechEnded()
        assertEquals(700L, v.msUntilMicMayOpen())
        c.t = 3_699; assertFalse(v.open(id))
        c.t = 3_700; assertTrue(v.open(id))
    }

    @Test
    fun `stopped speech that is still audible (TTS buffer) restarts the gap - the mic waits for real silence`() {
        val c = Clock()
        val v = VoiceInterlock(c::now)
        v.speechStarted("And 42 more.")
        c.t = 1_000; v.speechEnded() // the user pressed Speak: Voice stopped the speech
        val id = v.requestListen()
        c.t = 1_500; v.stillAudible() // ...but the engine is still playing "read more"
        c.t = 1_700; assertEquals(500L, v.msUntilMicMayOpen())
        assertFalse(v.open(id))
        c.t = 2_200; assertTrue(v.open(id))
        // A transcript is timed from when the sound really stopped.
        c.t = 2_900; assertEquals(EchoGuard.Reason.TOO_SOON_AFTER_SPEECH, v.judge(id))
    }

    @Test
    fun `push-to-talk - only a fresh press opens the mic, once, a Saathi turn disarms it (14 Oct 00-40)`() {
        val c = Clock(10_000)
        val p = PushToTalk(c::now)
        // Saathi speaks a turn: nothing was pressed, so nothing may listen (no auto-listen after a turn).
        p.turnSpoken()
        assertFalse(p.mayListen())
        c.t = 15_000; assertFalse(p.mayListen()) // nor later, by itself
        // The user presses Speak: one session.
        p.press(); assertTrue(p.mayListen())
        assertFalse(p.mayListen(), "a press is used once")
        // A press, then Saathi's next turn before it was used: disarmed.
        c.t = 20_000; p.press(); c.t = 20_100; p.turnSpoken(); assertFalse(p.mayListen())
        // A Volume-down hold delivered late (made before the latest turn, or too old): never counts.
        c.t = 30_000; p.turnSpoken(); p.press(at = 29_900); assertFalse(p.mayListen())
        c.t = 40_000; p.press(at = 37_000); assertFalse(p.mayListen())
        c.t = 41_000; p.press(at = 40_500); assertTrue(p.mayListen())
    }

    @Test
    fun `Saathi speaking during a session cancels it and its result is discarded`() {
        val c = Clock()
        val v = VoiceInterlock(c::now)
        val id = v.requestListen()
        assertTrue(v.open(id))
        c.t = 500
        assertTrue(v.speechStarted("Sorry, I'm not sure. $newConfirmEn")) // caller cancels the recognizer
        assertFalse(v.isCurrent(id))
        c.t = 9_000; v.speechEnded()
        c.t = 20_000
        assertEquals(EchoGuard.Reason.SPOKE_DURING_LISTENING, v.judge(id))
        // A session waiting to open is cancelled the same way.
        val waiting = v.requestListen()
        assertTrue(v.speechStarted("Typing your message"))
        assertFalse(v.open(waiting))
    }

    @Test
    fun `a result right after Saathi's speech is discarded, a later one is used`() {
        val c = Clock()
        val v = VoiceInterlock(c::now)
        v.speechStarted(newConfirmEn)
        c.t = 3_000; v.speechEnded()
        val early = v.requestListen()
        c.t = 3_700; assertTrue(v.open(early))
        c.t = 4_400 // 1.4 s after the speech ended
        assertEquals(EchoGuard.Reason.TOO_SOON_AFTER_SPEECH, v.judge(early))
        v.close(early)

        // Genuine "haan" after the question: tapped at 3.1 s, mic at 3.7 s, heard at 5.5 s -> YES.
        val c2 = Clock()
        val v2 = VoiceInterlock(c2::now)
        v2.speechStarted(newConfirmEn)
        c2.t = 3_000; v2.speechEnded()
        c2.t = 3_100; val id = v2.requestListen()
        c2.t = 3_700; assertTrue(v2.open(id))
        c2.t = 5_500
        assertEquals(EchoGuard.Reason.NONE, v2.judge(id))
        assertEquals(ConfirmDialog.Next.SEND, ConfirmDialog().hear("haan", v2.recentPrompts() + en.confirmHint))
    }

    @Test
    fun `timing rules`() {
        fun t(open: Long, heard: Long, start: Long?, end: Long?, speaking: Boolean = false) =
            EchoGuard.timing(EchoGuard.Timing(open, heard, start, end, speaking))
        assertEquals(EchoGuard.Reason.NONE, t(0, 2_000, null, null)) // Saathi hasn't spoken
        assertEquals(EchoGuard.Reason.SPOKE_DURING_LISTENING, t(0, 5_000, 0, 3_000, speaking = true))
        assertEquals(EchoGuard.Reason.SPOKE_DURING_LISTENING, t(1_000, 9_000, 0, null)) // never finished
        assertEquals(EchoGuard.Reason.SPOKE_DURING_LISTENING, t(1_000, 9_000, 2_000, 4_000)) // spoke while listening
        assertEquals(EchoGuard.Reason.SPOKE_DURING_LISTENING, t(3_500, 9_000, 0, 3_000)) // mic opened 500 ms after
        assertEquals(EchoGuard.Reason.TOO_SOON_AFTER_SPEECH, t(3_700, 4_499, 0, 3_000))
        assertEquals(EchoGuard.Reason.NONE, t(3_700, 4_500, 0, 3_000))
    }

    @Test
    fun `recent prompts and the loose check for other screens`() {
        val c = Clock()
        val v = VoiceInterlock(c::now)
        v.speechStarted("Hi, I'm Saathi. Press Continue to set up.")
        c.t = 2_000; v.speechEnded()
        c.t = 200_000
        v.speechStarted("Sorry, I didn't catch that. You can say Continue, or Change language.")
        c.t = 203_000; v.speechEnded()
        assertEquals(listOf("Sorry, I didn't catch that. You can say Continue, or Change language."), v.recentPrompts(15_000))
        val sources = v.recentPrompts(15_000)
        assertTrue(EchoGuard.looksLikeEcho("you can say continue", sources))
        assertTrue(EchoGuard.looksLikeEcho("continue or change language", sources))
        assertFalse(EchoGuard.looksLikeEcho("continue", sources))
        assertFalse(EchoGuard.looksLikeEcho("change language", sources))
        assertFalse(EchoGuard.looksLikeEcho("read mom's last message", listOf("Mom's last text message: \"see you\"")))
    }

    // -- helpers ----------------------------------------------------------------------------------

    private fun draftWorld() = FakeWhatsApp(
        chats = linkedMapOf("Mummy" to mutableListOf(Msg("them", "Call me"))),
    ).also { it.drafts["Mummy"] = leftover }

    private fun runReply(
        fake: FakeWhatsApp,
        choose: suspend (DraftChoiceRequest) -> DraftChoice,
        confirm: suspend (ConfirmRequest) -> Answer,
    ): Outcome = runBlocking {
        val ctx = Names.contextFor("Mummy", "x")
        AgentLoop(GatedDevice(SafetyGate(), fake), en, drafts = MemoryDraftStore()).run(
            ReplyFlow("Mummy", reply, ctx, en),
            ctx,
            {},
            choose = { req -> fake.pkg = "app.saathi"; choose(req) },
        ) { req -> fake.pkg = "app.saathi"; confirm(req) }
    }

    /** Run a reply on an empty box and take the gate's read-back from the confirmation it asks for. */
    private fun realReadback(): String {
        val fake = FakeWhatsApp(chats = linkedMapOf("Mummy" to mutableListOf(Msg("them", "Call me"))))
        var readback = ""
        runBlocking {
            val ctx = Names.contextFor("Mummy", "x")
            AgentLoop(GatedDevice(SafetyGate(), fake), en, drafts = MemoryDraftStore()).run(
                ReplyFlow("Mummy", reply, ctx, en),
                ctx,
                {},
            ) { req -> readback = req.readback; fake.pkg = "app.saathi"; Answer.NO }
        }
        assertTrue(readback.contains(reply) && readback.contains("Mummy"), readback)
        assertTrue(fake.sent.isEmpty())
        return readback
    }
}
