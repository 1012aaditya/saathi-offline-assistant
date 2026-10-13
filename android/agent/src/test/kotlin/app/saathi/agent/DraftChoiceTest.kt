package app.saathi.agent

import app.saathi.safety.Answer
import app.saathi.safety.SafetyGate
import app.saathi.safety.TypeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The user's OWN unsent text is already in the chat's box when a reply arrives. Saathi asks
 * (keep / replace / add / send it), KEEP is the default, nothing is sent without a yes on a
 * verbatim read-back, and on anything but yes the user's original text is put back exactly.
 * FakeWhatsApp reports the "Message" placeholder as box text, like the real app.
 */
class DraftChoiceTest {
    private val gate = SafetyGate()
    private val t = Texts(Lang.EN)
    private val original = "saathi test please ignore" // the leftover draft (25 chars), like on the tablet
    private val reply = "saathi test two"
    private val logs = mutableListOf<String>()
    private val secrets = mutableListOf<String>()

    private fun world(draft: String? = original) = FakeWhatsApp(
        chats = linkedMapOf(
            "Mummy" to mutableListOf(Msg("them", "Call me")),
            "Rahul Sharma" to mutableListOf(Msg("them", "Match at 6?")),
        ),
    ).also {
        assertTrue(it.placeholderAsText)
        if (draft != null) it.drafts["Mummy"] = draft
    }

    private fun ctx() = Names.contextFor("Mummy", "x")

    private fun flow() = ReplyFlow("Mummy", reply, ctx(), t)

    private fun loop(fake: FakeWhatsApp, store: DraftStore, timeoutMs: Long = AgentLoop.CONFIRM_TIMEOUT_MS) =
        AgentLoop(GatedDevice(gate, fake), t, drafts = store, log = { logs += it }, confirmTimeoutMs = timeoutMs)

    private class Run(val out: Outcome, val choices: List<DraftChoiceRequest>, val confirms: List<ConfirmRequest>, val loop: AgentLoop)

    private fun run(
        fake: FakeWhatsApp,
        store: DraftStore = MemoryDraftStore(),
        timeoutMs: Long = AgentLoop.CONFIRM_TIMEOUT_MS,
        choose: suspend (DraftChoiceRequest) -> DraftChoice,
        confirm: suspend (ConfirmRequest) -> Answer = { error("no confirm expected") },
    ): Run = runBlocking {
        val choices = mutableListOf<DraftChoiceRequest>()
        val confirms = mutableListOf<ConfirmRequest>()
        val l = loop(fake, store, timeoutMs)
        val out = l.run(
            flow(),
            ctx(),
            {},
            choose = { req ->
                choices += req
                fake.pkg = "app.saathi" // the choice sheet covers WhatsApp
                choose(req)
            },
        ) { req ->
            confirms += req
            fake.pkg = "app.saathi" // the Confirm screen covers WhatsApp
            confirm(req)
        }
        Run(out, choices, confirms, l)
    }

    private fun typedIntoBox(fake: FakeWhatsApp) = fake.performed.filterIsInstance<TypeText>().filter { it.text != "Mummy" }

    @AfterEach
    fun `logs never contain message text or names`() {
        val forbidden = listOf(original, reply, "saathi test", "please ignore", "Mummy") + secrets
        logs.forEach { line -> forbidden.forEach { f -> assertFalse(line.contains(f), "log leaks text: $line") } }
    }

    // -- KEEP (the default) -------------------------------------------------------------------

    @Test
    fun `keep touches nothing and says so`() {
        val fake = world()
        val store = MemoryDraftStore()
        val r = run(fake, store, choose = { DraftChoice.KEEP })
        val out = r.out as Outcome.Cancelled
        assertEquals(t.draftKept, out.message)
        val req = r.choices.single()
        assertEquals("Mummy", req.chat)
        assertEquals(original, req.existing) // verbatim, from the box on screen
        assertEquals(reply, req.newMessage)
        assertTrue(req.canChange)
        assertEquals(original, fake.drafts["Mummy"])
        assertTrue(typedIntoBox(fake).isEmpty())
        assertTrue(fake.sent.isEmpty() && fake.cleared.isEmpty())
        assertTrue(store.all().isEmpty())
        assertTrue(r.confirms.isEmpty())
    }

