package app.saathi.agent

import app.saathi.safety.Action
import app.saathi.safety.Answer
import app.saathi.safety.Apps
import app.saathi.safety.Element
import app.saathi.safety.SafetyGate
import app.saathi.safety.Screen
import app.saathi.safety.Search
import app.saathi.safety.Tap
import app.saathi.safety.TypeText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A guide-mode conversation driven by a script of commands (what the user would say after pressing Speak). */
class ScriptIO(
    commands: List<Heard>,
    private val answer: (ConfirmRequest) -> Answer = { error("no confirmation expected") },
    private val choose: (PickRequest) -> Int? = { error("no question expected") },
) : GuideIO {
    private val queue = ArrayDeque(commands)
    val turns = mutableListOf<GuideTurn>()
    val confirms = mutableListOf<ConfirmRequest>()
    val picks = mutableListOf<PickRequest>()

    /** Times the session waited for the user. It never asks for the microphone itself. */
    var waits = 0

    override suspend fun say(turn: GuideTurn) { turns += turn }
    override suspend fun next(timeoutMs: Long): Heard? { waits++; return queue.removeFirstOrNull() }
    override suspend fun confirm(request: ConfirmRequest): Answer { confirms += request; return answer(request) }
    override suspend fun pick(request: PickRequest): Int? { picks += request; return choose(request) }

    val said: List<String> get() = turns.map { it.text }
}

/** A plain app the gate doesn't know: fixed elements, records taps. */
class StaticApp(val pkg: String, private val els: List<Element>) : Actuator {
    val performed = mutableListOf<Action>()
    private val screen = S.screen(pkg, els)
    override suspend fun snapshot(): Screen = screen
    override suspend fun perform(action: Action, screen: Screen): Boolean { performed += action; return true }
    override suspend fun clearText(element: Element, screen: Screen): Boolean = false
    override suspend fun waitForChange(oldHash: String, timeoutMs: Long): Boolean = false
}

class GuideTest {
    private val gate = SafetyGate()
    private val t = GuideTexts(Lang.EN)
    private val hi = GuideTexts(Lang.HI)
    private val ordersOn = GuidePolicy(grocery = OrderPolicy(allowOrders = true))

    private fun shop(real: Boolean = true) = FakeBlinkit(
        listOf(
            FakeBlinkit.Item("Amul Taaza Toned Fresh Milk", "500 ml", 27, left = "2 left"),
            FakeBlinkit.Item("Mother Dairy Toned Milk", "500 ml", 28, ad = true),
            FakeBlinkit.Item("Brown Bread", "400 g", 45),
        ),
    ).apply {
        realShapes = real
        pkg = Apps.BLINKIT
        view = FakeBlinkit.View.Results("milk bread")
    }

    /** The owner's tablet, 14 Oct 00:40: real Blinkit shapes with all their chrome. */
    private fun device() = FakeBlinkit(
        listOf(
            FakeBlinkit.Item("Amul Taaza Toned Fresh Milk", "500 ml", 27, left = "2 left"),
            FakeBlinkit.Item("Mother Dairy Toned Milk", "500 ml", 28, ad = true),
            FakeBlinkit.Item("Amul Gold Full Cream Milk", "500 ml", 34),
            FakeBlinkit.Item("Amul Moti Toned Milk (90 Days Shelf Life)", "450 ml", 33),
            FakeBlinkit.Item("Amul Buffalo A2 Milk", "500 ml", 40),
            FakeBlinkit.Item("Diet Coke Soft Drink", "300 ml", 40),
            FakeBlinkit.Item("Diet Coke Soft Drink", "1.25 l", 65),
        ),
    ).apply {
        realShapes = true
        deviceChrome = true
        pkg = Apps.BLINKIT
        putInCart("Amul Moti Toned Milk (90 Days Shelf Life)", "450 ml", 1)
        putInCart("Amul Buffalo A2 Milk", "500 ml", 1)
        view = FakeBlinkit.View.Results("milk")
    }

    private fun deviceCheckout() = device().apply {
        outOfStockInCart = "Amul Taaza Toned Milk" to "1 ltr"
        paymentSaved = false
        view = FakeBlinkit.View.Checkout
    }

    private fun said(vararg s: String) = s.map { Heard(it) }

    private class Run(val outcome: Outcome, val io: ScriptIO)

    private fun guide(
        actuator: Actuator,
        commands: List<Heard>,
        target: String? = Apps.BLINKIT,
        policy: GuidePolicy = ordersOn,
        intro: String? = null,
        open: String? = null,
        lang: Lang = Lang.EN,
        answer: (ConfirmRequest) -> Answer = { error("no confirmation expected") },
        pick: (PickRequest) -> Int? = { error("no question expected") },
    ): Run = runBlocking {
        val io = ScriptIO(commands, answer, pick)
        val out = GuideSession(GatedDevice(gate, actuator), lang, io, policy, target).run(intro, open)
        Run(out, io)
    }

    private fun texts(s: Screen) = s.elements.flatMap { listOf(it.text, it.desc) }.filter { it.isNotEmpty() }

    private fun summary(a: Actuator, app: String = "Blinkit", tx: GuideTexts = t): Spoken = runBlocking {
        val s = a.snapshot()!!
        GuideView.summary(GuideView.of(s, app, tx), app, tx)
    }

    /** Nothing a friend wouldn't say: no controls, no glyphs, no address, nothing said twice. */
    private fun assertFriendly(text: String) {
        for (chrome in listOf("Navigate up", "Share", "Clear all", "Double tap", "Voice search", "wishlist", "4.3", "12.4k", "10 MINS", "Ad,", "Change", "Delivering")) {
            assertFalse(chrome in text, "\"$chrome\" in: $text")
        }
        assertFalse(text.any { it.code in 0xE000..0xF8FF || it == '펩' }, text)
        assertFalse("Example Road" in text || "411001" in text || "address" in text, text)
        assertFalse(": ." in text || ", ," in text, text)
        assertFalse(Regex("(?i)read more").containsMatchIn(text), "spoken text must not say 'read more': $text")
    }

    // -- DESCRIBE: content first, like a friend ----------------------------------------------------

