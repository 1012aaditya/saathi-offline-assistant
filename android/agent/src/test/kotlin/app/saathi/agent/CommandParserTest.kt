package app.saathi.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class CommandParserTest {
    private fun p(text: String, session: Session? = null, known: List<String> = emptyList()) =
        CommandParser.parse(text, session, known)

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "Read Mom's last message|Mom",
            "read mom’s latest message|mom",
            "What's Dad's last message?|Dad",
            "Read the last message from Priya|Priya",
            "messages from Rahul Sharma|Rahul Sharma",
            "What did Mom say|Mom",
            "Mummy ka last message padho|Mummy",
            "WhatsApp par Mummy ke pichle message padho|Mummy",
            "Papa ka message sunao|Papa",
            "मम्मी का आखिरी मैसेज पढ़ो|मम्मी",
            "Didi ne kya bheja|Didi",
        ],
    )
    fun `read last message with contact`(text: String, contact: String) {
        val c = p(text)
        assertTrue(c is Command.ReadLast, "$text -> $c")
        assertEquals(contact, (c as Command.ReadLast).contact)
        assertEquals(text, c.utterance)
    }

    @Test
    fun `read last without a name uses the last contact, else asks`() {
        assertEquals(Command.ReadLast(null, "read the last message"), p("read the last message"))
        val s = Session().apply { lastContact = "Mom" }
        assertEquals("Mom", (p("read the last message", s) as Command.ReadLast).contact)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Who messaged me?", "Kiske message aaye hain", "kis ka message aaya", "unread messages",
            "Any new messages?", "Read my messages", "naye message batao", "किसके संदेश आए हैं", "नए मैसेज",
        ],
    )
    fun `unread summary`(text: String) {
        assertTrue(p(text) is Command.UnreadSummary, "$text -> ${p(text)}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["What's on screen?", "what is on my screen", "Screen pe kya hai", "स्क्रीन पर क्या है", "read the screen", "kya likha hai"])
    fun `screen summary`(text: String) {
        assertTrue(p(text) is Command.ScreenSummary, "$text -> ${p(text)}")
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "reply to Mom I'll be home by 7|Mom|I'll be home by 7",
            "Reply to Mom: I'll be home by 7.|Mom|I'll be home by 7",
            "reply to Mom saying on my way|Mom|on my way",
            "tell Dad that I reached|Dad|I reached",
            "send a message to Priya saying see you soon|Priya|see you soon",
            "send Priya a message saying see you soon|Priya|see you soon",
            "text Riya hello there|Riya|hello there",
            "Mom ko bolo main aa raha hoon|Mom|main aa raha hoon",
            "Mummy ko reply karo: main aa raha hoon|Mummy|main aa raha hoon",
            "Papa ko message bhejo ki main late hoon|Papa|main late hoon",
            "Mummy ko main aa raha hoon bol do|Mummy|main aa raha hoon",
            "मम्मी को बोलो मैं आ रहा हूँ|मम्मी|मैं आ रहा हूँ",
        ],
    )
    fun `reply with contact and text`(text: String, contact: String, message: String) {
        val c = p(text)
        assertTrue(c is Command.Reply, "$text -> $c")
        c as Command.Reply
        assertEquals(contact, c.contact)
        assertEquals(message, c.text)
    }

    @Test
    fun `multi-word saved names are preferred`() {
        val c = p("reply to Rahul Sharma I'm coming", known = listOf("Rahul Sharma")) as Command.Reply
        assertEquals("Rahul Sharma", c.contact)
        assertEquals("I'm coming", c.text)
        val d = p("reply to Rahul Sharma: I'm coming") as Command.Reply
        assertEquals("Rahul Sharma", d.contact)
    }

    @Test
    fun `reply without text or name is incomplete, not guessed`() {
        assertEquals(Command.Reply("Mom", null, "Mom ko bolo"), p("Mom ko bolo"))
        assertEquals(Command.Reply("Mom", null, "message Mom"), p("message Mom"))
        val r = p("reply karo: main aa raha hoon") as Command.Reply
        assertNull(r.contact)
        assertEquals("main aa raha hoon", r.text)
        val s = Session().apply { lastContact = "Mummy" }
        assertEquals("Mummy", (p("reply karo: main aa raha hoon", s) as Command.Reply).contact)
    }

    @Test
    fun `a pending reply takes the next utterance as the message`() {
        val s = Session().apply { pendingReplyTo = "Mom" }
        assertEquals(Command.Reply("Mom", "I'll be home by 7", "I'll be home by 7"), p("I'll be home by 7", s))
        assertNull(s.pendingReplyTo)
        val s2 = Session().apply { pendingReplyTo = "Mom" }
        assertTrue(p("cancel", s2) is Command.Stop)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = ["Open WhatsApp|whatsapp", "WhatsApp kholo|whatsapp", "whatsapp|whatsapp", "व्हाट्सएप खोलो|व्हाट्सएप", "open google pay|google pay", "launch Blinkit|blinkit", "open phone settings|settings"],
    )
    fun `open app uses the safety alias list`(text: String, alias: String) {
        val c = p(text)
        assertTrue(c is Command.OpenApp, "$text -> $c")
        assertEquals(alias, (c as Command.OpenApp).alias)
        assertEquals(app.saathi.safety.Apps.APP_ALIASES[alias], c.packageName)
    }

    @ParameterizedTest
    @ValueSource(strings = ["stop", "Cancel", "ruko", "bas", "रुको"])
    fun stop(text: String) = assertTrue(p(text) is Command.Stop)

    @ParameterizedTest
    @ValueSource(strings = ["help", "madad", "What can you do?", "मदद"])
    fun help(text: String) = assertTrue(p(text) is Command.Help)

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = ["Go home|HOME", "walk mode|WALK", "open settings|SETTINGS", "activity|ACTIVITY", "safety|SAFETY"])
    fun navigation(text: String, dest: Destination) {
        assertEquals(Command.Navigate(dest, text), p(text))
    }

    @ParameterizedTest
    @ValueSource(strings = ["Pay Rahul 5 rupees", "open Instagram", "order milk", "call Mom", "Papa ko call karo", "what's the weather", ""])
    fun `anything else is unsupported, never guessed`(text: String) {
        assertTrue(p(text) is Command.Unsupported, "$text -> ${p(text)}")
    }
}
