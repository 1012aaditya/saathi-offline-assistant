package app.saathi.agent

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import app.saathi.safety.SafetyGate

/**
 * The ONE voice-answer pipeline ([VoiceAnswer]) behind every sheet. 13 Oct, 22:51 on the tablet:
 * `item pick voice answer: Picked (unclear so far 0), length 54` -> a Blinkit item was added with
 * no human answer. These tests replay every sheet's own words as if the microphone had
 * transcribed them, at echo timing and later, and check real answers still work.
 */
class VoiceAnswerTest {
    private val en = Texts(Lang.EN)
    private val hi = Texts(Lang.HI)
    private val oEn = OrderTexts(Lang.EN)
    private val oHi = OrderTexts(Lang.HI)

    /** A user answering after Saathi finished (mic opens 700 ms after, they speak, result ~2.5 s after). */
    private val after = 2_500L

    /** Heard while Saathi spoke / within the echo window after it. */
    private val echoTimes = listOf(0L, 700L, 1_200L, 1_499L)

    /** Every run of consecutive words in [text] (what a recognizer could have caught of it). */
    private fun windows(text: String): List<String> {
        val w = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        return (1..w.size).flatMap { n -> w.windowed(n).map { it.joinToString(" ") } }
    }

    private fun words(text: String) = EchoGuard.tokens(text).size

    private enum class R { ACT, SAFE, ASK }

    /** One sheet: everything it says / shows, and a fresh dialog's verdict for one hearing. */
    private class Sheet(val name: String, val texts: List<String>, val byPosition: Boolean = false, val hear: (Heard, List<String>) -> R)

    // -- the sheets, with their real words --------------------------------------------------------

    private val msg = "saathi test two"
    private val leftover = "saathi test please ignore"
    private val two = listOf("Rahul Sharma", "Rahul Verma")

    /** The incident: a 54-character option (name, size, price exactly as the sheet shows it). */
    private val incident = Product("Mother Dairy Cow Toned Fresh Milk Pouch", "500 ml", "₹28")
    private val products = listOf(incident, Product("Amul Taaza Toned Milk", "500 ml", "₹27"))
    private val options = products.map(oEn::option)
    private val order = OrderSummary(listOf(CartLine("Amul Taaza Toned Milk", "500 ml", 1, "₹27")), "₹27")

    private fun confirmR(n: ConfirmDialog.Next) = when (n) {
        ConfirmDialog.Next.SEND -> R.ACT
        ConfirmDialog.Next.CANCEL -> R.SAFE
        ConfirmDialog.Next.ASK_AGAIN -> R.ASK
    }

    private fun draftR(n: DraftChoiceDialog.Next) = when (n) {
        DraftChoiceDialog.Next.KEEP -> R.SAFE
        DraftChoiceDialog.Next.ASK_AGAIN -> R.ASK
        else -> R.ACT
    }

    private fun pickR(n: PickDialog.Next) = when (n) {
        is PickDialog.Next.Picked -> R.ACT
        PickDialog.Next.Cancel -> R.SAFE
        PickDialog.Next.AskAgain -> R.ASK
    }

    private fun contactR(n: ContactChoiceDialog.Next) = when (n) {
        is ContactChoiceDialog.Next.Pick -> R.ACT
        ContactChoiceDialog.Next.Cancel -> R.SAFE
        ContactChoiceDialog.Next.AskAgain -> R.ASK
    }