    @Test
    fun `real checkout (14 Oct 00-40) - out-of-stock item named, cart with sizes and prices, pay yourself, one question`() {
        val fake = deviceCheckout()
        val s = summary(fake)
        assertEquals(
            "You're at checkout. One thing's out of stock: Amul Taaza Toned Milk, 1 ltr. Blinkit is suggesting other milks. " +
                "In your cart: Amul Moti Toned Milk (90 Days Shelf Life), 450 ml, ₹33, and Amul Buffalo A2 Milk, 500 ml, ₹40. " +
                "To pay, you'll need to choose a payment method yourself. Want me to remove something or add more?",
            s.text,
        )
        assertFriendly(s.text)
        // Every name and price said is verbatim on the screen (hard rule 4).
        val screen = runBlocking { fake.snapshot() }
        for (part in listOf("Amul Taaza Toned Milk", "1 ltr", "Amul Moti Toned Milk (90 Days Shelf Life)", "450 ml", "₹33", "Amul Buffalo A2 Milk", "₹40")) {
            assertTrue(texts(screen).any { part in it }, part)
        }
    }

    @Test
    fun `real checkout - the old guide said 'not in stock - dot', the item is now found inside the clickable row`() {
        val v = GuideView.of(runBlocking { deviceCheckout().snapshot() }, "Blinkit", t)
        assertEquals(listOf("Amul Taaza Toned Milk, 1 ltr"), v.outOfStockItems)
        assertEquals(ScreenKind.CHECKOUT, v.kind)
        assertEquals("Select Payment Method", v.payment)
        assertEquals(listOf("Mother Dairy Toned Milk"), v.suggested.map { it.name })
    }

    @Test
    fun `real search results (14 Oct 00-40) - top 3 cards, and N more, cart count once, a question with suggestions`() {
        val s = summary(device())
        assertEquals(
            "Here's what I found for \"milk\": Amul Taaza Toned Fresh Milk, 500 ml, ₹27, 2 left; Mother Dairy Toned Milk, 500 ml, ₹28; " +
                "and Amul Gold Full Cream Milk, 500 ml, ₹34. And 2 more. Your cart has 2 items. Want me to add one? " +
                "You could say \"add Amul Taaza\" or \"open the cart\".",
            s.text,
        )
        assertFriendly(s.text)
        assertEquals(t.moreHint, s.hint) // "read more" is shown, never spoken
        assertEquals(1, Regex("2 items").findAll(s.text).count()) // not "2 items, 2 items"
    }

    @Test
    fun `read more pages through the next cards, the read-more hint is on screen, never in the spoken text`() {
        val r = guide(device(), said("read more", "read more", "done"))
        assertEquals(t.moreHint, r.io.turns[0].hint)
        assertEquals(
            "Also here: Amul Moti Toned Milk (90 Days Shelf Life), 450 ml, ₹33, 1 in your cart; and Amul Buffalo A2 Milk, 500 ml, ₹40, 1 in your cart. Want me to add one?",
            r.io.said[1],
        )
        assertEquals("${t.noMore} ${t.whatNext}", r.io.said[2])
        for (line in r.io.said) assertFalse(Regex("(?i)read more").containsMatchIn(line), line)
    }

    @Test
    fun `read everything - every line, controls included (cleaned of glyphs), on request only`() {
        val fake = deviceCheckout()
        val r = guide(fake, said("what buttons are there", "read more", "read more", "read more", "done"))
        val all = r.io.said.drop(1).joinToString(" ")
        assertTrue(r.io.said[1].startsWith("Blinkit: Checkout. I can see: Navigate up; Search; Share;"), r.io.said[1])
        assertFalse("펩" in all, all)
        assertFalse(fake.address in all, all)
        assertTrue("Change" in all && "your saved address" in all, all)
        assertTrue("Amul Moti Toned Milk (90 Days Shelf Life), 450 ml, ₹33, quantity 1, Move to wishlist" in all, all)
    }

    @Test
    fun `blinkit home - cart count, content, what to buy`() {
        val fake = shop().apply { putInCart("Brown Bread", "400 g", 2); view = FakeBlinkit.View.Home }
        val s = summary(fake)
        assertEquals("You're in Blinkit. Your cart has 2 items. I can see Shop by category. What would you like to buy? You could say \"open the cart\".", s.text)
    }

    @Test
    fun `whatsapp chat list - who has unread messages, then a question`() {
        val wa = FakeWhatsApp(
            linkedMapOf("Mummy" to mutableListOf(Msg("them", "Khana kha liya?")), "Rahul" to mutableListOf(), "Office" to mutableListOf()),
            mutableMapOf("Mummy" to 3, "Rahul" to 2),
        ).apply { pkg = WhatsApp.PKG }
        val s = summary(wa, "WhatsApp")
        assertEquals(
            "You're in your WhatsApp chats. 2 chats have unread messages: Mummy, 3 unread messages, and Rahul, 2 unread messages. " +
                "Want me to open one? You could say \"open Mummy\".",
            s.text,
        )
        val none = summary(FakeWhatsApp(linkedMapOf("Mummy" to mutableListOf(), "Rahul" to mutableListOf())).apply { pkg = WhatsApp.PKG }, "WhatsApp")
        assertEquals("You're in your WhatsApp chats. No unread messages. Your chats include Mummy and Rahul. Want me to open one? You could say \"open Mummy\".", none.text)
    }

    @Test
    fun `whatsapp chat - who and how many, the message itself only when asked`() {
        val wa = FakeWhatsApp(linkedMapOf("Mummy" to mutableListOf(Msg("them", "Khana kha liya?")))).apply { pkg = WhatsApp.PKG; view = FakeWhatsApp.View.Chat("Mummy") }
        val s = summary(wa, "WhatsApp")
        assertEquals("You're in your chat with Mummy. There's 1 message on screen. Want me to read the newest message, or type a reply? You could say \"read messages\".", s.text)
        assertFalse("Khana kha liya?" in s.text)
        val r = guide(wa, said("read messages", "done"), target = WhatsApp.PKG)
        assertEquals("The newest message, from Mummy, says: Khana kha liya? What next?", r.io.said[1])
    }

