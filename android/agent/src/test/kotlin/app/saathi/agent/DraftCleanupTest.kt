package app.saathi.agent

import app.saathi.safety.Answer
import app.saathi.safety.SafetyGate
import app.saathi.safety.TypeText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A typed-but-unsent reply must never be left behind silently: NO, unclear, timeout, Stop, the
 * service going away, process death. Only Saathi's exact text is removed; if it can't be removed
 * now, a record (no message text) remembers it and it's removed later, and the user is told.
 * FakeWhatsApp reports the "Message" placeholder as box text, like the real app.
 */
class DraftCleanupTest {
    private val gate = SafetyGate()
    private val t = Texts(Lang.EN)
    private val text = "saathi test please ignore"

    private fun world() = FakeWhatsApp(
        chats = linkedMapOf(
            "Mummy" to mutableListOf(Msg("them", "Call me")),
            "Rahul Sharma" to mutableListOf(Msg("them", "Match at 6?")),
        ),
    ).also { assertTrue(it.placeholderAsText) }

    private fun reply(contact: String = "Mummy") = ReplyFlow(contact, text, Names.contextFor(contact, "x"), t)

    private fun loop(fake: FakeWhatsApp, store: DraftStore, logs: MutableList<String> = mutableListOf(), timeoutMs: Long = AgentLoop.CONFIRM_TIMEOUT_MS) =
        AgentLoop(GatedDevice(gate, fake), t, drafts = store, log = { logs += it }, confirmTimeoutMs = timeoutMs)

    /** The chat's box as WhatsApp shows it: placeholder + voice button when empty. */
    private fun boxIsEmpty(fake: FakeWhatsApp, chat: String): Boolean {
        val saved = fake.pkg to fake.view
        fake.pkg = WhatsApp.PKG
        fake.view = FakeWhatsApp.View.Chat(chat)
        val s = fake.render()
        fake.pkg = saved.first
        fake.view = saved.second
        return WhatsApp.entry(s)?.text == "Message" && WhatsApp.sendButton(s) == null && WhatsApp.typedText(s).isEmpty()
    }

    private fun assertNoTextInLogs(logs: List<String>) {
        logs.forEach { line ->
            assertFalse(line.contains(text) || line.contains("saathi test") || line.contains("Mummy"), "log leaks text: $line")
        }
    }

    // -- in-run endings ---------------------------------------------------------------------------

    @Test
    fun `NO removes exactly the typed text, nothing sent, record gone`() = runBlocking {
        val fake = world()
        val store = MemoryDraftStore()
        val logs = mutableListOf<String>()
        var recordAtConfirm: List<PendingDraft> = emptyList()
        val out = loop(fake, store, logs).run(reply(), Names.contextFor("Mummy", "x"), {}) {
            recordAtConfirm = store.all()
            Answer.NO
        }
        out as Outcome.Cancelled
        assertTrue(out.draftCleared, out.message)
        assertTrue(fake.sent.isEmpty())
        assertEquals(listOf("Mummy"), fake.cleared)
        assertTrue(boxIsEmpty(fake, "Mummy"))
        // Write-ahead: the record existed while the confirm screen was up; it holds no text.
        assertEquals(1, recordAtConfirm.size)
        assertEquals("Mummy", recordAtConfirm[0].chat)
        assertEquals(text.length, recordAtConfirm[0].length)
        assertFalse(recordAtConfirm[0].toString().contains(text))
        assertTrue(store.all().isEmpty())
        assertTrue(logs.any { it.startsWith("cleanup: after boxLen=7 send=false") }, "$logs")
        assertNoTextInLogs(logs)
    }