    @Test
    fun `unclear twice keeps it`() {
        val fake = world()
        val r = run(fake, choose = { req ->
            val d = DraftChoiceDialog()
            assertEquals(DraftChoiceDialog.Next.ASK_AGAIN, d.hear("hmm what", req.canChange))
            assertEquals(DraftChoiceDialog.Next.KEEP, d.hear("umm", req.canChange))
            DraftChoice.KEEP // what the sheet sends for Next.KEEP
        })
        assertEquals(t.draftKept, r.out.message)
        assertEquals(original, fake.drafts["Mummy"])
        assertTrue(typedIntoBox(fake).isEmpty() && fake.sent.isEmpty())
    }

    @Test
    fun `silence twice keeps it`() {
        val fake = world()
        val r = run(fake, choose = {
            val d = DraftChoiceDialog()
            assertEquals(DraftChoiceDialog.Next.ASK_AGAIN, d.silence())
            assertEquals(DraftChoiceDialog.Next.KEEP, d.silence())
            DraftChoice.KEEP
        })
        assertEquals(t.draftKept, r.out.message)
        assertEquals(original, fake.drafts["Mummy"])
        assertTrue(typedIntoBox(fake).isEmpty() && fake.sent.isEmpty())
    }

    @Test
    fun `no answer in time keeps it`() {
        val fake = world()
        val r = run(fake, timeoutMs = 50, choose = { awaitCancellation() })
        assertEquals(t.draftKept, r.out.message)
        assertEquals(original, fake.drafts["Mummy"])
        assertTrue(typedIntoBox(fake).isEmpty() && fake.sent.isEmpty())
    }

    // -- REPLACE ------------------------------------------------------------------------------

    @Test
    fun `replace then yes sends only the new message`() {
        val fake = world()
        val store = MemoryDraftStore()
        var atConfirm: List<PendingDraft> = emptyList()
        val r = run(fake, store, choose = { DraftChoice.REPLACE }) { req ->
            atConfirm = store.all()
            assertEquals(reply, req.message) // the box's full text, verbatim
            assertEquals("Mummy", req.recipient)
            assertEquals(AfterCancel.RESTORE_ORIGINAL, req.afterCancel)
            assertTrue(fake.sent.isEmpty())
            Answer.YES
        }
        assertTrue(r.out is Outcome.Answered, "${r.out}")
        assertEquals(listOf("Mummy" to reply), fake.sent)
        // Write-ahead: what Saathi changed and the original's hash + length, never the text.
        val rec = atConfirm.single()
        assertEquals(DraftMode.REPLACE, rec.mode)
        assertEquals(original.length, rec.originalLength)
        assertEquals(PendingDraft.hashOf(original), rec.originalHash)
        assertFalse(rec.toString().contains(original) || rec.toString().contains(reply))
        assertTrue(store.all().isEmpty())
        assertNull(store.original(rec))
    }

    @Test
    fun `replace then no puts the original back`() {
        val fake = world()
        val store = MemoryDraftStore()
        val r = run(fake, store, choose = { DraftChoice.REPLACE }) { Answer.NO }
        val out = r.out as Outcome.Cancelled
        assertTrue(out.restored, out.message)
        assertEquals(t.cancelledRestored, out.message)
        assertEquals(original, fake.drafts["Mummy"])
        assertTrue(fake.sent.isEmpty())
        assertTrue(store.all().isEmpty())
        assertTrue(logs.any { it.startsWith("cleanup: after typedLen=25 ") && it.endsWith("original=true") }, "$logs")
    }

    @Test
    fun `replace then unclear (any non-yes) puts the original back`() {
        val fake = world()
        val r = run(fake, choose = { DraftChoice.REPLACE }) { Answer.UNCLEAR }
        assertTrue((r.out as Outcome.Cancelled).restored)
        assertEquals(original, fake.drafts["Mummy"])
        assertTrue(fake.sent.isEmpty())
    }