    @Test
    fun `a dialog - the question and its choices`() {
        val app = StaticApp(
            Apps.BLINKIT,
            listOf(
                S.el(0, text = "Remove item?", rid = "alertTitle"),
                S.el(1, text = "Amul Buffalo A2 Milk will be removed from your cart", rid = "message"),
                S.el(2, text = "Remove", rid = "button1", cls = "Button", clickable = true),
                S.el(3, text = "Cancel", rid = "button2", cls = "Button", clickable = true),
            ),
        )
        assertEquals(
            "Blinkit is asking: Remove item? Amul Buffalo A2 Milk will be removed from your cart. The choices are Remove and Cancel. Which one? " +
                "You could say \"tap Remove\" or \"tap Cancel\".",
            summary(app).text,
        )
    }

    @Test
    fun `another app - title and up to 4 things, controls left out`() {
        val app = StaticApp(
            "com.android.settings",
            listOf(
                S.el(0, desc = "Navigate up", cls = "ImageButton", clickable = true),
                S.el(1, text = "Network & internet", rid = "title"),
                S.el(2, desc = "Search settings", clickable = true),
                S.el(3, text = "Wi-Fi", clickable = true),
                S.el(4, text = "Mobile network", clickable = true),
                S.el(5, text = "Hotspot & tethering", clickable = true),
                S.el(6, text = "Data Saver", clickable = true),
                S.el(7, text = "VPN", clickable = true),
            ),
        )
        val s = summary(app, "Settings")
        assertEquals(
            "You're in Settings: Network & internet. I can see Wi-Fi, Mobile network, Hotspot & tethering, and Data Saver. And 1 more. What next? You could say \"tap Wi-Fi\".",
            s.text,
        )
        assertEquals(t.moreHint, s.hint)
    }

    @Test
    fun `Hindi - short Devanagari lines with the verbatim names`() {
        assertEquals(
            "आप चेकआउट पर हैं। एक चीज़ स्टॉक में नहीं है: Amul Taaza Toned Milk, 1 ltr। Blinkit बदले में और milk सुझा रहा है। " +
                "कार्ट में: Amul Moti Toned Milk (90 Days Shelf Life), 450 ml, ₹33 और Amul Buffalo A2 Milk, 500 ml, ₹40। " +
                "भुगतान का तरीका आपको खुद चुनना होगा। कुछ हटाऊँ या और डालूँ?",
            summary(deviceCheckout(), tx = hi).text,
        )
        assertEquals(
            "\"milk\" के लिए यह मिला: Amul Taaza Toned Fresh Milk, 500 ml, ₹27, 2 left; Mother Dairy Toned Milk, 500 ml, ₹28; और Amul Gold Full Cream Milk, 500 ml, ₹34। " +
                "और 2 चीज़ें हैं। आपके कार्ट में 2 items हैं। कोई डालूँ? आप बोल सकते हैं \"Amul Taaza डालो\" या \"कार्ट खोलो\"।",
            summary(device(), tx = hi).text,
        )
        // The suggested Hindi commands are understood.
        assertEquals(GuideCommand.Add("amul taaza", 1), GuideGrammar.parse("Amul Taaza डालो"))
        assertEquals(GuideCommand.OpenCart, GuideGrammar.parse("कार्ट खोलो"))
        assertEquals(GuideCommand.NewestMessage, GuideGrammar.parse("मैसेज पढ़ो"))
    }

    @Test
    fun `Saathi's guide lines never contain a yes word or a position word (echo-safe for the sheets after them)`() {
        for (tx in listOf(t, hi)) {
            val lines = listOf(
                tx.start, tx.whatNext, tx.pressSpeak, tx.andMore(3), tx.moreHint, tx.loading, tx.wantAdd, tx.whatToBuy, tx.atCheckout,
                tx.oosOne("X"), tx.oosMany(2, "X and Y"), tx.oosUnnamed, tx.suggesting("Blinkit", "milk"), tx.suggesting("Blinkit", null),
                tx.inYourCart(listOf("A", "B"), 1), tx.payYourself, tx.placeWhenReady, tx.checkoutAsk, tx.inChats, tx.unreadChats(2, "A"),
                tx.noUnread, tx.wantOpen, tx.chatWith("X"), tx.messagesOnScreen(2), tx.chatAsk, tx.whichOne, tx.donePrefix,
                tx.nowInCart("X", 1, "quantity 1"), tx.gone("X"), tx.qtyNow("X", "quantity 2"), tx.cartNow("1 item", listOf("A"), 0),
                tx.nothingChanged, tx.foundAsk("q", 1), tx.foundAsk("q", 2), tx.didYouMeanPick("q"), tx.searchedNoMatch("q", listOf("A")),
                tx.heard("q"), tx.guessAdd("X"), tx.guessSearchAdd("X"), tx.guessMean("X"), tx.guessCart, tx.notSureMeant, tx.ifSoSay("add X"),
                tx.pickNothingAdded, tx.totalAtCheckout, tx.wantOpenCart, tx.cheapest("A"), tx.priceIs("A, 1 l", "₹2"), tx.available("A", "₹2"),
                tx.outOfStockNow("X"), tx.couldSay(listOf(tx.sayAdd("X"), tx.sayOpenCart))!!, tx.cartNowCount("2 items"), tx.cartEmptyNow,
            )
            for (l in lines) {
                assertFalse(Ordinals.containsOrdinal(l), l)
                assertFalse(EchoGuard.tokens(l).any { it in ConfirmDialog.YES_WORDS }, l)
            }
        }
    }

    @Test
    fun `cleaning - icon glyphs dropped, repeated parts said once, Devanagari kept`() {
        assertEquals("Share", GuideView.clean("펩 Share"))
        assertEquals("Share", GuideView.clean(" Share"))
        assertEquals("2 items", GuideView.clean("2 items, 2 items"))
        assertEquals("दूध", GuideView.clean("दूध"))
        assertEquals("Coca-Cola Diet Coke", GuideView.clean("Coca-Cola Diet Coke"))
        assertEquals("펩시", GuideView.clean("펩시")) // a label in another script is kept
    }