    private val sheets: List<Sheet> = listOf(
        Sheet("confirm send", listOf(en.confirmQuestion("Mummy", msg), hi.confirmQuestion("Mummy", msg), en.confirmHint, hi.confirmHint, "Send to Mummy?", msg)) { h, s ->
            confirmR(ConfirmDialog().hear(h, s))
        },
        Sheet("confirm order", listOf(oEn.orderQuestion(order), oHi.orderQuestion(order), oEn.readback(order))) { h, s ->
            confirmR(ConfirmDialog(extraFillers = ConfirmDialog.ORDER_FILLERS).hear(h, s))
        },
        Sheet("empty cart", listOf(oEn.emptyQuestion(order.copy(purpose = OrderSummary.Purpose.EMPTY)), oHi.emptyQuestion(order.copy(purpose = OrderSummary.Purpose.EMPTY)))) { h, s ->
            confirmR(ConfirmDialog(extraFillers = ConfirmDialog.ORDER_FILLERS).hear(h, s))
        },
        Sheet("open an app", listOf(en.appApprovalQuestion("Camera"), hi.appApprovalQuestion("Camera"), en.appApprovalHint, hi.appApprovalHint)) { h, s ->
            confirmR(ConfirmDialog().hear(h, s))
        },
        Sheet("unsent text", listOf(en.draftQuestion("Mummy", leftover, true), hi.draftQuestion("Mummy", leftover, true), en.draftQuestion("Mummy", leftover, false))) { h, s ->
            draftR(DraftChoiceDialog().hear(h, true, s))
        },
        Sheet("which Rahul", listOf(en.whichContact("Rahul", two), hi.whichContact("Rahul", two), en.contactChoiceHint, hi.contactChoiceHint) + two, byPosition = true) { h, s ->
            contactR(ContactChoiceDialog(two).hear(h, s))
        },
        Sheet(
            "which one (grocery)",
            listOf(oEn.whichProduct(products), oEn.whichSize(products), oHi.whichProduct(products), oHi.whichSize(products), oEn.pickHint, oHi.pickHint) + options,
            byPosition = true,
        ) { h, s -> pickR(PickDialog(options).hear(h, s)) },
    )

    // -- replaying the sheets' own words ------------------------------------------------------------

    @Test
    fun `every window of every sheet text heard as an echo is never an answer`() {
        for (sheet in sheets) for (text in sheet.texts) for (w in windows(text)) for (ms in echoTimes) {
            assertEquals(R.ASK, sheet.hear(Heard(w, ms), sheet.texts), "${sheet.name}: \"$w\" at $ms ms")
        }
    }

    @Test
    fun `no window of 4 or more words is ever an answer however late it is heard`() {
        for (sheet in sheets) for (text in sheet.texts) for (w in windows(text)) {
            if (words(w) < 4) continue
            assertEquals(R.ASK, sheet.hear(Heard(w, after), sheet.texts), "${sheet.name}: \"$w\"")
            assertEquals(R.ASK, sheet.hear(Heard(w), sheet.texts), "${sheet.name}: \"$w\" (Saathi silent)")
        }
    }

    @Test
    fun `a window heard late never says yes or a position - only a name said on its own`() {
        for (sheet in sheets) for (text in sheet.texts) for (w in windows(text)) {
            val r = sheet.hear(Heard(w, after), sheet.texts)
            if (r != R.ACT) continue
            // What remains: a 1-3 word name / choice word, as a human would say it. Never a yes, never a position.
            assertTrue(words(w) <= VoiceAnswers.MAX_NAME_WORDS, "${sheet.name}: \"$w\"")
            assertFalse(ConfirmDialog.containsYesWord(w), "${sheet.name}: \"$w\"")
            assertFalse(Ordinals.containsOrdinal(w), "${sheet.name}: \"$w\"")
            assertTrue(sheet.name in setOf("unsent text", "which Rahul", "which one (grocery)"), "${sheet.name} acted on \"$w\"")
        }
    }

    @Test
    fun `no sheet text has a yes word or a position word`() {
        for (sheet in sheets) for (text in sheet.texts) {
            assertFalse(ConfirmDialog.containsYesWord(text), "${sheet.name}: $text")
            if (sheet.byPosition) assertFalse(Ordinals.containsOrdinal(text), "${sheet.name}: $text")
        }
    }

    // -- the 22:51 incident --------------------------------------------------------------------------

    @Test
    fun `the 54-character option read-out is unclear and twice is cancel`() {
        val readout = options[0]
        assertEquals(54, readout.length)
        // The old sheet's classifier alone took this as option 1 (it names only that option).
        assertEquals(0, PickDialog.classify(readout, options))
        val sources = listOf(oEn.whichSize(products), oEn.pickHint)
        for (heard in listOf(readout, "Mother Dairy Cow Toned Fresh Milk Pouch 500 ml for 28 rupees", "mother dairy cow toned fresh milk pouch")) {
            for (ms in echoTimes + after + Heard.LONG_AFTER) {
                assertEquals(PickDialog.Next.AskAgain, PickDialog(options).hear(Heard(heard, ms), sources), "\"$heard\" at $ms")
            }
            val d = PickDialog(options)
            assertEquals(PickDialog.Next.AskAgain, d.hear(Heard(heard, after), sources))
            assertEquals(PickDialog.Next.Cancel, d.hear(Heard(heard, after), sources))
            assertEquals(VoiceAnswer.Why.TOO_LONG, d.lastWhy)
        }
        // Even with NO echo sources at all (a sheet that forgot them), the pipeline still adds the options.
        assertEquals(PickDialog.Next.AskAgain, PickDialog(options).hear(Heard("Mother Dairy Cow", after), emptyList()))
    }

