package app.saathi.agent

import app.saathi.safety.Answer
import app.saathi.safety.Back
import app.saathi.safety.OpenApp
import app.saathi.safety.SafetyGate
import app.saathi.safety.Tap
import app.saathi.safety.TypeText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * 13 Oct, 22:50 on the owner's tablet:
 *  - "message Aaditya Goel saathi self test" and "send a message to Aaditya Goel saying saathi
 *    self test" were both sent towards "Aaditya" (the owner's own chat is "Aaditya Goel (You)");
 *  - starting that reply, "open_app, back, open_app" ended in "I couldn't open that app."
 */
class NamesAndOpeningTest {
    private val t = Texts(Lang.EN)
    private val gate = SafetyGate()

    private fun reply(text: String, known: List<String> = emptyList()) = CommandParser.parse(text, null, known) as Command.Reply

    // == where the name ends ==========================================================================

    @Test
    fun `a saved multi-word name is kept whole`() {
        val c = reply("message Aaditya Goel saathi self test", known = listOf("Aaditya Goel", "Aaditya"))
        assertEquals("Aaditya Goel", c.contact)
        assertEquals("saathi self test", c.text)
        assertTrue(c.alternatives.isEmpty(), "a saved name needs no other reading")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "send a message to Aaditya Goel saying saathi self test",
            "message Aaditya Goel saying saathi self test",
            "tell Aaditya Goel that saathi self test",
            "message Aaditya Goel ki saathi self test",
            "message Aaditya Goel कि saathi self test",
            "message Aaditya Goel bolo saathi self test",
            "message Aaditya Goel: saathi self test",
            "Aaditya Goel ko bolo saathi self test",
        ],
    )
    fun `a joining word marks the end of the name even with a shorter saved name`(text: String) {
        for (known in listOf(emptyList(), listOf("Aaditya"))) {
            val c = reply(text, known)
            assertEquals("Aaditya Goel", c.contact, "$text known=$known")
            assertEquals("saathi self test", c.text, "$text known=$known")
            assertTrue(c.alternatives.isEmpty(), "$text known=$known")
        }
    }

    @Test
    fun `no joining word - the longest reading first, shorter ones as fallbacks`() {
        for (known in listOf(emptyList(), listOf("Aaditya"))) {
            val c = reply("message Aaditya Goel saathi self test", known)
            assertEquals("Aaditya Goel", c.contact, "known=$known")
            assertEquals("saathi self test", c.text) // "saathi" is never part of a name
            assertEquals(listOf(NameSplit("Aaditya", "Goel saathi self test")), c.alternatives, "known=$known")
        }
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "text Riya hello there|Riya|hello there",
            "reply to Mom I'll be home by 7|Mom|I'll be home by 7",
            "message Rahul kal milte hain|Rahul|kal milte hain",
            "tell Mom we said that you are late|Mom|we said that you are late",
            "message Rahul hello, how are you|Rahul|hello, how are you",
        ],
    )
    fun `message words never become part of a name`(text: String, contact: String, message: String) {
        val c = reply(text)
        assertEquals(contact, c.contact, text)
        assertEquals(message, c.text, text)
        assertTrue(c.alternatives.isEmpty(), "$text -> ${c.alternatives}")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "message myself saying hi", "send a message to myself saying hi", "send a message to me saying hi",
            "send myself a message saying hi", "mujhe khud ko bolo hi", "khud ko message bhejo ki hi", "खुद को बोलो hi",
            "message myself hi",
        ],
    )
    fun `myself me and mujhe khud ko mean the users own chat`(text: String) {
        val c = reply(text)
        assertEquals(Names.SELF, c.contact, text)
        assertEquals("hi", c.text, text)
    }

    @Test
    fun `the WhatsApp own-chat mark is understood`() {
        assertEquals("Aaditya Goel", WhatsApp.personName("Aaditya Goel (You)"))
        assertEquals("Aaditya Goel", WhatsApp.personName("Aaditya Goel (आप)"))
        assertEquals("Rahul Sharma", WhatsApp.personName("Rahul Sharma"))
        listOf("Aaditya Goel (You)", "Aaditya Goel (you)", "You", "Message yourself", "आप").forEach { assertTrue(WhatsApp.isSelfChat(it), it) }
        listOf("Aaditya Goel", "Thank You Group", "Youth Club").forEach { assertFalse(WhatsApp.isSelfChat(it), it) }
    }

    // == the flows ====================================================================================

    private class Run(val outcome: Outcome, val confirms: List<ConfirmRequest>, val picks: List<ContactChoiceRequest>)

    private fun run(fake: FakeWhatsApp, command: Command, phoneBook: List<String> = emptyList(), answer: Answer = Answer.YES): Run = runBlocking {
        val confirms = mutableListOf<ConfirmRequest>()
        val picks = mutableListOf<ContactChoiceRequest>()
        val flow = Flows.forCommand(command, t, gate, phoneBook = phoneBook)!!
        val out = AgentLoop(GatedDevice(gate, fake), t).run(
            flow,
            Flows.contextFor(command, phoneBook),
            {},
            pick = { req -> picks += req; null },
        ) { req ->
            confirms += req
            fake.pkg = "app.saathi" // the Confirm screen covers WhatsApp
            answer
        }
        Run(out, confirms, picks)
    }

    private fun selfWorld() = FakeWhatsApp(
        chats = linkedMapOf(
            "Mummy" to mutableListOf(),
            "Aaditya Sharma" to mutableListOf(),
            "Aaditya Goel (You)" to mutableListOf(),
        ),
    )

    @Test
    fun `message Aaditya Goel reaches the own chat shown as Aaditya Goel (You) with no question asked`() {
        for (text in listOf("message Aaditya Goel saathi self test", "send a message to Aaditya Goel saying saathi self test")) {
            for (book in listOf(emptyList(), listOf("Aaditya"))) {
                val fake = selfWorld()
                val r = run(fake, reply(text, book), book)
                assertTrue(r.outcome is Outcome.Answered, "$text $book ${r.outcome}")
                assertEquals(listOf("Aaditya Goel (You)" to "saathi self test"), fake.sent, "$text $book")
                assertEquals("Aaditya Goel (You)", r.confirms.single().recipient)
                assertTrue(r.picks.isEmpty())
            }
        }
    }

    @Test
    fun `nobody under the longer reading - the shorter one is used with its own text and read back`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Mummy" to mutableListOf(), "Aaditya" to mutableListOf()))
        val r = run(fake, reply("message Aaditya Goel saathi self test"))
        assertTrue(r.outcome is Outcome.Answered, "${r.outcome}")
        val typed = fake.performed.filterIsInstance<TypeText>().map { it.text }
        assertEquals(listOf("Aaditya Goel", "Aaditya", "Goel saathi self test"), typed) // longest first
        assertEquals("Aaditya", r.confirms.single().recipient)
        assertEquals("Goel saathi self test", r.confirms.single().message)
        assertEquals(listOf("Aaditya" to "Goel saathi self test"), fake.sent)
    }

    @Test
    fun `nobody under any reading - honest and nothing typed`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Mummy" to mutableListOf()))
        val r = run(fake, reply("message Aaditya Goel saathi self test"))
        assertEquals(Outcome.Failed(t.couldntFind("Aaditya Goel")), r.outcome)
        assertTrue(fake.drafts.isEmpty() && fake.sent.isEmpty())
    }

    @Test
    fun `message myself reaches the chat WhatsApp marks as yours`() {
        val fake = selfWorld()
        val r = run(fake, reply("message myself saying saathi self test"))
        assertTrue(r.outcome is Outcome.Answered, "${r.outcome}")
        assertEquals(listOf("Aaditya Goel (You)" to "saathi self test"), fake.sent)
        assertEquals("Aaditya Goel (You)", r.confirms.single().recipient)
    }

    @Test
    fun `message myself without an own chat in WhatsApp is said honestly`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Mummy" to mutableListOf(), "Aaditya Sharma" to mutableListOf()))
        val r = run(fake, reply("message myself saying hi"))
        assertEquals(Outcome.Failed(t.selfChatNotFound), r.outcome)
        assertTrue(fake.sent.isEmpty() && fake.drafts.isEmpty())
    }

    // == opening WhatsApp =============================================================================

    /** OpenApps before the first thing typed (the one after the Confirm sheet brings WhatsApp back). */
    private fun opensBeforeTyping(fake: FakeWhatsApp): Int =
        fake.performed.takeWhile { it !is TypeText }.count { it is OpenApp }

    private fun mummy(fake: FakeWhatsApp = FakeWhatsApp(chats = linkedMapOf("Mummy" to mutableListOf(Msg("them", "Call me"))))) = fake

    private val toMummy = Command.Reply("Mummy", "on my way", "reply to Mummy on my way")

    @Test
    fun `replay of 22-50 - a first frame and Back closing WhatsApp and a slow reopen no longer fail`() {
        val fake = mummy()
        fake.ownScreenChanges = true // Saathi's narration changes its own screen right after each OpenApp
        fake.openDelaysMs = listOf(0L, 2_000L)
        fake.splashMs = 500L // Back on this first frame closes WhatsApp
        val r = run(fake, toMummy)
        assertTrue(r.outcome is Outcome.Answered, "${r.outcome}")
        assertEquals(listOf("Mummy" to "on my way"), fake.sent)
        assertFalse(fake.performed.any { it is Back }, "never Back on WhatsApp's first frame")
        assertEquals(1, opensBeforeTyping(fake))
    }

    @Test
    fun `a slow WhatsApp is waited for instead of being reopened until it fails`() {
        val fake = mummy()
        fake.ownScreenChanges = true
        fake.openDelaysMs = listOf(4_000L)
        fake.splashMs = 3_000L
        val r = run(fake, toMummy)
        assertTrue(r.outcome is Outcome.Answered, "${r.outcome}")
        assertEquals(listOf("Mummy" to "on my way"), fake.sent)
        assertEquals(1, opensBeforeTyping(fake))
        assertFalse(fake.performed.any { it is Back })
    }

    @Test
    fun `WhatsApp opening on a screen whose Back closes it - its own Up button is used`() {
        val fake = mummy()
        fake.view = FakeWhatsApp.View.Sub(hasUp = true)
        fake.subIsRoot = true
        val r = run(fake, toMummy)
        assertTrue(r.outcome is Outcome.Answered, "${r.outcome}")
        assertEquals(listOf("Mummy" to "on my way"), fake.sent)
        assertEquals(1, fake.performed.count { it is Back }) // Back once (it closed WhatsApp), then Up
        assertTrue(fake.performed.any { it is Tap })
        assertEquals(2, opensBeforeTyping(fake))
    }

    @Test
    fun `no way out of that screen - an honest stop with no loop of reopening and nothing typed`() {
        val fake = mummy()
        fake.view = FakeWhatsApp.View.Sub(hasUp = false)
        fake.subIsRoot = true
        val r = run(fake, toMummy)
        assertEquals(Outcome.Failed(t.whatsappStuck), r.outcome)
        assertTrue(fake.performed.count { it is OpenApp } <= 2)
        assertTrue(fake.drafts.isEmpty() && fake.sent.isEmpty())
    }

    @Test
    fun `WhatsApp that never comes to the front - says which app could not open`() {
        val fake = mummy()
        fake.ownScreenChanges = true
        fake.openDelaysMs = listOf(1_000_000L)
        fake.splashMs = 1L
        val r = run(fake, toMummy)
        assertEquals(Outcome.Failed(t.appCouldNotOpen("WhatsApp")), r.outcome)
        assertEquals("I couldn't open WhatsApp.", r.outcome.message)
        assertTrue(fake.performed.count { it is OpenApp } <= ChatOpener.MAX_OPENS)
    }

    @Test
    fun `WhatsApp stuck on its first frame - says it could not open and never presses Back there`() {
        val fake = mummy()
        fake.splashMs = 1_000_000L
        val r = run(fake, toMummy)
        assertEquals(Outcome.Failed(t.appCouldNotOpen("WhatsApp")), r.outcome)
        assertFalse(fake.performed.any { it is Back })
    }
}