    @Test
    fun `shimmer placeholders are loading - the guide waits for the cards, never reads placeholders`() {
        val fake = shop().apply { shimmerSnapshots = 2 }
        val loadingScreen = runBlocking { fake.snapshot() }
        assertTrue(GuideView.loading(loadingScreen))
        fake.shimmerSnapshots = 2
        val r = guide(fake, said("done"))
        assertTrue("Amul Taaza Toned Fresh Milk" in r.io.said.first(), r.io.said.first())
    }

    @Test
    fun `password fields and codes are never read`() {
        val app = StaticApp(
            "com.example.bank",
            listOf(
                S.el(0, text = "Welcome back", y = 100),
                S.el(1, text = "", desc = "Password", cls = "EditText", editable = true, password = true, y = 200),
                S.el(2, text = "Your code is 482913", y = 300),
                S.el(3, text = "Continue", cls = "Button", clickable = true, y = 400),
            ),
        )
        val v = GuideView.of(runBlocking { app.snapshot() }, "Bank", t)
        val all = GuideView.everything(v, "Bank", t).text + " " + GuideView.summary(v, "Bank", t).text
        assertFalse("482913" in all, all)
        assertTrue("a password field" in all, all)
        assertTrue("a code or number" in all, all)
    }

    // -- UNDERSTAND ------------------------------------------------------------------------------------

    @Test
    fun `grammar - English, Hindi and Hinglish`() {
        val cases = mapOf(
            "tap View cart" to GuideCommand.Tap("View cart"),
            "press the Move to wishlist button" to GuideCommand.Tap("Move to wishlist"),
            "Brown Bread par tap karo" to GuideCommand.Tap("Brown Bread"),
            "Move to wishlist दबाओ" to GuideCommand.Tap("Move to wishlist"),
            "add Amul Taaza" to GuideCommand.Add("amul taaza", 1),
            "add 2 brown bread" to GuideCommand.Add("brown bread", 2),
            "brown bread add karo" to GuideCommand.Add("brown bread", 1),
            "अमूल डालो" to GuideCommand.Add("अमूल", 1),
            "remove brown bread" to GuideCommand.Remove("brown bread"),
            "brown bread kam karo" to GuideCommand.Remove("brown bread"),
            "plus amul" to GuideCommand.Increase("amul"),
            "open cart" to GuideCommand.OpenCart,
            "कार्ट खोलो" to GuideCommand.OpenCart,
            "checkout" to GuideCommand.Checkout,
            "search for atta" to GuideCommand.Search("atta"),
            "atta khojo" to GuideCommand.Search("atta"),
            "type Hello Mummy" to GuideCommand.Type("Hello Mummy", null),
            "type hello into Message" to GuideCommand.Type("hello", "Message"),
            "scroll down" to GuideCommand.Scroll(true),
            "aage" to GuideCommand.Scroll(true),
            "peeche" to GuideCommand.Scroll(false),
            "go back" to GuideCommand.Back,
            "wapas jao" to GuideCommand.Back,
            "repeat" to GuideCommand.Repeat,
            "phir se bolo" to GuideCommand.Repeat,
            "read more" to GuideCommand.More,
            "aur batao" to GuideCommand.More,
            "what's on screen" to GuideCommand.WhatsOnScreen,
            "what's in my cart" to GuideCommand.Cart,
            "cart mein kya hai" to GuideCommand.Cart,
            "done" to GuideCommand.Done,
            "bas" to GuideCommand.Done,
            "हो गया" to GuideCommand.Done,
            "keep it" to GuideCommand.Keep,
            "read everything" to GuideCommand.Everything,
            "what buttons are there" to GuideCommand.Everything,
        )
        for ((text, want) in cases) assertEquals(want, GuideGrammar.parse(text), text)
    }

    @Test
    fun `natural, polite and messy speech (14 Oct 00-42, the owner's own words) - EN, HI, Hinglish`() {
        val diet = GuideCommand.Add("diet coke", 1)
        val cases = mapOf(
            // The owner's tries on the tablet.
            "can you order diet Coke in my card on" to diet,
            // Polite / filler variants, cart misheard as card / cut / caught / kart / khat.
            "please add diet coke to my cart" to diet,
            "could you add diet coke to my cut please" to diet,
            "can you put diet coke in the basket" to diet,
            "I want to add diet coke to my kart" to diet,
            "add diet coke in my caught" to diet,
            "will you buy diet coke for me" to diet,
            "get me diet coke" to diet,
            "could you put 2 diet coke in my card" to GuideCommand.Add("diet coke", 2),
            "diet coke mere cart mein daal do" to diet,
            "diet coke mere khat mein daal do na" to diet,
            "cart mein diet coke daal do" to diet,
            "zara diet coke mangao" to diet,
            "kya tum diet coke add kar sakte ho" to diet,
            "डाइट कोक कार्ट में डाल दो" to GuideCommand.Add("डाइट कोक", 1),
            "I'd like diet coke" to GuideCommand.Want("diet coke"),
            "mujhe diet coke chahiye" to GuideCommand.Want("diet coke"),
            "मुझे डाइट कोक चाहिए" to GuideCommand.Want("डाइट कोक"),
            "mujhe cart dikhao" to GuideCommand.OpenCart,
            "I want to go back" to GuideCommand.Back,
            // Remove.
            "remove diet coke" to GuideCommand.Remove("diet coke"),
            "diet coke hatao" to GuideCommand.Remove("diet coke"),
            "minus diet coke" to GuideCommand.Remove("diet coke"),
            "diet coke nikal do" to GuideCommand.Remove("diet coke"),
            "please take out diet coke from my cart" to GuideCommand.Remove("diet coke"),
            "diet coke ko cart se hatao" to GuideCommand.Remove("diet coke"),
            // Questions.
            "how much is diet coke" to GuideCommand.Price("diet coke"),
            "diet coke kitne ka hai" to GuideCommand.Price("diet coke"),
            "डाइट कोक कितने का है" to GuideCommand.Price("डाइट कोक"),
            "what's in my card" to GuideCommand.Cart,
            "how much is the total" to GuideCommand.Total,
            "total kitna hua" to GuideCommand.Total,
            "is diet coke available" to GuideCommand.Available("diet coke"),
            "diet coke milega" to GuideCommand.Available("diet coke"),
            "diet coke hai kya" to GuideCommand.Available("diet coke"),
            "show me cheaper" to GuideCommand.Cheaper,
            "kuch sasta dikhao" to GuideCommand.Cheaper,
            "सस्ता दिखाओ" to GuideCommand.Cheaper,
            "next" to GuideCommand.More,
            "aur dikhao" to GuideCommand.More,
            "और दिखाओ" to GuideCommand.More,
            "place order" to GuideCommand.Checkout,
        )
        for ((text, want) in cases) assertEquals(want, GuideGrammar.parse(text), text)
        // Not understood: reflected back by the session, never acted on.
        assertEquals(GuideCommand.Tap("are diet Google my card", explicit = false), GuideGrammar.parse("are diet Google my card"))
    }

