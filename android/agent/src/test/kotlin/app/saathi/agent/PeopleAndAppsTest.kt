package app.saathi.agent

import app.saathi.safety.Answer
import app.saathi.safety.Apps
import app.saathi.safety.Kind
import app.saathi.safety.OpenApp
import app.saathi.safety.SafetyGate
import app.saathi.safety.Tap
import app.saathi.safety.TypeText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** "Message anyone" and "open any app": parser, contact search, app resolution, launch policy. All data made up. */
class PeopleAndAppsTest {
    private val gate = SafetyGate()
    private val t = Texts(Lang.EN)

    // == parser: message anyone =====================================================================

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "message Rahul I'm on my way|Rahul|I'm on my way",
            "Message Rahul, I'm on my way|Rahul|I'm on my way",
            "send a message to Priya saying I'll be late|Priya|I'll be late",
            "send a WhatsApp message to Priya saying see you soon|Priya|see you soon",
            "send Priya a message saying hello|Priya|hello",
            "text Priya see you at 6|Priya|see you at 6",
            "Rahul ko bolo main aa raha hoon|Rahul|main aa raha hoon",
            "Rahul ko message bhejo ki main late hoon|Rahul|main late hoon",
            "Didi ko bolo khana ready hai|Didi|khana ready hai",
            "राहुल को मैसेज करो कि मैं आ रहा हूँ|राहुल|मैं आ रहा हूँ",
            "मम्मी को बोलो मैं आ रहा हूँ|मम्मी|मैं आ रहा हूँ",
            "Family Group ko bolo dinner at 8|Family Group|dinner at 8",
            "send a message to Family Group saying dinner at 8|Family Group|dinner at 8",
        ],
    )
    fun `message anyone, every phrasing`(text: String, contact: String, message: String) {
        val c = CommandParser.parse(text)
        assertTrue(c is Command.Reply, "$text -> $c")
        assertEquals(contact, (c as Command.Reply).contact)
        assertEquals(message, c.text)
    }

    @Test
    fun `message a name with no text asks what to say`() {
        val c = CommandParser.parse("message Rahul") as Command.Reply
        assertEquals("Rahul", c.contact)
        assertNull(c.text)
        assertEquals(t.whatToSay("Rahul"), Flows.missing(c, t))
        val hi = CommandParser.parse("राहुल को मैसेज करो") as Command.Reply
        assertEquals("राहुल", hi.contact)
        assertNull(hi.text)
    }

    @Test
    fun `a saved multi-word name is kept whole`() {
        val c = CommandParser.parse("message Rahul Sharma I'm on my way", knownNames = listOf("Rahul Sharma")) as Command.Reply
        assertEquals("Rahul Sharma", c.contact)
        assertEquals("I'm on my way", c.text)
    }

    // == parser: open any app ======================================================================

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "open camera|camera",
            "Open the camera app|camera",
            "open photos|photos",
            "open gallery|gallery",
            "open YouTube|YouTube",
            "launch the Spotify app|Spotify",
            "open google maps|google maps",
            "Instagram kholo|Instagram",
            "Instagram khol do|Instagram",
            "YouTube chalu karo|YouTube",
            "कैमरा खोलो|कैमरा",
            "यूट्यूब खोल दो|यूट्यूब",
            "zara camera kholo|camera",
            "open phone|phone",
        ],
    )
    fun `open any app`(text: String, name: String) {
        val c = CommandParser.parse(text)
        assertTrue(c is Command.LaunchApp, "$text -> $c")
        assertEquals(name, (c as Command.LaunchApp).name)
        assertEquals(text, c.utterance)
    }

    @Test
    fun `safety alias apps and Saathi's own screens keep their own commands`() {
        assertTrue(CommandParser.parse("open WhatsApp") is Command.OpenApp)
        assertTrue(CommandParser.parse("Blinkit kholo") is Command.OpenApp)
        assertEquals(Command.Navigate(Destination.WALK, "start walk mode"), CommandParser.parse("start walk mode"))
        assertEquals(Command.Navigate(Destination.SETTINGS, "open settings"), CommandParser.parse("open settings"))
        assertTrue(CommandParser.parse("open braille") !is Command.LaunchApp)
        assertTrue(CommandParser.parse("open Rahul's chat") !is Command.LaunchApp)
        assertTrue(CommandParser.parse("Read Mom's last message") is Command.ReadLast)
    }

    // == phone contacts ==============================================================================

    private val book = listOf("Mummy", "Papa", "Rahul Sharma", "Rahul Verma", "Priya", "Maa Ji Office")

    @Test
    fun `spoken names and relations resolve to the saved contact`() {
        assertEquals(Recipients.Match.One("Mummy"), Recipients.match("Mom", book))
        assertEquals(Recipients.Match.One("Priya", exact = true), Recipients.match("priya", book))
        assertEquals(Recipients.Match.One("Maa Ji Office", exact = false), Recipients.match("Maa", listOf("Maa Ji Office", "Papa")))
        assertEquals(Recipients.Match.One("Mummy"), Recipients.match("मम्मी", book))
        assertEquals(Recipients.Match.One("Papa"), Recipients.match("Dad", book))
        assertEquals(Recipients.Match.One("Priya"), Recipients.match("प्रिया", book))
        assertEquals(Recipients.Match.Many(listOf("Rahul Sharma", "Rahul Verma")), Recipients.match("Rahul", book))
        assertEquals(Recipients.Match.Many(listOf("Rahul Sharma", "Rahul Verma")), Recipients.match("राहुल", book))
        assertEquals(Recipients.Match.None, Recipients.match("Zoya", book))
        assertEquals(Recipients.Match.None, Recipients.match("Mom", emptyList()))
    }

    @Test
    fun `the saved name is searched first and is a safety name`() {
        val r = Recipients.of("Mom", book)
        assertEquals("Mummy", r.saved)
        assertTrue(r.inPhone)
        assertEquals("Mummy", r.queries.first())
        assertTrue(r.queries.size <= Recipients.MAX_QUERIES)
        assertTrue("Mummy" in r.context("Mom ko bolo hi").names)
        // A fuzzy-only match ("Rahul" -> "Rahul Sharma") is searched after the spoken name: every Rahul shows up.
        val fuzzy = Recipients.of("Rahul", listOf("Rahul Sharma", "Papa"))
        assertEquals("Rahul Sharma", fuzzy.saved)
        assertEquals(listOf("Rahul", "Rahul Sharma"), fuzzy.queries.take(2))
        // Without the phone's contacts: the spoken name, then other spellings; never "in phone".
        val none = Recipients.of("राहुल")
        assertEquals(listOf("राहुल", "rahul"), none.queries)
        assertFalse(none.inPhone)
        assertNull(none.saved)
    }

    // == finding the person in WhatsApp ============================================================

    private class Run(val outcome: Outcome, val confirms: List<ConfirmRequest>, val picks: List<ContactChoiceRequest>)

    private fun run(
        fake: FakeWhatsApp,
        command: Command,
        phoneBook: List<String> = emptyList(),
        pick: (suspend (ContactChoiceRequest) -> String?)? = null,
        answer: Answer = Answer.YES,
    ): Run = runBlocking {
        val confirms = mutableListOf<ConfirmRequest>()
        val picks = mutableListOf<ContactChoiceRequest>()
        val flow = Flows.forCommand(command, t, gate, phoneBook = phoneBook)!!
        val loop = AgentLoop(GatedDevice(gate, fake), t)
        val wrapped: (suspend (ContactChoiceRequest) -> String?)? = pick?.let { p -> { req -> picks += req; fake.pkg = "app.saathi"; p(req) } }
        val out = loop.run(flow, Flows.contextFor(command, phoneBook), {}, pick = wrapped) { req ->
            confirms += req
            fake.pkg = "app.saathi" // the Confirm screen covers WhatsApp
            answer
        }
        Run(out, confirms, picks)
    }

    private fun reply(contact: String, text: String) = Command.Reply(contact, text, "message $contact $text")

    @Test
    fun `a chat found by search is opened, typed, held, and sent only after a yes`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Mom" to mutableListOf(), "Rahul Sharma" to mutableListOf(Msg("them", "Match at 6?"))))
        fake.searchHeaders = true
        val r = run(fake, reply("Rahul", "on my way"))
        assertTrue(r.outcome is Outcome.Answered, "${r.outcome}")
        assertEquals(listOf("Rahul Sharma" to "on my way"), fake.sent)
        assertEquals("Rahul Sharma", r.confirms.single().recipient)
        assertEquals("R2", r.confirms.single().rule)
        // The partial match on the chat list wasn't taken: Saathi searched first.
        assertTrue(fake.performed.any { it is TypeText && it.text == "Rahul" })
    }

    @Test
    fun `a contact without a chat is found under Contacts and a new chat is opened`() {
        for (headers in listOf(true, false)) {
            val fake = FakeWhatsApp(chats = linkedMapOf("Mom" to mutableListOf()))
            fake.contacts += "Priya Kapoor"
            fake.searchHeaders = headers
            val r = run(fake, reply("Priya", "See you at 6"))
            assertTrue(r.outcome is Outcome.Answered, "headers=$headers ${r.outcome}")
            assertEquals(listOf("Priya Kapoor" to "See you at 6"), fake.sent)
            assertEquals("Priya Kapoor", r.confirms.single().recipient)
        }
    }

    @Test
    fun `contact rows with the contact picker id count too`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Mom" to mutableListOf()))
        fake.contacts += "Priya Kapoor"
        fake.searchHeaders = true
        fake.contactRid = "contactpicker_row_name"
        val r = run(fake, reply("Priya", "hi"))
        assertTrue(r.outcome is Outcome.Answered, "${r.outcome}")
        assertEquals(listOf("Priya Kapoor" to "hi"), fake.sent)
    }

    @Test
    fun `a fuzzy phone match still asks when WhatsApp has another Rahul`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Rahul Sharma" to mutableListOf(), "Rahul Gym Group" to mutableListOf()))
        val r = run(fake, reply("Rahul", "on my way"), phoneBook = listOf("Rahul Sharma"), pick = { "Rahul Gym Group" })
        assertEquals(listOf("Rahul Sharma", "Rahul Gym Group"), r.picks.single().options)
        assertEquals(listOf("Rahul Gym Group" to "on my way"), fake.sent)
    }

    @Test
    fun `several matches ask which one and only the picked person gets the message`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Rahul Sharma" to mutableListOf()))
        fake.contacts += "Rahul Verma"
        fake.searchHeaders = true
        val r = run(fake, reply("Rahul", "on my way"), pick = { req ->
            assertEquals(listOf("Rahul Sharma", "Rahul Verma"), req.options)
            assertEquals("Rahul", req.spoken)
            assertTrue(fake.drafts.isEmpty() && fake.sent.isEmpty()) // nothing typed while asking
            "Rahul Verma"
        })
        assertTrue(r.outcome is Outcome.Answered, "${r.outcome}")
        assertEquals(1, r.picks.size)
        assertEquals(listOf("Rahul Verma" to "on my way"), fake.sent)
        assertEquals("Rahul Verma", r.confirms.single().recipient)
        assertTrue(fake.chats.getValue("Rahul Sharma").isEmpty())
    }

    @Test
    fun `several matches with no clear pick stop, nothing typed or sent`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Rahul Sharma" to mutableListOf()))
        fake.contacts += "Rahul Verma"
        val r = run(fake, reply("Rahul", "on my way"), pick = { null })
        assertEquals(Outcome.Cancelled(t.contactNotChosen), r.outcome)
        assertTrue(fake.sent.isEmpty())
        assertTrue(fake.drafts.isEmpty())
        assertTrue(r.confirms.isEmpty())
    }

    @Test
    fun `a pick that isn't one of the options is never used`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Rahul Sharma" to mutableListOf()))
        fake.contacts += "Rahul Verma"
        val r = run(fake, reply("Rahul", "on my way"), pick = { "Rahul Kumar" })
        assertTrue(r.outcome is Outcome.Cancelled, "${r.outcome}")
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun `nobody found is said honestly, nothing typed`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Mom" to mutableListOf()))
        val r = run(fake, reply("Zoya", "hi"))
        assertEquals(Outcome.Failed(t.couldntFind("Zoya")), r.outcome)
        assertEquals("I couldn't find Zoya in WhatsApp.", r.outcome.message)
        assertTrue(fake.sent.isEmpty() && fake.drafts.isEmpty())
    }

    @Test
    fun `in the phone's contacts but not in WhatsApp says so, no SMS`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Mom" to mutableListOf()))
        val r = run(fake, reply("Zoya", "hi"), phoneBook = listOf("Zoya Khan"))
        assertEquals(Outcome.Failed(t.notOnWhatsApp("Zoya")), r.outcome)
        assertTrue("may not use WhatsApp" in r.outcome.message)
        assertTrue(fake.sent.isEmpty())
    }

    @Test
    fun `message hits under Messages are not people`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Mom" to mutableListOf()))
        fake.messageHits += "Mom" to "Rahul is coming"
        val r = run(fake, reply("Rahul", "hi"))
        assertEquals(Outcome.Failed(t.couldntFind("Rahul")), r.outcome)
        assertTrue(fake.view is FakeWhatsApp.View.Search) // never opened Mom's chat
    }

    @Test
    fun `the phone's saved name is searched for a relation word`() {
        val world = { FakeWhatsApp(chats = linkedMapOf("Priya" to mutableListOf())).apply { contacts += "Amma Ji" } }
        // "Mom" alone: WhatsApp has no "Mom" / "Mommy" / "Mummy" / "Mumma": honest failure.
        val without = world()
        val r1 = run(without, reply("Mom", "coming home"))
        assertEquals(Outcome.Failed(t.couldntFind("Mom")), r1.outcome)
        assertTrue(without.performed.count { it is TypeText } <= Recipients.MAX_QUERIES)
        // With the phone's contacts: "Mom" -> the saved "Amma Ji" is searched and messaged.
        val with = world()
        val r2 = run(with, reply("Mom", "coming home"), phoneBook = listOf("Amma Ji", "Priya"))
        assertTrue(r2.outcome is Outcome.Answered, "${r2.outcome}")
        assertEquals(listOf("Amma Ji" to "coming home"), with.sent)
    }

    @Test
    fun `a Devanagari name is searched again in Latin letters`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Mom" to mutableListOf(), "Rahul Sharma" to mutableListOf()))
        val r = run(fake, reply("राहुल", "aa raha hoon"))
        assertTrue(r.outcome is Outcome.Answered, "${r.outcome}")
        assertEquals(listOf("राहुल", "rahul"), fake.performed.filterIsInstance<TypeText>().map { it.text }.take(2))
        assertEquals(listOf("Rahul Sharma" to "aa raha hoon"), fake.sent)
    }

    @Test
    fun `a group chat counts as a name`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Mom" to mutableListOf(), "Family Group" to mutableListOf()))
        val r = run(fake, CommandParser.parse("Family Group ko bolo dinner at 8"))
        assertTrue(r.outcome is Outcome.Answered, "${r.outcome}")
        assertEquals(listOf("Family Group" to "dinner at 8"), fake.sent)
    }

    @Test
    fun `a no on the read-back sends nothing, even after a pick`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Rahul Sharma" to mutableListOf()))
        fake.contacts += "Rahul Verma"
        val r = run(fake, reply("Rahul", "on my way"), pick = { "Rahul Sharma" }, answer = Answer.NO)
        assertTrue(r.outcome is Outcome.Cancelled, "${r.outcome}")
        assertTrue(fake.sent.isEmpty())
        assertTrue(fake.drafts.isEmpty()) // the typed text was removed
    }

    @Test
    fun `reading the last message also asks which one`() {
        val fake = FakeWhatsApp(chats = linkedMapOf("Rahul Sharma" to mutableListOf(Msg("them", "Match at 6?")), "Rahul Verma" to mutableListOf(Msg("them", "Lunch?"))))
        val r = run(fake, Command.ReadLast("Rahul", "read Rahul's last message"), pick = { "Rahul Verma" })
        assertEquals("Lunch?", (r.outcome as Outcome.Answered).quote)
    }

    @Test
    fun `two rows with exactly the same name can't be told apart`() {
        val s = S.build(
            WhatsApp.PKG,
            { S.el(it, text = "Rahul", rid = "search_input", cls = "EditText", editable = true, y = 100) },
            { S.el(it, text = "Chats", y = 200) },
            { S.el(it, text = "Rahul", rid = WhatsApp.ROW_NAME, y = 300) },
            { S.el(it, text = "Rahul", rid = WhatsApp.ROW_NAME, y = 420) },
        )
        val ctx = Recipients.of("Rahul").context("x")
        assertEquals(ContactSearch.Pick.Same("Rahul"), ContactSearch.pick(s, "Rahul", ctx))
        assertTrue("same name" in t.sameName("Rahul"))
    }

    @Test
    fun `search sections are read from the headers above each row`() {
        val s = S.build(
            WhatsApp.PKG,
            { S.el(it, text = "Chats", y = 200) },
            { S.el(it, text = "Rahul Sharma", rid = WhatsApp.ROW_NAME, y = 300) },
            { S.el(it, text = "Contacts", y = 400) },
            { S.el(it, text = "Rahul Verma", rid = WhatsApp.ROW_NAME, y = 500) },
            { S.el(it, text = "Messages", y = 600) },
            { S.el(it, text = "Mom", rid = WhatsApp.ROW_NAME, y = 700) },
            { S.el(it, text = "Chats", cls = "Button", clickable = true, y = 1900) }, // a tab, not a header
        )
        val rows = ContactSearch.rows(s).map { it.element.text to it.section }
        assertEquals(
            listOf("Rahul Sharma" to ContactSearch.Section.CHATS, "Rahul Verma" to ContactSearch.Section.CONTACTS, "Mom" to ContactSearch.Section.MESSAGES),
            rows,
        )
    }

    // == which one? (voice) ==========================================================================

    private val two = listOf("Rahul Sharma", "Rahul Verma")

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "Sharma|Rahul Sharma", "Rahul Verma|Rahul Verma", "verma please|Rahul Verma", "the first one|Rahul Sharma",
            "second|Rahul Verma", "doosra wala|Rahul Verma", "दूसरा|Rahul Verma", "शर्मा|Rahul Sharma", "sharmaa|Rahul Sharma",
            "last one|Rahul Verma",
        ],
    )
    fun `a pick names exactly one option`(reply: String, expected: String) {
        assertEquals(expected, ContactChoiceDialog.classify(reply, two))
    }

    @ParameterizedTest
    @ValueSource(strings = ["Rahul", "both", "Sharma or Verma", "I think it's the one from office I talked to yesterday", "first second", ""])
    fun `an unclear pick is never a guess`(reply: String) {
        val c = ContactChoiceDialog.classify(reply, two)
        assertTrue(c == null, "$reply -> $c")
    }

    @Test
    fun `no or cancel stops, unclear twice stops, an echo of the question is unclear`() {
        assertEquals(ContactChoiceDialog.CANCEL, ContactChoiceDialog.classify("no", two))
        assertEquals(ContactChoiceDialog.CANCEL, ContactChoiceDialog.classify("रहने दो", two))
        assertEquals(ContactChoiceDialog.Next.Cancel, ContactChoiceDialog(two).hear("cancel"))
        val d = ContactChoiceDialog(two)
        val question = t.whichContact("Rahul", two)
        assertEquals(ContactChoiceDialog.Next.AskAgain, d.hear("which Rahul Rahul Sharma", listOf(question)))
        assertEquals(ContactChoiceDialog.Next.Cancel, d.hear("hmm"))
        assertEquals(ContactChoiceDialog.Next.Pick("Rahul Sharma"), ContactChoiceDialog(two).hear("Sharma", listOf(question)))
        assertEquals("Which Rahul: \"Rahul Sharma\" or \"Rahul Verma\"?", question)
    }

    // == open any app ==================================================================================

    private val phone = listOf(
        LaunchableApp("Camera", "com.android.camera2"),
        LaunchableApp("Photos", "com.google.android.apps.photos"),
        LaunchableApp("Gallery", "com.sec.android.gallery3d"),
        LaunchableApp("YouTube", "com.google.android.youtube"),
        LaunchableApp("Instagram", "com.instagram.android"),
        LaunchableApp("Google Maps", "com.google.android.apps.maps"),
        LaunchableApp("Google Pay", Apps.GPAY),
        LaunchableApp("PhonePe", "com.phonepe.app"),
        LaunchableApp("WhatsApp", Apps.WHATSAPP),
        LaunchableApp("Saathi", "app.saathi"),
        LaunchableApp("Spotify", "com.spotify.music"),
        LaunchableApp("Phone", "com.google.android.dialer"),
    )

    private fun one(spoken: String, apps: List<LaunchableApp> = phone): String? =
        (AppResolver.resolve(spoken, apps, "app.saathi") as? AppResolver.Result.One)?.app?.packageName

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "camera|com.android.camera2", "कैमरा|com.android.camera2", "Camera|com.android.camera2",
            "YouTube|com.google.android.youtube", "यूट्यूब|com.google.android.youtube", "yt|com.google.android.youtube",
            "Instagram|com.instagram.android", "insta|com.instagram.android", "इंस्टाग्राम|com.instagram.android",
            "maps|com.google.android.apps.maps", "google maps|com.google.android.apps.maps",
            "photos|com.google.android.apps.photos", "फोटो|com.google.android.apps.photos", "gallery|com.sec.android.gallery3d",
            "spotify app|com.spotify.music", "phone|com.google.android.dialer", "फ़ोन|com.google.android.dialer",
        ],
    )
    fun `an app name resolves to the installed app`(spoken: String, pkg: String) {
        assertEquals(pkg, one(spoken))
    }

    @Test
    fun `gallery and photos mean the phone's gallery app whatever it is called`() {
        val onlyPhotos = listOf(LaunchableApp("Photos", "com.google.android.apps.photos"), LaunchableApp("Camera", "com.android.camera2"))
        assertEquals("com.google.android.apps.photos", one("gallery", onlyPhotos))
        val samsung = listOf(LaunchableApp("Gallery", "com.sec.android.gallery3d"), LaunchableApp("Camera", "com.sec.android.app.camera"))
        assertEquals("com.sec.android.gallery3d", one("photos", samsung))
        assertEquals("com.sec.android.gallery3d", one("pictures", samsung))
        val miui = listOf(LaunchableApp("Album", "com.miui.gallery"))
        assertEquals("com.miui.gallery", one("gallery", miui))
    }

    @Test
    fun `several or no apps are never guessed, Saathi itself is never a target`() {
        val google = AppResolver.resolve("google", phone, "app.saathi")
        assertTrue(google is AppResolver.Result.Many, "$google")
        assertEquals(setOf("Google Maps", "Google Pay"), (google as AppResolver.Result.Many).apps.map { it.label }.toSet())
        assertEquals(AppResolver.Result.None, AppResolver.resolve("tinder", phone, "app.saathi"))
        assertEquals(AppResolver.Result.None, AppResolver.resolve("saathi", phone, "app.saathi"))
        assertEquals(AppResolver.Result.None, AppResolver.resolve("", phone, "app.saathi"))
    }

    // == launch policy =================================================================================

    private val insta = LaunchableApp("Instagram", "com.instagram.android")
    private val phonePe = LaunchableApp("PhonePe", "com.phonepe.app")

    private fun decide(app: LaunchableApp, approval: Approval? = null, paymentsOff: Boolean = true, groceryOff: Boolean = false) =
        LaunchPolicy.decide(app, approval, paymentsOff, groceryOff, "app.saathi")

    @Test
    fun `first time asks, approved opens, not allowed never opens`() {
        assertEquals(LaunchDecision.AskFirst, decide(insta))
        assertEquals(LaunchDecision.Launch, decide(insta, Approval.ALLOWED))
        assertEquals(LaunchDecision.Refuse(RefuseReason.NOT_ALLOWED), decide(insta, Approval.NOT_ALLOWED))
        assertEquals(LaunchDecision.Refuse(RefuseReason.SAATHI_ITSELF), decide(LaunchableApp("Saathi", "app.saathi"), Approval.ALLOWED))
    }

    @Test
    fun `payment apps never open while Payments is Off, even if approved before`() {
        assertEquals(LaunchDecision.Refuse(RefuseReason.PAYMENTS_OFF), decide(phonePe))
        assertEquals(LaunchDecision.Refuse(RefuseReason.PAYMENTS_OFF), decide(phonePe, Approval.ALLOWED))
        assertEquals(LaunchDecision.Refuse(RefuseReason.PAYMENTS_OFF), decide(LaunchableApp("Google Pay", Apps.GPAY), Approval.ALLOWED))
        assertEquals(LaunchDecision.Refuse(RefuseReason.PAYMENTS_OFF), decide(LaunchableApp("Some Bank", "com.example.mobile")))
        assertEquals(LaunchDecision.Refuse(RefuseReason.PAYMENTS_OFF), decide(LaunchableApp("CRED", "com.dreamplug.androidapp")))
        // Payments allowed: still asked the first time, and Not allowed still wins.
        assertEquals(LaunchDecision.AskFirst, decide(phonePe, paymentsOff = false))
        assertEquals(LaunchDecision.Launch, decide(phonePe, Approval.ALLOWED, paymentsOff = false))
        assertEquals(LaunchDecision.Refuse(RefuseReason.NOT_ALLOWED), decide(phonePe, Approval.NOT_ALLOWED, paymentsOff = false))
        assertFalse(LaunchPolicy.isPaymentApp("com.spotify.music", "Spotify"))
        assertFalse(LaunchPolicy.isPaymentApp("com.instagram.android", "Instagram"))
    }

    @Test
    fun `apps Saathi operates keep their own path and levels`() {
        assertEquals(LaunchDecision.Managed("whatsapp"), decide(LaunchableApp("WhatsApp", Apps.WHATSAPP)))
        assertEquals(LaunchDecision.Managed("gpay"), decide(LaunchableApp("Google Pay", Apps.GPAY), paymentsOff = false))
        assertEquals(LaunchDecision.Refuse(RefuseReason.GROCERY_OFF), decide(LaunchableApp("Blinkit", Apps.BLINKIT), groceryOff = true))
        assertEquals(LaunchDecision.Managed("blinkit"), decide(LaunchableApp("Blinkit", Apps.BLINKIT)))
    }

    @Test
    fun `the safety gate itself still refuses unknown apps (not weakened)`() {
        val s = S.build("app.saathi", { S.el(it, text = "Home") })
        assertEquals(Kind.BLOCK, gate.check(OpenApp("instagram"), s).kind)
        assertEquals(Kind.BLOCK, gate.check(OpenApp("com.instagram.android"), s).kind)
        assertEquals(Kind.ALLOW, gate.check(OpenApp("whatsapp"), s).kind)
    }

    @Test
    fun `nothing said while waiting for an answer contains a yes word`() {
        for (lang in Lang.entries) {
            val tx = Texts(lang)
            for (text in listOf(
                tx.appApprovalQuestion("Instagram"), tx.appApprovalHint, tx.whichContact("Rahul", two), tx.contactChoiceHint,
                tx.contactsWhy,
            )) {
                assertFalse(ConfirmDialog.containsYesWord(text), "$lang: $text")
            }
        }
    }

    @Test
    fun `refusals say why`() {
        assertTrue("Not allowed" in t.refused(RefuseReason.NOT_ALLOWED, "Instagram"))
        assertTrue("Payments" in t.refused(RefuseReason.PAYMENTS_OFF, "PhonePe"))
        assertTrue("PIN" !in t.appOpened("Instagram"))
        assertTrue("can't use Instagram for you offline yet" in t.appOpened("Instagram"))
        assertFalse(gate.check(Tap(0), S.build(WhatsApp.PKG, { S.el(it, desc = "Send", rid = "send") })).kind == Kind.ALLOW)
    }
}