    @Test
    fun `replay of 22-51 - a sheet that hears only the read-out adds nothing to the cart`() {
        val fake = FakeBlinkit(
            listOf(
                FakeBlinkit.Item("Mother Dairy Cow Toned Fresh Milk Pouch", "500 ml", 28),
                FakeBlinkit.Item("Mother Dairy Toned Milk", "500 ml", 28),
                FakeBlinkit.Item("Brown Bread", "400 g", 45),
            ),
        )
        val gate = SafetyGate()
        val policy = OrderPolicy(allowOrders = true)
        val command = CommandParser.parse("order Mother Dairy milk") as GroceryCommand
        val ctx = OrderFlows.contextFor(command, policy)
        val flow = OrderFlows.forCommand(command, policy, gate, ctx, oEn)!!
        var asked: PickRequest? = null
        val out = runBlocking {
            AgentLoop(GatedDevice(gate, fake), en).run(flow, ctx, {}, pickOption = { req ->
                asked = req
                fake.pkg = "app.saathi"
                val d = PickDialog(req.options)
                val sources = listOf(req.question, req.hint)
                // The mic hears the sheet reading its first option, twice (as on the tablet).
                var next = d.hear(Heard(req.options[0], after), sources)
                if (next == PickDialog.Next.AskAgain) next = d.hear(Heard(req.options[0], after), sources)
                (next as? PickDialog.Next.Picked)?.index
            }) { error("nothing may reach the order read-back") }
        }
        assertTrue(asked != null, "the close call was asked")
        assertTrue(out is Outcome.Cancelled, "$out")
        assertTrue(fake.addTaps.isEmpty(), "added ${fake.addTaps}")
        assertTrue(fake.orders.isEmpty())
    }

    // -- real answers still work -------------------------------------------------------------------

    private val pickSources = listOf(oEn.whichSize(products), oEn.pickHint, oHi.whichSize(products), oHi.pickHint) + options

    @Test
    fun `a position said after Saathi finished picks the right option`() {
        val cases = mapOf(
            "first" to 0, "the first one" to 0, "pehla" to 0, "pehla wala" to 0, "पहला" to 0, "1" to 0,
            "second" to 1, "doosra" to 1, "doosra wala" to 1, "दूसरा" to 1, "2" to 1, "last one" to 1,
        )
        for ((said, i) in cases) {
            assertEquals(PickDialog.Next.Picked(i), PickDialog(options).hear(Heard(said, after), pickSources), said)
            assertEquals(ContactChoiceDialog.Next.Pick(two[i]), ContactChoiceDialog(two).hear(Heard(said, after), listOf(en.whichContact("Rahul", two), en.contactChoiceHint)), said)
        }
        // ...but not while Saathi is speaking or just after (the 1.5 s quiet gap).
        for (ms in echoTimes) assertEquals(PickDialog.Next.AskAgain, PickDialog(options).hear(Heard("first", ms), pickSources))
    }