    @Test
    fun `sound-alike slips are matched only to ask - 'diet Google' is Diet Coke, in the name's own words`() {
        assertEquals("Diet Coke" to "Coca-Cola Diet Coke Soft Drink", GuideGuess.match(listOf("diet", "google"), listOf("Amul Gold Full Cream Milk", "Coca-Cola Diet Coke Soft Drink")))
        assertEquals(listOf("diet", "google"), GuideGuess.productWords("are diet Google my card"))
        assertTrue(GuideGuess.mentionsCart("are diet Google my card"))
        assertNull(GuideGuess.match(listOf("bananas"), listOf("Amul Gold Full Cream Milk")))
        assertNull(GuideGuess.match(listOf("google"), listOf("Coke Zero")), "a sound-alike alone (no word heard right) is not enough")
    }

    @Test
    fun `a bare yes, ok or send is never an action (only the Confirm sheet takes a yes)`() {
        for (w in listOf("yes", "haan", "ok", "send it", "yes send", "हाँ", "theek hai", "please yes")) {
            assertTrue(GuideGrammar.parse(w) is GuideCommand.Unknown, w)
        }
    }

    @Test
    fun `entry commands - parsed as guide mode, with the app when named`() {
        assertEquals(Command.Guide("blinkit", "let's do it together in Blinkit"), CommandParser.parse("let's do it together in Blinkit"))
        assertEquals(null, (CommandParser.parse("step by step") as Command.Guide).app)
        assertEquals(null, (CommandParser.parse("guide me") as Command.Guide).app)
        assertEquals(null, (CommandParser.parse("saath mein karte hain") as Command.Guide).app)
        assertEquals(null, (CommandParser.parse("ek ek karke") as Command.Guide).app)
        assertEquals(null, (CommandParser.parse("मिलकर करते हैं") as Command.Guide).app)
        assertEquals("whatsapp", (CommandParser.parse("WhatsApp mein saath mein karte hain") as Command.Guide).app)
        assertEquals("Instagram", (CommandParser.parse("guide me in Instagram") as Command.Guide).app)
    }

    @Test
    fun `fuzzy resolution - spoken variants find the verbatim label`() {
        val s = runBlocking { shop().snapshot() }
        val targets = GuideTargets.all(GuideView.of(s, "Blinkit", t))
        val one = GuideTargets.resolve("brown bred", targets) as Resolved.One
        assertEquals("Brown Bread", one.target.label)
        assertTrue(GuideTargets.resolve("milk", targets) is Resolved.Many)
        assertEquals(Resolved.None, GuideTargets.resolve("bananas", targets))
    }

    // -- the owner's two tries, end to end ------------------------------------------------------------

    @Test
    fun `'can you order diet Coke in my card on' - searched, matches described, the user picks, then added`() {
        val fake = device()
        val r = guide(fake, said("can you order diet Coke in my card on", "done"), pick = { req ->
            assertEquals("For \"diet coke\" I found 2 matches. Which one should I add?", req.question)
            assertEquals(listOf("Diet Coke Soft Drink 300 ml, ₹40", "Diet Coke Soft Drink 1.25 l, ₹65"), req.options)
            0
        })
        assertTrue(Search("diet coke") in fake.performed, "${fake.performed}")
        assertEquals(1, fake.cartQty("Diet Coke Soft Drink"))
        assertEquals(listOf("Diet Coke Soft Drink 300 ml"), fake.addTaps)
        assertEquals("Done — Diet Coke Soft Drink is in your cart. Your cart now has 3 items. What next?", r.io.said[1])
    }

    @Test
    fun `after a search, no pick = nothing added (never added on a guess)`() {
        val fake = device()
        val r = guide(fake, said("add diet coke to my cart", "done"), pick = { null })
        assertEquals(0, fake.cartQty("Diet Coke Soft Drink"))
        assertTrue(fake.addTaps.isEmpty())
        assertEquals("${t.pickNothingAdded} ${t.whatNext}", r.io.said[1])
    }

    @Test
    fun `'are diet Google my card' - reflected back with the likely meaning from the screen, nothing done`() {
        val fake = device()
        val r = guide(fake, said("search for diet coke", "are diet Google my card", "go back", "are diet Google my card", "done"))
        // Diet Coke on screen: offered, with the exact command to say.
        assertEquals("I heard \"are diet Google my card\". Did you want me to add Diet Coke to your cart? If so, say \"add Diet Coke\".", r.io.said[2])
        // Seen earlier, not on screen now: "search for … and add it".
        assertEquals(
            "I heard \"are diet Google my card\". Did you want me to search for Diet Coke and add it to your cart? If so, say \"add Diet Coke\".",
            r.io.said[4],
        )
        assertTrue(fake.addTaps.isEmpty())
        assertTrue(r.io.picks.isEmpty())
        assertEquals(2, r.io.turns.count { it.kind == GuideTurn.Kind.REFUSED })
    }

    @Test
    fun `not understood with no likely meaning - says what was heard and 1-2 things from the screen, private words never said back`() {
        val r = guide(device(), said("blah blah", "my otp is 482913", "done"))
        assertEquals("I heard \"blah blah\". I'm not sure what you meant. You could say \"add Amul Taaza\" or \"open the cart\".", r.io.said[1])
        assertFalse("482913" in r.io.said[2], r.io.said[2])
    }