    // -- ADD ----------------------------------------------------------------------------------

    @Test
    fun `add then yes sends the combined text`() {
        val fake = world()
        val combined = "$original $reply"
        val r = run(fake, choose = { DraftChoice.ADD }) { req ->
            assertEquals(combined, req.message) // read-back of the FULL text
            assertEquals(AfterCancel.RESTORE_ORIGINAL, req.afterCancel)
            Answer.YES
        }
        assertTrue(r.out is Outcome.Answered, "${r.out}")
        assertEquals(listOf("Mummy" to combined), fake.sent)
    }

    @Test
    fun `add then no restores exactly the original`() {
        val fake = world()
        val store = MemoryDraftStore()
        val r = run(fake, store, choose = { DraftChoice.ADD }) { Answer.NO }
        assertTrue((r.out as Outcome.Cancelled).restored, r.out.message)
        assertEquals(original, fake.drafts["Mummy"])
        assertTrue(fake.sent.isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `add keeps the original's own spacing when putting it back`() {
        val spaced = "two  spaces here " // raw box text; the screen shows it collapsed
        val fake = world(spaced)
        val r = run(fake, choose = { DraftChoice.ADD }) { Answer.NO }
        assertTrue((r.out as Outcome.Cancelled).restored)
        assertEquals(spaced, fake.drafts["Mummy"])
    }

    // -- SEND IT ------------------------------------------------------------------------------

    @Test
    fun `send it then yes sends the original, typing nothing`() {
        val fake = world()
        val r = run(fake, choose = { DraftChoice.SEND }) { req ->
            assertEquals(original, req.message)
            assertEquals(AfterCancel.LEAVE_AS_IS, req.afterCancel)
            Answer.YES
        }
        assertTrue(r.out is Outcome.Answered, "${r.out}")
        assertEquals(listOf("Mummy" to original), fake.sent)
        assertTrue(typedIntoBox(fake).isEmpty())
    }

    @Test
    fun `send it then no leaves it as it was`() {
        val fake = world()
        val store = MemoryDraftStore()
        val r = run(fake, store, choose = { DraftChoice.SEND }) { Answer.NO }
        val out = r.out as Outcome.Cancelled
        assertEquals(t.cancelledLeftAsIs, out.message)
        assertEquals(original, fake.drafts["Mummy"])
        assertTrue(typedIntoBox(fake).isEmpty() && fake.sent.isEmpty() && fake.cleared.isEmpty())
        assertTrue(store.all().isEmpty())
    }

    // -- private-looking text ---------------------------------------------------------------------

    @Test
    fun `a draft that looks like a code, PIN or password is never read or touched`() {
        val private = listOf("482913", "Your OTP is 482913", "my password is tiger", "hunter22", "PIN 1234", "code 5521 for login")
        secrets += private
        for (p in private) {
            assertTrue(PrivateText.looksPrivate(p), p)
            val fake = world(p)
            val r = run(fake, choose = { error("never asked about private text") })
            val out = r.out as Outcome.Cancelled
            assertEquals(t.draftPrivate, out.message)
            assertFalse(out.message.contains(p))
            assertEquals(p, fake.drafts["Mummy"])
            assertTrue(typedIntoBox(fake).isEmpty() && fake.sent.isEmpty())
        }
        listOf(original, "I'll be home by 7", "ok", "Call me at 6 pm please", "kal milte hain").forEach {
            assertFalse(PrivateText.looksPrivate(it), it)
        }
    }

    // -- Stop ---------------------------------------------------------------------------------

    private fun stopAt(choice: DraftChoice, atChoice: Boolean = false): Pair<FakeWhatsApp, AgentLoop> = runBlocking {
        val fake = world()
        val store = MemoryDraftStore()
        val asked = CompletableDeferred<Unit>()
        val l = loop(fake, store)
        val job = async {
            l.run(flow(), ctx(), {}, choose = {
                fake.pkg = "app.saathi"
                if (atChoice) {
                    asked.complete(Unit)
                    awaitCancellation()
                }
                choice
            }) {
                fake.pkg = "app.saathi"
                asked.complete(Unit)
                awaitCancellation()
            }
        }
        asked.await()
        job.cancel()
        assertThrows<CancellationException> { runBlocking { job.await() } }
        assertTrue(fake.sent.isEmpty())
        assertTrue(store.all().isEmpty())
        fake to l
    }

    @Test
    fun `stop while choosing touches nothing`() {
        val (fake, l) = stopAt(DraftChoice.REPLACE, atChoice = true)
        assertEquals(original, fake.drafts["Mummy"])
        assertTrue(typedIntoBox(fake).isEmpty())
        assertNull(l.lastCleanup)
    }

    @Test
    fun `stop during the replace read-back puts the original back`() {
        val (fake, l) = stopAt(DraftChoice.REPLACE)
        assertEquals(original, fake.drafts["Mummy"])
        assertEquals(Cleanup.RESTORED, l.lastCleanup)
        assertEquals(t.originalRestored, l.lastNote)
    }

    @Test
    fun `stop during the add read-back puts the original back`() {
        val (fake, l) = stopAt(DraftChoice.ADD)
        assertEquals(original, fake.drafts["Mummy"])
        assertEquals(Cleanup.RESTORED, l.lastCleanup)
    }

    @Test
    fun `stop during the send-it read-back leaves it as it was`() {
        val (fake, l) = stopAt(DraftChoice.SEND)
        assertEquals(original, fake.drafts["Mummy"])
        assertNull(l.lastCleanup)
        assertTrue(typedIntoBox(fake).isEmpty())
    }

    // -- the service goes away / the process dies ------------------------------------------------

    @Test
    fun `service gone during replace read-back - user hears their text, restored on reconnect`() {
        val fake = world()
        val store = MemoryDraftStore()
        val r = run(fake, store, choose = { DraftChoice.REPLACE }) {
            fake.unavailable = true
            Answer.NO
        }
        val out = r.out as Outcome.Cancelled
        assertFalse(out.restored)
        assertTrue(out.message.contains(t.restoreFailed("Mummy", original)), out.message) // read to them, so it isn't lost
        assertEquals(reply, fake.drafts["Mummy"]) // Saathi's text, NOT sent
        val kept = store.all().single()
        assertEquals(DraftMode.REPLACE, kept.mode)
        assertEquals(original, store.original(kept)) // memory only

        fake.unavailable = false
        fake.pkg = "app.saathi"
        val later = runBlocking { loop(fake, store).cleanPending() }!!
        assertEquals(listOf(Cleanup.RESTORED), later.results)
        assertEquals(t.earlierRestored("Mummy"), later.note)
        assertEquals(original, fake.drafts["Mummy"])
        assertTrue(store.all().isEmpty())
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun `after process death a replace removes Saathi's text and says the original needs retyping`() = runBlocking {
        val fake = world(reply) // Saathi's text is in the box; the original was only in memory
        val store = MemoryDraftStore(PendingDraft.changing("Mummy", reply, DraftMode.REPLACE, original))
        val r = loop(fake, store).cleanPending()!!
        assertEquals(listOf(Cleanup.ORIGINAL_LOST), r.results)
        assertEquals(t.originalLost("Mummy"), r.note)
        assertNull(fake.drafts["Mummy"])
        assertTrue(store.all().isEmpty() && fake.sent.isEmpty())
    }

    @Test
    fun `after process death an add is still undone exactly (original read back from the box)`() = runBlocking {
        val fake = world("$original $reply")
        val store = MemoryDraftStore(PendingDraft.changing("Mummy", "$original $reply", DraftMode.ADD, original))
        val r = loop(fake, store).cleanPending()!!
        assertEquals(listOf(Cleanup.RESTORED), r.results)
        assertEquals(original, fake.drafts["Mummy"])
        assertTrue(store.all().isEmpty() && fake.sent.isEmpty())
    }

    // -- the box changes under us --------------------------------------------------------------

    @Test
    fun `text changed while asking is left alone`() {
        val fake = world()
        val r = run(fake, choose = {
            fake.drafts["Mummy"] = "edited by the user"
            DraftChoice.REPLACE
        })
        assertTrue(r.out is Outcome.Failed, "${r.out}")
        assertEquals(t.draftChangedMeanwhile, r.out.message)
        assertEquals("edited by the user", fake.drafts["Mummy"])
        assertTrue(typedIntoBox(fake).isEmpty())
    }

    @Test
    fun `user edits the box during the read-back - left alone, original read to them`() {
        val fake = world()
        val r = run(fake, choose = { DraftChoice.REPLACE }) {
            fake.drafts["Mummy"] = "something new by the user"
            Answer.NO
        }
        val out = r.out as Outcome.Cancelled
        assertTrue(out.message.contains(t.yourEarlierText(original)), out.message)
        assertEquals("something new by the user", fake.drafts["Mummy"])
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun `a draft with several lines can only be kept or sent`() {
        val fake = world("line one\nline two")
        val r = run(fake, choose = { req ->
            assertFalse(req.canChange)
            assertEquals("line one line two", req.existing)
            DraftChoice.REPLACE // not offered: treated as keep
        })
        assertEquals(t.draftKept, r.out.message)
        assertEquals("line one\nline two", fake.drafts["Mummy"])
        assertTrue(typedIntoBox(fake).isEmpty())
    }

    // -- read last --------------------------------------------------------------------------------

    @Test
    fun `read last mentions unsent text without reading it`() = runBlocking {
        val fake = world()
        val out = loop(fake, MemoryDraftStore()).run(ReadLastFlow("Mummy", ctx(), t), ctx(), {}) { error("no confirm") }
        out as Outcome.Answered
        assertEquals("Call me", out.quote)
        assertTrue(out.message.endsWith(t.unsentTextHere), out.message)
        assertEquals(t.unsentTextHere, out.note)
        assertFalse(out.message.contains(original))
        assertEquals(original, fake.drafts["Mummy"])

        val clean = loop(world(null), MemoryDraftStore()).run(ReadLastFlow("Mummy", ctx(), t), ctx(), {}) { error("no confirm") }
        clean as Outcome.Answered
        assertNull(clean.note)
        assertFalse(clean.message.contains(t.unsentTextHere))
    }

    // -- the spoken answer ---------------------------------------------------------------------

    @Test
    fun `spoken answers in English, Hinglish and Hindi`() {
        val c = DraftChoiceDialog.Companion
        listOf("keep", "keep it", "leave it", "rehne do", "रहने दो", "chhodo", "no", "nahi", "don't send", "mat bhejo", "मत बदलो", "cancel")
            .forEach { assertEquals(DraftChoice.KEEP, c.classify(it), it) }
        listOf("replace", "replace it", "change it", "badal do", "बदल दो", "replace it and send")
            .forEach { assertEquals(DraftChoice.REPLACE, c.classify(it), it) }
        listOf("add", "add it", "add my message", "jod do", "जोड़ दो", "जोड़ दो")
            .forEach { assertEquals(DraftChoice.ADD, c.classify(it), it) }
        listOf("send it", "send", "bhej do", "भेज दो", "bhejo")
            .forEach { assertEquals(DraftChoice.SEND, c.classify(it), it) }
        listOf("", "hmm", "yes", "haan", "keep it and send", "replace or add")
            .forEach { assertNull(c.classify(it), it) }
        // Not offered (several lines): replace / add count as unclear, then keep.
        val d = DraftChoiceDialog()
        assertEquals(DraftChoiceDialog.Next.ASK_AGAIN, d.hear("replace", canChange = false))
        assertEquals(DraftChoiceDialog.Next.KEEP, d.hear("add", canChange = false))
        assertEquals(DraftChoiceDialog.Next.SEND, DraftChoiceDialog().hear("bhej do", canChange = false))
    }
}