    @Test
    fun `unclear twice cancels and removes the typed text`() = runBlocking {
        val fake = world()
        val store = MemoryDraftStore()
        val dialog = ConfirmDialog()
        val out = loop(fake, store).run(reply(), Names.contextFor("Mummy", "x"), {}) {
            assertEquals(ConfirmDialog.Next.ASK_AGAIN, dialog.hear("hmm what"))
            assertEquals(ConfirmDialog.Next.CANCEL, dialog.silence())
            Answer.UNCLEAR // what the UI's cancel() amounts to: anything but a clear yes
        }
        assertTrue(out is Outcome.Cancelled && out.draftCleared, "$out")
        assertTrue(fake.sent.isEmpty())
        assertTrue(boxIsEmpty(fake, "Mummy"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `no answer in time cancels and removes the typed text`() = runBlocking {
        val fake = world()
        val store = MemoryDraftStore()
        val out = loop(fake, store, timeoutMs = 50).run(reply(), Names.contextFor("Mummy", "x"), {}) { awaitCancellation() }
        assertTrue(out is Outcome.Cancelled && out.draftCleared, "$out")
        assertTrue(fake.sent.isEmpty())
        assertTrue(boxIsEmpty(fake, "Mummy"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `stop during confirm removes the typed text even though the job is cancelled`() = runBlocking {
        val fake = world()
        val store = MemoryDraftStore()
        val asked = CompletableDeferred<Unit>()
        val l = loop(fake, store)
        val job = async {
            l.run(reply(), Names.contextFor("Mummy", "x"), {}) {
                fake.pkg = "app.saathi" // the Confirm screen covers WhatsApp
                asked.complete(Unit)
                awaitCancellation()
            }
        }
        asked.await()
        job.cancel()
        assertThrows<CancellationException> { runBlocking { job.await() } }
        assertTrue(fake.sent.isEmpty())
        assertEquals(listOf("Mummy"), fake.cleared)
        assertTrue(boxIsEmpty(fake, "Mummy"))
        assertEquals(Cleanup.CLEARED, l.lastCleanup)
        assertEquals(t.draftRemoved, l.lastNote)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `an unexpected error after typing still removes the text`() = runBlocking {
        val fake = world()
        val store = MemoryDraftStore()
        val l = loop(fake, store)
        assertThrows<IllegalStateException> {
            runBlocking { l.run(reply(), Names.contextFor("Mummy", "x"), {}) { error("confirm screen crashed") } }
        }
        assertEquals(Cleanup.CLEARED, l.lastCleanup)
        assertTrue(boxIsEmpty(fake, "Mummy"))
        assertTrue(fake.sent.isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `yes sends and forgets the record`() = runBlocking {
        val fake = world()
        val store = MemoryDraftStore()
        val out = loop(fake, store).run(reply(), Names.contextFor("Mummy", "x"), {}) { Answer.YES }
        assertTrue(out is Outcome.Answered, "$out")
        assertEquals(listOf("Mummy" to text), fake.sent)
        assertTrue(store.all().isEmpty())
        assertTrue(fake.cleared.isEmpty())
    }

    // -- the device goes away (service unbound / reconnecting / process death) --------------------

    @Test
    fun `service gone during confirm, then NO - remembered, user told, removed on reconnect`() = runBlocking {
        val fake = world()
        val store = MemoryDraftStore()
        val logs = mutableListOf<String>()
        val out = loop(fake, store, logs).run(reply(), Names.contextFor("Mummy", "x"), {}) {
            fake.unavailable = true // uiautomator dump / service restart while the Confirm screen is up
            Answer.NO
        }
        out as Outcome.Cancelled
        assertFalse(out.draftCleared)
        assertTrue(out.message.contains(t.draftLeft("Mummy")), out.message)
        assertEquals(text, fake.drafts["Mummy"]) // still there, NOT sent
        assertTrue(fake.sent.isEmpty())
        val kept = store.all().single()
        assertEquals("Mummy", kept.chat)
        assertTrue(logs.any { it == "cleanup: result=UNAVAILABLE" }, "$logs")

        // The service reconnects: the pending cleanup runs (a fresh loop, as after process death).
        fake.unavailable = false
        fake.pkg = "app.saathi"
        val later = loop(fake, store, logs).cleanPending()
        assertNotNull(later)
        assertEquals(listOf(Cleanup.CLEARED), later!!.results)
        assertEquals(t.earlierDraftRemoved("Mummy"), later.note)
        assertTrue(boxIsEmpty(fake, "Mummy"))
        assertTrue(fake.sent.isEmpty())
        assertTrue(store.all().isEmpty())
        assertNoTextInLogs(logs)
    }

    @Test
    fun `stop because the service went away - record kept, next task removes it first`() = runBlocking {
        val fake = world()
        val store = MemoryDraftStore()
        val asked = CompletableDeferred<Unit>()
        val l = loop(fake, store)
        val job = async {
            l.run(reply(), Names.contextFor("Mummy", "x"), {}) {
                asked.complete(Unit)
                awaitCancellation()
            }
        }
        asked.await()
        fake.unavailable = true // shutdown(): device unreadable, then the job is cancelled
        job.cancel()
        assertThrows<CancellationException> { runBlocking { job.await() } }
        assertEquals(Cleanup.UNAVAILABLE, l.lastCleanup)
        assertEquals(t.draftLeft("Mummy"), l.lastNote)
        assertEquals(text, fake.drafts["Mummy"])
        assertEquals(1, store.all().size)

        // Next task (any flow): the earlier text goes first, and the user hears about it.
        fake.unavailable = false
        fake.view = FakeWhatsApp.View.List // WhatsApp was left on the chat list meanwhile
        val said = mutableListOf<String>()
        val out = loop(fake, store).run(
            ReadLastFlow("Rahul Sharma", Names.contextFor("Rahul Sharma", "x"), t),
            Names.contextFor("Rahul Sharma", "x"),
            { said += it },
        ) { error("no confirm") }
        assertEquals(t.clearingEarlierDraft("Mummy"), said.first())
        assertTrue(out is Outcome.Answered, "$out")
        assertTrue(out.message.endsWith(t.earlierDraftRemoved("Mummy")), out.message)
        assertTrue(boxIsEmpty(fake, "Mummy"))
        assertTrue(fake.sent.isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `pending cleanup finds the chat from another chat and leaves the user's own edit alone`() = runBlocking {
        val fake = world().apply {
            pkg = WhatsApp.PKG
            view = FakeWhatsApp.View.Chat("Rahul Sharma")
            drafts["Mummy"] = "$text and more words by the user"
        }
        val store = MemoryDraftStore(PendingDraft.of("Mummy", text))
        val r = loop(fake, store).cleanPending()!!
        assertEquals(listOf(Cleanup.NOT_OURS), r.results)
        assertEquals(t.draftChanged("Mummy"), r.note)
        assertEquals("$text and more words by the user", fake.drafts["Mummy"]) // never touched
        assertTrue(fake.cleared.isEmpty())
        assertTrue(store.all().isEmpty())
        assertTrue(fake.performed.none { it is TypeText && it.text.contains(text) })
    }

    @Test
    fun `pending cleanup with the box already empty just forgets the record`() = runBlocking {
        val fake = world()
        val store = MemoryDraftStore(PendingDraft.of("Mummy", text))
        val r = loop(fake, store).cleanPending()!!
        assertEquals(listOf(Cleanup.GONE), r.results)
        assertNull(r.note)
        assertTrue(fake.cleared.isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `pending cleanup that keeps failing gives up after a few tries and says so`() = runBlocking {
        val fake = world().apply { drafts["Mummy"] = text; chats.remove("Mummy") } // chat can't be found
        val store = MemoryDraftStore(PendingDraft.of("Mummy", text))
        repeat(PendingDraft.MAX_ATTEMPTS - 1) {
            val r = loop(fake, store).cleanPending()!!
            assertEquals(listOf(Cleanup.FAILED), r.results)
            assertEquals(t.draftLeft("Mummy"), r.note)
        }
        val last = loop(fake, store).cleanPending()!!
        assertEquals(t.draftLeftGiveUp("Mummy"), last.note)
        assertTrue(store.all().isEmpty())
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun `an older unresolved record is not overwritten by a new reply's record`() = runBlocking {
        val fake = world().apply { drafts["Mummy"] = text; unavailable = false }
        val store = MemoryDraftStore(PendingDraft.of("Mummy", text))
        fake.unavailable = true
        assertEquals(listOf(Cleanup.UNAVAILABLE), loop(fake, store).cleanPending()!!.results)
        fake.unavailable = false
        // Mummy's chat can't be reached this time, then a reply to Rahul types and is cancelled.
        val mummy = fake.chats.remove("Mummy")!!
        val out = loop(fake, store).run(reply("Rahul Sharma"), Names.contextFor("Rahul Sharma", "x"), {}) { Answer.NO }
        assertTrue(out is Outcome.Cancelled && out.draftCleared, "$out")
        assertEquals(listOf("Mummy"), store.all().map { it.chat })
        fake.chats["Mummy"] = mummy
        assertEquals(listOf(Cleanup.CLEARED), loop(fake, store).cleanPending()!!.results)
        assertTrue(store.all().isEmpty())
        assertTrue(fake.sent.isEmpty())
    }
}