    @Test
    fun `a slip of an item on screen - only offered on the which-one sheet (cancel = nothing)`() {
        val fake = device().apply { view = FakeBlinkit.View.Results("diet coke") }
        val r = guide(fake, said("add diet google", "done"), pick = { req ->
            assertEquals(t.didYouMeanPick("diet google"), req.question)
            null
        })
        assertTrue(fake.addTaps.isEmpty())
        assertEquals(1, r.io.picks.size)
    }

    @Test
    fun `questions - price, available, cheaper, total, cart`() {
        val r = guide(device(), said("how much is amul gold", "is amul taaza available", "show me cheaper", "how much is the total", "what's in my cart", "done"))
        assertEquals("Amul Gold Full Cream Milk, 500 ml, is ₹34. What next?", r.io.said[1])
        assertEquals("Amul Taaza Toned Fresh Milk, 500 ml, is available for ₹27. What next?", r.io.said[2])
        assertEquals(
            "The cheapest here: Amul Taaza Toned Fresh Milk, 500 ml, ₹27, 2 left; Mother Dairy Toned Milk, 500 ml, ₹28; and Amul Moti Toned Milk (90 Days Shelf Life), 450 ml, ₹33, 1 in your cart. Want me to add one?",
            r.io.said[3],
        )
        assertEquals("The total shows at checkout. You could say \"open the cart\".", r.io.said[4])
        assertEquals("Your cart has 2 items. Want me to open it?", r.io.said[5])
    }

    // -- ACT -------------------------------------------------------------------------------------------

    @Test
    fun `after an action - what changed first ('Done - Amul Buffalo A2 Milk is gone'), then the cart`() {
        val fake = deviceCheckout()
        val r = guide(fake, said("remove buffalo a2", "done"))
        assertEquals(0, fake.cartQty("Amul Buffalo A2 Milk"))
        assertEquals(
            "Done — Amul Buffalo A2 Milk is gone. Your cart now has 1 item: Amul Moti Toned Milk (90 Days Shelf Life), 450 ml, ₹33. What next?",
            r.io.said[1],
        )
        assertFriendly(r.io.said[1])
    }

    @Test
    fun `add - two candidates, asks which one (cancel default), adds the one picked, says what changed`() {
        val fake = shop()
        val r = guide(fake, said("add milk", "done"), pick = { req ->
            assertEquals(listOf("Amul Taaza Toned Fresh Milk 500 ml, ₹27", "Mother Dairy Toned Milk 500 ml, ₹28"), req.options)
            assertFalse(Ordinals.containsOrdinal(req.question))
            1
        })
        assertEquals(mapOf("Mother Dairy Toned Milk 500 ml" to 1), fake.cartItems)
        assertEquals("Done — Mother Dairy Toned Milk is in your cart. Your cart now has 1 item. What next?", r.io.said[1])
        val out = r.outcome as Outcome.Answered
        assertTrue(out.message.endsWith("Your cart has 1 item; nothing was ordered."), out.message)
    }

    @Test
    fun `which one - no answer cancels, nothing added`() {
        val fake = shop()
        val r = guide(fake, said("add milk", "done"), pick = { null })
        assertTrue(fake.cartItems.isEmpty())
        assertTrue(r.io.said[1].startsWith(t.pickCancelled), r.io.said[1])
    }

    @Test
    fun `can't see it - says so and what it can see (content, not controls)`() {
        val r = guide(shop(), said("tap bananas", "done"))
        assertEquals(
            "I can't see \"bananas\" here. I can see Amul Taaza Toned Fresh Milk, Mother Dairy Toned Milk, and Brown Bread. You could say \"search for bananas\".",
            r.io.said[1],
        )
    }

    @Test
    fun `an item already in the cart - asks, never adds on top`() {
        val fake = shop().apply { putInCart("Amul Taaza Toned Fresh Milk", "500 ml", 1) }
        val r = guide(fake, said("add amul taaza", "done"))
        assertEquals(mapOf("Amul Taaza Toned Fresh Milk 500 ml" to 1), fake.cartItems)
        assertTrue(fake.addTaps.isEmpty())
        assertTrue(r.io.said[1].startsWith("Amul Taaza Toned Fresh Milk 500 ml is already in your cart: quantity 1. To add one more, say \"plus"), r.io.said[1])
    }

    @Test
    fun `add 2 - ADD then plus once, never more (the gate's cart guard)`() {
        val fake = shop()
        guide(fake, said("add 2 brown bread", "done"))
        assertEquals(mapOf("Brown Bread 400 g" to 2), fake.cartItems)
        assertEquals(2, fake.addTaps.size)
    }

    @Test
    fun `minus and plus act on the item's own stepper`() {
        val fake = shop().apply { putInCart("Brown Bread", "400 g", 2) }
        val r = guide(fake, said("minus brown bread", "plus brown bread", "plus it", "done"))
        assertEquals(mapOf("Brown Bread 400 g" to 3), fake.cartItems)
        assertEquals("Done — Brown Bread is now at quantity 1. Your cart now has 1 item. What next?", r.io.said[1])
    }

    @Test
    fun `open cart, then checkout's Select Payment Method - handed over, never pressed`() {
        val fake = shop().apply { putInCart("Brown Bread", "400 g", 1); paymentSaved = false }
        val r = guide(fake, said("open cart", "tap select payment method"))
        assertEquals("You're at checkout. In your cart: Brown Bread, 400 g, ₹45. To pay, you'll need to choose a payment method yourself. Want me to remove something or add more?", r.io.said[1])
        assertTrue(r.outcome is Outcome.HandedOver && r.outcome.message.startsWith(t.choosePayment), r.outcome.message)
        assertTrue(fake.view == FakeBlinkit.View.Checkout)
        assertTrue(fake.orders.isEmpty())
    }

    @Test
    fun `order button - the gate HOLDs it, the Confirm sheet gets the verbatim cart, No = nothing ordered`() {
        val fake = shop(real = false).apply { putInCart("Brown Bread", "400 g", 1); view = FakeBlinkit.View.Checkout }
        val r = guide(fake, said("tap place order", "done"), answer = { Answer.NO })
        val req = r.io.confirms.single()
        assertEquals("R3", req.rule)
        assertEquals(listOf(CartLine("Brown Bread", "400 g", 1, "₹45")), req.order!!.lines)
        assertTrue(fake.orders.isEmpty())
        assertTrue(r.io.said[1].startsWith("Cancelled. I didn't press it. Nothing was ordered."), r.io.said[1])
    }