    @Test
    fun `a name said on its own picks - with more words or too soon it is unclear`() {
        assertEquals(PickDialog.Next.Picked(1), PickDialog(options).hear(Heard("Amul", after), pickSources))
        assertEquals(PickDialog.Next.Picked(1), PickDialog(options).hear(Heard("Amul wala", after), pickSources))
        assertEquals(PickDialog.Next.Picked(0), PickDialog(options).hear(Heard("Mother Dairy", after), pickSources))
        assertEquals(PickDialog.Next.AskAgain, PickDialog(options).hear(Heard("Mother Dairy", 1_000), pickSources))
        assertEquals(PickDialog.Next.AskAgain, PickDialog(options).hear(Heard("Mother Dairy Cow", after), pickSources)) // a run of the option: echo
        assertEquals(PickDialog.Next.AskAgain, PickDialog(options).hear(Heard("Mother Dairy please now", after), pickSources))
        assertEquals(PickDialog.Next.AskAgain, PickDialog(options).hear(Heard("Amul bread", after), pickSources)) // a word of no option
        assertEquals(ContactChoiceDialog.Next.Pick("Rahul Verma"), ContactChoiceDialog(two).hear(Heard("Verma", after), listOf(en.whichContact("Rahul", two))))
        assertEquals(ContactChoiceDialog.Next.AskAgain, ContactChoiceDialog(two).hear(Heard("Verma", 900), listOf(en.whichContact("Rahul", two))))
        assertEquals(PickDialog.Next.Cancel, PickDialog(options).hear(Heard("cancel", after), pickSources))
        assertEquals(PickDialog.Next.Cancel, PickDialog(options).hear(Heard("koi nahi", after), pickSources))
    }

    @Test
    fun `yes keep replace and no still work after Saathi finished`() {
        val q = listOf(en.confirmQuestion("Mummy", msg), en.confirmHint)
        assertEquals(ConfirmDialog.Next.SEND, ConfirmDialog().hear(Heard("haan", after), q))
        assertEquals(ConfirmDialog.Next.SEND, ConfirmDialog().hear(Heard("yes send it", after), q))
        assertEquals(ConfirmDialog.Next.CANCEL, ConfirmDialog().hear(Heard("no", after), q))
        assertEquals(ConfirmDialog.Next.ASK_AGAIN, ConfirmDialog().hear(Heard("haan", 1_000), q)) // too soon
        val d = listOf(en.draftQuestion("Mummy", leftover, true))
        assertEquals(DraftChoiceDialog.Next.REPLACE, DraftChoiceDialog().hear(Heard("badal do", after), true, d))
        assertEquals(DraftChoiceDialog.Next.KEEP, DraftChoiceDialog().hear(Heard("rehne do", after), true, d))
        assertEquals(DraftChoiceDialog.Next.ASK_AGAIN, DraftChoiceDialog().hear(Heard("badal do", 800), true, d))
    }

    // -- every dialog goes through the pipeline ------------------------------------------------------

    @Test
    fun `every sheet dialog applies the shared rules - too soon, too long, unclear twice is the safe default`() {
        // A valid answer for each sheet, and the same answer buried in a long transcript.
        val valid = mapOf(
            "confirm send" to "yes", "confirm order" to "yes order it", "empty cart" to "haan", "open an app" to "haan",
            "unsent text" to "replace", "which Rahul" to "second", "which one (grocery)" to "second",
        )
        for (sheet in sheets) {
            val answer = valid.getValue(sheet.name)
            assertEquals(R.ACT, sheet.hear(Heard(answer, after), emptyList()), "${sheet.name}: \"$answer\"")
            assertEquals(R.ASK, sheet.hear(Heard(answer, 1_000), emptyList()), "${sheet.name}: too soon")
            assertEquals(R.ASK, sheet.hear(Heard("okay so I think $answer", after), emptyList()), "${sheet.name}: too long")
            assertEquals(R.ASK, sheet.hear(Heard("", after), emptyList()), "${sheet.name}: empty")
        }
        assertEquals(ConfirmDialog.Next.CANCEL, ConfirmDialog().also { it.silence() }.silence())
        assertEquals(DraftChoiceDialog.Next.KEEP, DraftChoiceDialog().also { it.silence() }.silence())
        assertEquals(PickDialog.Next.Cancel, PickDialog(options).also { it.silence() }.silence())
        assertEquals(ContactChoiceDialog.Next.Cancel, ContactChoiceDialog(two).also { it.silence() }.silence())
    }

    @Test
    fun `the interlock reports how long after Saathi spoke a transcript came`() {
        var now = 0L
        val v = VoiceInterlock { now }
        assertEquals(null, v.msSinceSpeechEnded())
        v.speechStarted("Which size?")
        assertEquals(0L, v.msSinceSpeechEnded()) // speaking
        now = 3_000; v.speechEnded()
        now = 5_200
        assertEquals(2_200L, v.msSinceSpeechEnded())
    }
}
