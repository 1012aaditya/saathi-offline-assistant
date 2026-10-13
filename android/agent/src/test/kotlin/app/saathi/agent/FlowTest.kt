package app.saathi.agent

import app.saathi.safety.Action
import app.saathi.safety.Answer
import app.saathi.safety.Back
import app.saathi.safety.CommandContext
import app.saathi.safety.Kind
import app.saathi.safety.OpenApp
import app.saathi.safety.SafetyGate
import app.saathi.safety.Screen
import app.saathi.safety.Scroll
import app.saathi.safety.Tap
import app.saathi.safety.TypeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FlowTest {
    private val gate = SafetyGate()
    private val t = Texts(Lang.EN)

    private fun world() = FakeWhatsApp(
        chats = linkedMapOf(
            "Mom" to mutableListOf(Msg("them", "Did you eat?"), Msg("me", "Yes"), Msg("them", "Come home early")),
            "Rahul Sharma" to mutableListOf(Msg("them", "Match at 6?")),
            "Office Group" to mutableListOf(Msg("me", "Done")),
        ),
        unread = mutableMapOf("Mom" to 2, "Rahul Sharma" to 1),
    )

    private class Run(val outcome: Outcome, val said: List<String>, val confirms: List<ConfirmRequest>)

    private fun run(
        fake: FakeWhatsApp,
        command: Command,
        captured: Screen? = null,
        answer: suspend (ConfirmRequest, FakeWhatsApp) -> Answer = { _, _ -> error("no confirm expected") },
    ): Run = runBlocking {
        val said = mutableListOf<String>()
        val confirms = mutableListOf<ConfirmRequest>()
        val flow = Flows.forCommand(command, t, gate, captured, "Test app")!!
        val loop = AgentLoop(GatedDevice(gate, fake), t)
        val out = loop.run(flow, Flows.contextFor(command), { said += it }) { req ->
            confirms += req
            answer(req, fake)
        }
        Run(out, said, confirms)
    }

    // -- read last -----------------------------------------------------------------------------

    @Test
    fun `read last message from them, verbatim`() {
        val fake = world()
        val r = run(fake, Command.ReadLast("Mom", "Read Mom's last message"))
        val out = r.outcome as Outcome.Answered
        assertEquals("Come home early", out.quote)
        assertTrue("\"Come home early\"" in out.message)
        assertEquals(listOf<Action>(OpenApp("whatsapp"), Tap(3)), fake.performed)
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun `Hindi relation word finds the English contact`() {
        val out = run(world(), Command.ReadLast("मम्मी", "मम्मी का आखिरी मैसेज पढ़ो")).outcome as Outcome.Answered
        assertEquals("Come home early", out.quote)
    }

    @Test
    fun `contact not on the list is searched, then a single match is opened`() {
        val fake = world()
        fake.chats.keys.toList().forEach { if (it != "Mom") fake.unread.remove(it) }
        val out = run(fake, Command.ReadLast("Rahul", "Read Rahul's last message")).outcome as Outcome.Answered
        assertEquals("Match at 6?", out.quote)
    }

    @Test
    fun `unknown contact fails honestly after searching, nothing opened`() {
        val fake = world()
        val out = run(fake, Command.ReadLast("Zoya", "Read Zoya's last message")).outcome
        assertTrue(out is Outcome.Failed, "$out")
        assertTrue("Zoya" in out.message)
        assertTrue(fake.view is FakeWhatsApp.View.Search)
        assertTrue(fake.performed.none { it is Tap && it.index > 0 && fake.render().elements.getOrNull(it.index)?.rid == WhatsApp.ROW_NAME })
    }

    @Test
    fun `ambiguous contact asks which one`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Riya Home" to mutableListOf(), "Riya Office" to mutableListOf()))
        val out = run(fake, Command.ReadLast("Riya", "read Riya's last message")).outcome
        assertTrue(out is Outcome.Asked, "$out")
        assertTrue("\"Riya Home\"" in out.message && "\"Riya Office\"" in out.message)
    }

    @Test
    fun `no message from them is said honestly`() {
        val out = run(world(), Command.ReadLast("Office Group", "x")).outcome as Outcome.Answered
        assertNull(out.quote)
        assertTrue("couldn't find" in out.message)
    }

    @Test
    fun `missing contact is asked, not guessed`() {
        assertNull(Flows.forCommand(Command.ReadLast(null, "read the last message"), t, gate))
        assertEquals(t.whoseMessage, Flows.missing(Command.ReadLast(null, "x"), t))
        assertEquals(t.whatToSay("Mom"), Flows.missing(Command.Reply("Mom", null, "x"), t))
    }

    // -- PIN / secure ----------------------------------------------------------------------------

    @Test
    fun `PIN screen hands over without acting`() {
        val fake = world().apply { pkg = WhatsApp.PKG; pinScreen = true }
        val out = run(fake, Command.ReadLast("Mom", "x")).outcome
        assertTrue(out is Outcome.HandedOver, "$out")
        assertTrue(fake.performed.isEmpty())
    }

    // -- reply -----------------------------------------------------------------------------------

    @Test
    fun `reply types, then the gate HOLDs send until a yes`() {
        val fake = world()
        val r = run(fake, Command.Reply("Mom", "I'll be home by 7", "reply to Mom I'll be home by 7")) { req, f ->
            // At the moment of asking, nothing has been sent and send was never tapped.
            assertTrue(f.sent.isEmpty())
            assertTrue(f.performed.none { it is Tap && it.index == f.render().elements.indexOfFirst { e -> e.rid == "send" } })
            assertEquals("Mom", req.recipient)
            assertEquals("I'll be home by 7", req.message)
            assertEquals("R2", req.rule)
            assertTrue("Mom" in req.readback && "\"I'll be home by 7\"" in req.readback)
            f.pkg = "app.saathi" // the Confirm screen covers WhatsApp
            Answer.YES
        }
        assertEquals(1, r.confirms.size)
        assertEquals(listOf("Mom" to "I'll be home by 7"), fake.sent)
        assertTrue(r.outcome is Outcome.Answered, "${r.outcome}")
        assertTrue(fake.performed.any { it is TypeText && it.text == "I'll be home by 7" })
    }

    @Test
    fun `WhatsApp placeholder in an empty box is not mistaken for a user draft`() {
        val fake = world() // fake shows "Message" in the empty box, like the real app
        assertTrue(fake.placeholderAsText)
        val r = run(fake, Command.Reply("Mom", "on my way", "x")) { _, _ -> Answer.NO }
        assertTrue(r.outcome is Outcome.Cancelled, "${r.outcome}") // got as far as the read-back
    }

    @Test
    fun `a real user draft is still never overwritten without asking (default keep)`() {
        val fake = world()
        fake.drafts["Mom"] = "my own unsent words"
        val r = run(fake, Command.Reply("Mom", "on my way", "x")) { _, _ -> Answer.YES }
        assertTrue(r.outcome is Outcome.Cancelled, "${r.outcome}")
        assertEquals(t.draftKept, r.outcome.message)
        assertTrue(r.confirms.isEmpty())
        assertTrue(fake.sent.isEmpty())
        assertEquals("my own unsent words", fake.drafts["Mom"])
    }

    @Test
    fun `cancel clears the typed draft and sends nothing`() {
        val fake = world()
        val r = run(fake, Command.Reply("Mom", "on my way", "x")) { _, _ -> Answer.NO }
        val out = r.outcome as Outcome.Cancelled
        assertTrue(out.draftCleared)
        assertTrue(fake.sent.isEmpty())
        assertEquals(listOf("Mom"), fake.cleared)
        assertNull(fake.drafts["Mom"])
    }

    @Test
    fun `unclear answer cancels too (defaults to cancel)`() {
        val fake = world()
        val out = run(fake, Command.Reply("Mom", "on my way", "x")) { _, _ -> Answer.UNCLEAR }.outcome
        assertTrue(out is Outcome.Cancelled)
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun `text edited after the read-back is not sent`() {
        val fake = world()
        var asked = false
        fake.onSnapshot = { if (asked) it.drafts["Mom"] = "something else" }
        val out = run(fake, Command.Reply("Mom", "on my way", "x")) { _, _ -> asked = true; Answer.YES }.outcome
        assertTrue(out is Outcome.Failed, "$out")
        assertTrue(fake.sent.isEmpty())
        assertEquals("something else", fake.drafts["Mom"]) // not ours: never cleared
    }

    @Test
    fun `an existing user draft is never overwritten without an answer`() {
        val fake = world().apply { drafts["Mom"] = "my own draft" }
        val out = run(fake, Command.Reply("Mom", "hi", "x")).outcome
        assertTrue(out is Outcome.Cancelled, "$out")
        assertEquals("my own draft", fake.drafts["Mom"])
        assertTrue(fake.performed.none { it is TypeText })
    }

    @Test
    fun `send through the choke point is held, never run, without confirmation`() = runBlocking {
        val fake = world().apply { pkg = WhatsApp.PKG; view = FakeWhatsApp.View.Chat("Mom"); drafts["Mom"] = "hello" }
        val device = GatedDevice(gate, fake)
        val s = fake.snapshot()!!
        val send = s.elements.first { it.rid == "send" }
        val ctx = Names.contextFor("Mom", "x")
        val r = device.execute(Tap(send.index), s, ctx)
        assertTrue(r is Exec.Held)
        assertTrue(fake.performed.isEmpty() && fake.sent.isEmpty())
        val no = device.executeConfirmed(r as Exec.Held, Answer.NO, ctx)
        assertTrue(no is Exec.Blocked)
        assertTrue(fake.sent.isEmpty())
        val unclear = device.executeConfirmed(r, Answer.UNCLEAR, ctx)
        assertTrue(unclear is Exec.Blocked)
        assertTrue(fake.sent.isEmpty())
        val yes = device.executeConfirmed(r, Answer.YES, ctx)
        assertEquals(Exec.Ran(true), yes)
        assertEquals(listOf("Mom" to "hello"), fake.sent)
    }

    @Test
    fun `held send to a different chat is blocked by R5`() = runBlocking {
        val fake = world().apply { pkg = WhatsApp.PKG; view = FakeWhatsApp.View.Chat("Rahul Sharma"); drafts["Rahul Sharma"] = "hello" }
        val device = GatedDevice(gate, fake)
        val s = fake.snapshot()!!
        val r = device.execute(Tap(s.elements.first { it.rid == "send" }.index), s, Names.contextFor("Mom", "x"))
        assertTrue(r is Exec.Blocked && r.verdict.rule == "R5")
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun `stop cancels at once and removes the draft`() = runBlocking {
        val fake = world()
        val asked = CompletableDeferred<Unit>()
        val flow = ReplyFlow("Mom", "on my way", Names.contextFor("Mom", "x"), t)
        val job = async {
            AgentLoop(GatedDevice(gate, fake), t).run(flow, Names.contextFor("Mom", "x"), {}) {
                asked.complete(Unit)
                awaitCancellation()
            }
        }
        asked.await()
        job.cancel()
        assertThrows<CancellationException> { runBlocking { job.await() } }
        assertTrue(fake.sent.isEmpty())
        assertEquals(listOf("Mom"), fake.cleared)
    }

    // -- unread ----------------------------------------------------------------------------------

    @Test
    fun `unread summary reads names and counts, then restores the filter`() {
        val fake = world()
        val out = run(fake, Command.UnreadSummary("who messaged me")).outcome as Outcome.Answered
        assertEquals("2 chats have new messages: Mom, 2, Rahul Sharma, 1.", out.message)
        assertEquals(listOf("Mom", "Rahul Sharma"), out.quotes)
        assertFalse(fake.unreadFilterOn)
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun `unread summary in Hindi with nothing new`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Mom" to mutableListOf()))
        val loop = AgentLoop(GatedDevice(gate, fake), Texts(Lang.HI))
        val out = runBlocking { loop.run(UnreadFlow(Texts(Lang.HI)), CommandContext(""), {}) { Answer.NO } }
        assertEquals("अभी कोई नया संदेश नहीं है।", out.message)
    }

    @Test
    fun `back out of a chat to reach the list`() {
        val fake = world().apply { pkg = WhatsApp.PKG; view = FakeWhatsApp.View.Chat("Office Group") }
        val out = run(fake, Command.UnreadSummary("x")).outcome
        assertTrue(out is Outcome.Answered)
        assertEquals(Back, fake.performed.first())
    }

    // -- screen summary --------------------------------------------------------------------------

    @Test
    fun `screen summary lists captured texts verbatim and never acts`() {
        val fake = world()
        val captured = S.build(
            "com.example.notes",
            { S.el(it, text = "Shopping list") },
            { S.el(it, text = "Milk, bread", desc = "") },
            { S.el(it, desc = "Add note", clickable = true) },
            { S.el(it, text = "hunter2", password = true, editable = true) },
        )
        val out = run(fake, Command.ScreenSummary("what's on screen"), captured).outcome as Outcome.Answered
        assertEquals("This is Test app. On screen: Shopping list; Milk, bread; Add note.", out.message)
        assertFalse("hunter2" in out.message)
        assertTrue(fake.performed.isEmpty())
    }

    @Test
    fun `screen summary without a captured screen explains, and a PIN screen hands over`() {
        val none = run(world(), Command.ScreenSummary("x")).outcome
        assertEquals(t.noCapturedScreen, none.message)
        val pin = S.build("com.bank", { S.el(it, text = "Enter your PIN") })
        assertTrue(run(world(), Command.ScreenSummary("x"), pin).outcome is Outcome.HandedOver)
    }

    @Test
    fun `a read-only flow that tries to act is refused`() = runBlocking {
        val fake = world()
        val sneaky = object : Flow {
            override val readOnly = true
            override fun next(screen: Screen): Plan = Plan.Act(Back, "x")
        }
        val out = AgentLoop(GatedDevice(gate, fake), t).run(sneaky, CommandContext(""), {}) { Answer.YES }
        assertTrue(out is Outcome.Failed)
        assertTrue(fake.performed.isEmpty())
    }

    // -- open app --------------------------------------------------------------------------------

    @Test
    fun `open app`() {
        val fake = world()
        val out = run(fake, Command.OpenApp("whatsapp", WhatsApp.PKG, "open whatsapp")).outcome
        assertTrue(out is Outcome.Answered)
        assertEquals(listOf<Action>(OpenApp("whatsapp")), fake.performed)
    }

    // -- limits ----------------------------------------------------------------------------------

    @Test
    fun `stops after 3 failures when nothing changes`() = runBlocking {
        val fake = world().apply { pkg = WhatsApp.PKG; view = FakeWhatsApp.View.Chat("Mom") }
        val stuck = object : Flow { override fun next(screen: Screen): Plan = Plan.Act(Scroll(app.saathi.safety.Direction.UP), "x") }
        val loop = AgentLoop(GatedDevice(gate, fake), t)
        val out = loop.run(stuck, CommandContext(""), {}) { Answer.NO }
        assertEquals(t.stuck, out.message)
        assertEquals(3, loop.steps)
    }

    @Test
    fun `stops at 25 steps`() = runBlocking {
        val fake = world().apply { pkg = WhatsApp.PKG }
        var flip = false
        val busy = object : Flow {
            override fun next(screen: Screen): Plan {
                flip = !flip
                // alternate list <-> chat: always a change, never the same action 3x in 6
                return if (WhatsApp.onChatList(screen)) Plan.Act(Tap(screen.elements.first { it.rid == WhatsApp.ROW_NAME }.index), "x")
                else Plan.Act(Back, "x")
            }
        }
        val loop = AgentLoop(GatedDevice(gate, fake), t, maxFailures = 99)
        val out = loop.run(busy, CommandContext(""), {}) { Answer.NO }
        assertEquals(t.tooManySteps, out.message)
        assertEquals(25, loop.steps)
    }

    @Test
    fun `a blocked action counts as a failure and is never run`() = runBlocking {
        val fake = FakeWhatsApp(chats = linkedMapOf("Block List" to mutableListOf())).apply { pkg = WhatsApp.PKG }
        val risky = object : Flow {
            override fun next(screen: Screen): Plan = Plan.Act(Tap(screen.elements.first { it.rid == WhatsApp.ROW_NAME }.index), "x")
        }
        val said = mutableListOf<String>()
        val out = AgentLoop(GatedDevice(gate, fake), t).run(risky, CommandContext(""), { said += it }) { Answer.NO }
        assertEquals(t.unsafe, out.message)
        assertTrue(fake.performed.isEmpty())
        assertTrue(said.all { it == t.verdict(app.saathi.safety.Verdict(Kind.BLOCK, "R10")) })
    }

    @Test
    fun `an answer quoting text that isn't on screen is refused`() = runBlocking {
        val fake = world().apply { pkg = WhatsApp.PKG }
        val liar = object : Flow { override fun next(screen: Screen): Plan = Plan.Done("Mom said \"see you\"", listOf("see you")) }
        val out = AgentLoop(GatedDevice(gate, fake), t).run(liar, CommandContext(""), {}) { Answer.NO }
        assertEquals(t.notExact, out.message)
    }
}

class ConfirmDialogTest {
    @Test
    fun `yes sends, no cancels`() {
        assertEquals(ConfirmDialog.Next.SEND, ConfirmDialog().hear("yes"))
        assertEquals(ConfirmDialog.Next.SEND, ConfirmDialog().hear("haan bhej do"))
        assertEquals(ConfirmDialog.Next.CANCEL, ConfirmDialog().hear("no"))
        assertEquals(ConfirmDialog.Next.CANCEL, ConfirmDialog().hear("nahi"))
        assertEquals(ConfirmDialog.Next.CANCEL, ConfirmDialog().hear("yes no")) // any no wins
        assertEquals(ConfirmDialog.Next.CANCEL, ConfirmDialog().hear("don't send"))
    }

    @Test
    fun `unclear asks once more, then cancels`() {
        val d = ConfirmDialog()
        assertEquals(ConfirmDialog.Next.ASK_AGAIN, d.hear("hmm"))
        assertEquals(ConfirmDialog.Next.CANCEL, d.hear("what"))
        val e = ConfirmDialog()
        assertEquals(ConfirmDialog.Next.ASK_AGAIN, e.silence())
        assertEquals(ConfirmDialog.Next.CANCEL, e.silence())
    }

}