    @Test
    fun `order button - a clear yes (and Settings allow it) places the order`() {
        val fake = shop(real = false).apply { putInCart("Brown Bread", "400 g", 1); view = FakeBlinkit.View.Checkout }
        val r = guide(fake, said("tap place order", "done"), answer = { Answer.YES })
        assertEquals(1, fake.orders.size)
        assertTrue((r.outcome as Outcome.Answered).message.endsWith(t.orderPlaced), r.outcome.message)
    }

    @Test
    fun `'place order' said plainly - still only through the gated Confirm sheet`() {
        val fake = shop(real = false).apply { putInCart("Brown Bread", "400 g", 1); view = FakeBlinkit.View.Checkout }
        val r = guide(fake, said("please place the order", "done"), answer = { Answer.NO })
        assertEquals("R3", r.io.confirms.single().rule)
        assertTrue(fake.orders.isEmpty())
    }

    @Test
    fun `order button with ordering off in Settings - refused, no Confirm sheet, nothing ordered`() {
        val fake = shop(real = false).apply { putInCart("Brown Bread", "400 g", 1); view = FakeBlinkit.View.Checkout }
        val r = guide(fake, said("tap place order", "done"), policy = GuidePolicy())
        assertTrue(r.io.confirms.isEmpty())
        assertTrue(fake.orders.isEmpty())
        assertTrue(r.io.said[1].startsWith(OrderTexts(Lang.EN).orderingOff), r.io.said[1])
    }

    @Test
    fun `grocery read only - never adds`() {
        val fake = shop()
        val r = guide(fake, said("add brown bread", "done"), policy = GuidePolicy(grocery = OrderPolicy(level = GroceryLevel.READ_ONLY)))
        assertTrue(fake.cartItems.isEmpty())
        assertTrue(r.io.said[1].startsWith(t.groceryReadOnly), r.io.said[1])
    }

    @Test
    fun `whatsapp - type then send - HOLD with the verbatim read-back, No = nothing sent, text left in the box`() {
        val wa = FakeWhatsApp(linkedMapOf("Mummy" to mutableListOf(Msg("them", "Kahan ho?")))).apply { pkg = WhatsApp.PKG; view = FakeWhatsApp.View.Chat("Mummy") }
        val r = guide(wa, said("type Ghar aa raha hoon", "tap send", "done"), target = WhatsApp.PKG, answer = { req ->
            assertEquals("R2", req.rule)
            assertEquals("Mummy", req.recipient)
            assertEquals("Ghar aa raha hoon", req.message)
            Answer.NO
        })
        assertTrue(wa.sent.isEmpty())
        assertTrue(TypeText("Ghar aa raha hoon", 3) in wa.performed || wa.performed.any { it is TypeText && it.text == "Ghar aa raha hoon" })
        assertTrue("still in the box" in r.io.said[2], r.io.said[2])
        assertTrue((r.outcome as Outcome.Answered).message.endsWith(t.messageLeft), r.outcome.message)
    }

    @Test
    fun `never types a code or password`() {
        val wa = FakeWhatsApp(linkedMapOf("Mummy" to mutableListOf())).apply { pkg = WhatsApp.PKG; view = FakeWhatsApp.View.Chat("Mummy") }
        val r = guide(wa, said("type 482913", "done"), target = WhatsApp.PKG)
        assertTrue(wa.performed.none { it is TypeText })
        assertTrue(r.io.said[1].startsWith(t.noSecrets), r.io.said[1])
    }

    @Test
    fun `PIN screen - handed over at once, nothing done`() {
        val wa = FakeWhatsApp().apply { pkg = WhatsApp.PKG; pinScreen = true }
        val r = guide(wa, said("tap continue"), target = WhatsApp.PKG)
        assertTrue(r.outcome is Outcome.HandedOver && "PIN" in r.outcome.message, r.outcome.message)
        assertTrue(wa.performed.isEmpty())
        assertTrue(r.io.turns.isEmpty())
    }

    @Test
    fun `an app the gate doesn't know - buttons that buy or send are never pressed`() {
        val app = StaticApp("com.example.shop", listOf(S.el(0, text = "Buy now", cls = "Button", clickable = true), S.el(1, text = "Details", cls = "Button", clickable = true)))
        val r = guide(app, said("tap buy now", "tap details", "done"), target = "com.example.shop")
        assertEquals(listOf<Action>(Tap(1)), app.performed)
        assertTrue(r.io.said[1].startsWith(t.risky), r.io.said[1])
    }

    @Test
    fun `search in Blinkit - the gated search link, then the results summarised`() {
        val fake = shop()
        val r = guide(fake, said("search for brown bread", "done"))
        assertTrue(Search("brown bread") in fake.performed, "${fake.performed}")
        assertTrue(r.io.said[1].startsWith("Here's what I found for \"brown bread\": Brown Bread, 400 g, ₹45."), r.io.said[1])
    }

    @Test
    fun `opening the named app first, through the gate`() {
        val fake = shop().apply { pkg = "app.saathi"; view = FakeBlinkit.View.Home }
        val r = guide(fake, said("done"), target = null, open = "blinkit")
        assertTrue(r.io.said.first().startsWith(t.start), r.io.said.first())
        assertTrue(fake.performed.first() is app.saathi.safety.OpenApp)
    }

    // -- LISTEN (echo / push-to-talk) ------------------------------------------------------------------

    @Test
    fun `echo timing - a command heard under 1,5 s after Saathi spoke is not acted on`() {
        val fake = shop()
        val r = guide(fake, listOf(Heard("add brown bread", 400), Heard("add brown bread", 3_000), Heard("done")))
        assertEquals(t.tooSoon, r.io.said[1])
        assertEquals(mapOf("Brown Bread 400 g" to 1), fake.cartItems)
    }

    @Test
    fun `a long transcript repeating Saathi's description is an echo, not a command`() {
        val fake = shop()
        val first = summary(fake).text
        val echo = first.split(" ").drop(4).take(9).joinToString(" ")
        val r = guide(fake, listOf(Heard(echo, 5_000), Heard("done")))
        assertEquals(t.echo, r.io.said[1])
        assertTrue(fake.cartItems.isEmpty())
    }

    @Test
    fun `Saathi's own suggestion heard right after it spoke is asked again once, then acted on`() {
        val fake = device()
        val r = guide(fake, listOf(Heard("add Amul Taaza", 2_000), Heard("add Amul Taaza", 2_000), Heard("done")))
        assertEquals(t.echo, r.io.said[1])
        assertEquals(1, fake.cartQty("Amul Taaza Toned Fresh Milk")) // the repeat (after the "say it again") is the user's
    }

    @Test
    fun `no auto-listen - Saathi waits once per turn for the user's press, never starts listening itself`() {
        val r = guide(shop(), said("read more", "done"))
        assertEquals(r.io.turns.size, r.io.waits) // each turn: one wait for the user, nothing else
        assertTrue(r.io.said.first().endsWith(t.pressSpeak), r.io.said.first())
        assertTrue(r.io.turns.all { it.kind != GuideTurn.Kind.INFO || "Listening" !in it.text })
    }

    @Test
    fun `no spoken turn tells the user to say 'read more' (it was heard back on 14 Oct) - the hint is on screen`() {
        val r = guide(device(), said("what's on screen", "read everything", "read more", "done"))
        for (turn in r.io.turns) assertFalse(Regex("(?i)read more").containsMatchIn(turn.text), turn.text)
        assertTrue(r.io.turns.any { it.hint == t.moreHint })
    }

    @Test
    fun `idle 3 minutes - stops and says where things are left`() {
        val fake = shop().apply { putInCart("Brown Bread", "400 g", 2) }
        val r = guide(fake, emptyList())
        assertEquals("${t.idle} Your cart has 2 items; nothing was ordered.", r.outcome.message)
    }

    // -- hand-off from the scripted flows -----------------------------------------------------------------

    private fun order(fake: FakeBlinkit, text: String, policy: OrderPolicy = OrderPolicy(allowOrders = true)): Outcome = runBlocking {
        fake.view = FakeBlinkit.View.Home
        val command = CommandParser.parse(text) as GroceryCommand
        val ctx = OrderFlows.contextFor(command, policy)
        val flow = OrderFlows.forCommand(command, policy, gate, ctx, OrderTexts(Lang.EN))!!
        AgentLoop(GatedDevice(gate, fake), Texts(Lang.EN)).run(flow, ctx, {}, pickOption = { null }) { Answer.NO }
    }

    @Test
    fun `OrderFlow - the item is already in the cart - hands off to guide mode, adds nothing`() {
        val fake = shop().apply { pkg = "app.saathi"; putInCart("Brown Bread", "400 g", 1) }
        val out = order(fake, "order brown bread") as Outcome.Failed
        assertEquals(Apps.BLINKIT, out.offer!!.packageName)
        assertTrue(out.offer!!.intro!!.startsWith("Brown Bread 400 g is already in your cart (quantity 1)."), out.offer!!.intro)
        assertEquals(mapOf("Brown Bread 400 g" to 1), fake.cartItems)
        assertTrue(fake.orders.isEmpty())
    }

    @Test
    fun `OrderFlow - an out-of-stock item in the cart - hands off with the question, never decides`() {
        val fake = shop().apply {
            pkg = "app.saathi"
            putInCart("Brown Bread", "400 g", 1)
            outOfStockInCart = "Amul Taaza Toned Milk" to "1 unit • 1 ltr"
        }
        val out = order(fake, "place my order") as Outcome.Failed
        assertEquals(t.outOfStockAsk("Amul Taaza Toned Milk, 1 unit • 1 ltr"), out.offer!!.intro)
        assertTrue(fake.orders.isEmpty())
        assertTrue(fake.wishlist.isEmpty())
    }

    @Test
    fun `OrderFlow - shimmer placeholders are waited out, not a failure`() {
        val fake = shop().apply { pkg = "app.saathi"; shimmerSnapshots = 3 }
        order(fake, "add brown bread to my cart")
        assertEquals(mapOf("Brown Bread 400 g" to 1), fake.cartItems)
    }

    @Test
    fun `guide after an out-of-stock hand-off - Move to wishlist on the right item (asks which), nothing else`() {
        val fake = shop().apply {
            putInCart("Brown Bread", "400 g", 1)
            outOfStockInCart = "Amul Taaza Toned Milk" to "1 unit • 1 ltr"
            view = FakeBlinkit.View.Checkout
        }
        val r = guide(fake, said("tap move to wishlist", "done"), intro = t.outOfStockAsk("Amul Taaza Toned Milk, 1 unit • 1 ltr"), pick = { req ->
            assertTrue(req.options.any { "Brown Bread" in it }, "${req.options}")
            req.options.indexOfFirst { "Brown Bread" !in it }
        })
        assertEquals(listOf("Amul Taaza Toned Milk"), fake.wishlist)
        assertEquals(mapOf("Brown Bread 400 g" to 1), fake.cartItems)
        assertTrue(r.io.said.first().startsWith(t.outOfStockAsk("Amul Taaza Toned Milk, 1 unit • 1 ltr")))
        assertTrue("Done — Amul Taaza Toned Milk is gone." in r.io.said[1], r.io.said[1])
        assertNull(fake.outOfStockInCart)
    }

    @Test
    fun `stuck in a flow - the loop offers guide mode on that app`() {
        val fake = shop()
        val stuck = object : Flow { override fun next(screen: Screen): Plan = Plan.Act(app.saathi.safety.Scroll(app.saathi.safety.Direction.UP), "x") }
        val out = runBlocking { AgentLoop(GatedDevice(gate, fake), Texts(Lang.EN)).run(stuck, app.saathi.safety.CommandContext(""), {}) { Answer.NO } } as Outcome.Failed
        assertEquals(GuideOffer(Apps.BLINKIT, null), out.offer)
    }
}
