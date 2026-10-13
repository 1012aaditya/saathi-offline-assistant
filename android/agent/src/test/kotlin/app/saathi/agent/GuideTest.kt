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

    private fun said(vararg s: String) = s.map { Heard(it) }

    private class Run(val outcome: Outcome, val io: ScriptIO)

    private fun guide(
        actuator: Actuator,
        commands: List<Heard>,
        target: String? = Apps.BLINKIT,
        policy: GuidePolicy = ordersOn,
        intro: String? = null,
        open: String? = null,
        answer: (ConfirmRequest) -> Answer = { error("no confirmation expected") },
        pick: (PickRequest) -> Int? = { error("no question expected") },
    ): Run = runBlocking {
        val io = ScriptIO(commands, answer, pick)
        val out = GuideSession(GatedDevice(gate, actuator), Lang.EN, io, policy, target).run(intro, open)
        Run(out, io)
    }

    private fun texts(s: Screen) = s.elements.flatMap { listOf(it.text, it.desc) }.filter { it.isNotEmpty() }

    // -- DESCRIBE ------------------------------------------------------------------------------------

    @Test
    fun `results - cards grouped with verbatim name, size, price, stock and ADD, cart badge, asks what next`() {
        val fake = shop().apply { putInCart("Brown Bread", "400 g", 2) }
        val s = runBlocking { fake.snapshot() }
        val v = GuideView.of(s, "Blinkit", t)
        val d = GuideView.describe(v, "Blinkit", t)
        assertTrue(d.startsWith("Blinkit."), d)
        assertTrue("Amul Taaza Toned Fresh Milk, 500 ml, ₹27, 2 left, ADD" in d, d)
        assertTrue("Mother Dairy Toned Milk, 500 ml, ₹28, Ad, ADD" in d, d)
        assertTrue("Brown Bread, 400 g, ₹45, quantity 2" in d, d)
        assertTrue("Cart: View cart, 2 items." in d, d)
        assertTrue(d.endsWith("What should I do?"), d)
        // One card = one line: its inner name / price / ADD aren't listed again.
        assertEquals(1, Regex("Amul Taaza Toned Fresh Milk").findAll(d).count(), d)
        // Every piece quoted from the screen is verbatim on it (hard rule 4).
        for (c in v.cards) for (part in listOf(c.name, c.size, c.price)) assertTrue(texts(s).any { part in it }, part)
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
    fun `checkout - address never read, out-of-stock block highlighted, suggestions and payment button listed`() {
        val fake = shop().apply {
            putInCart("Brown Bread", "400 g", 1)
            outOfStockInCart = "Amul Taaza Toned Milk" to "1 unit • 1 ltr"
            view = FakeBlinkit.View.Checkout
            paymentSaved = false
        }
        val s = runBlocking { fake.snapshot() }
        val v = GuideView.of(s, "Blinkit", t)
        val all = (0..3).joinToString(" ") { GuideView.describe(v, "Blinkit", t, it) }
        assertFalse(fake.address in all, all)
        assertFalse("411001" in all, all)
        assertTrue("your saved address" in all, all)
        assertTrue(all.startsWith("Blinkit: Checkout."), all)
        assertTrue("1 item is not in stock: Amul Taaza Toned Milk, 1 unit • 1 ltr." in all, all)
        assertTrue("Mother Dairy Toned Milk, 500 ml, ₹28, ADD" in all, all)
        assertTrue("Brown Bread, 400 g, ₹45, quantity 1, Move to wishlist" in all, all)
        assertTrue("Select Payment Method" in all, all)
        assertEquals("Amul Taaza Toned Milk, 1 unit • 1 ltr", v.outOfStock)
        assertEquals("1 item", v.cartCount)
    }

    @Test
    fun `read more pages through the rest`() {
        val fake = shop().apply { putInCart("Brown Bread", "400 g", 1); view = FakeBlinkit.View.Checkout }
        val r = guide(fake, said("read more", "read more", "done"))
        assertTrue("more. Say \"read more\"" in r.io.said[0], r.io.said[0])
        assertTrue(r.io.said[1] != r.io.said[0])
    }

    @Test
    fun `whatsapp chat - title, messages, empty box (placeholder is not text), no password text`() {
        val wa = FakeWhatsApp(linkedMapOf("Mummy" to mutableListOf(Msg("them", "Khana kha liya?")))).apply { pkg = WhatsApp.PKG; view = FakeWhatsApp.View.Chat("Mummy") }
        val s = runBlocking { wa.snapshot()!! }
        val d = GuideView.describe(GuideView.of(s, "WhatsApp", t), "WhatsApp", t)
        assertTrue(d.startsWith("WhatsApp: Mummy."), d)
        assertTrue("Khana kha liya?" in d, d)
        assertTrue("Message field" in d, d)
        assertFalse("has: Message" in d, d)
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
        val d = GuideView.describe(GuideView.of(runBlocking { app.snapshot() }, "Bank", t), "Bank", t)
        assertFalse("482913" in d, d)
        assertTrue("a password field" in d, d)
        assertTrue("a code or number" in d, d)
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
        )
        for ((text, want) in cases) assertEquals(want, GuideGrammar.parse(text), text)
    }

    @Test
    fun `a bare yes, ok or send is never an action (only the Confirm sheet takes a yes)`() {
        for (w in listOf("yes", "haan", "ok", "send it", "yes send", "हाँ", "theek hai")) {
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

    // -- ACT ---------------------------------------------------------------------------------------------

    @Test
    fun `add - two candidates, asks which one (cancel default), adds the one picked, says what changed`() {
        val fake = shop()
        val r = guide(fake, said("add milk", "done"), pick = { req ->
            assertEquals(listOf("Amul Taaza Toned Fresh Milk 500 ml, ₹27", "Mother Dairy Toned Milk 500 ml, ₹28"), req.options)
            assertFalse(Ordinals.containsOrdinal(req.question))
            1
        })
        assertEquals(mapOf("Mother Dairy Toned Milk 500 ml" to 1), fake.cartItems)
        val after = r.io.said[1]
        assertTrue(after.startsWith("Added. Mother Dairy Toned Milk now shows quantity 1. Cart: View cart, 1 item."), after)
        assertTrue(after.endsWith("What should I do?"), after)
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
    fun `can't see it - says so and what it can see`() {
        val r = guide(shop(), said("tap bananas", "done"))
        assertTrue(r.io.said[1].startsWith("I can't see \"bananas\" here. I can see: Search products; Results for milk bread; Amul Taaza Toned Fresh Milk 500 ml"), r.io.said[1])
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
        assertTrue(r.io.said[1].startsWith("Removed one. Brown Bread now shows quantity 1."), r.io.said[1])
    }

    @Test
    fun `open cart, then checkout's Select Payment Method - handed over, never pressed`() {
        val fake = shop().apply { putInCart("Brown Bread", "400 g", 1); paymentSaved = false }
        val r = guide(fake, said("open cart", "tap select payment method"))
        assertTrue(r.io.said[1].startsWith("Pressed View cart. Now on Checkout."), r.io.said[1])
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
    fun `search in Blinkit - the gated search link`() {
        val fake = shop()
        guide(fake, said("search for brown bread", "done"))
        assertTrue(Search("brown bread") in fake.performed, "${fake.performed}")
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
        val r = guide(fake, listOf(Heard("add brown bread", 400), Heard("add brown bread", 2_500), Heard("done")))
        assertEquals(t.tooSoon, r.io.said[1])
        assertEquals(mapOf("Brown Bread 400 g" to 1), fake.cartItems)
    }

    @Test
    fun `a long transcript repeating Saathi's description is an echo, not a command`() {
        val fake = shop()
        val first = runBlocking { GuideView.describe(GuideView.of(fake.snapshot(), "Blinkit", t), "Blinkit", t) }
        val echo = first.split(" ").drop(4).take(9).joinToString(" ")
        val r = guide(fake, listOf(Heard(echo, 5_000), Heard("done")))
        assertEquals(t.echo, r.io.said[1])
        assertTrue(fake.cartItems.isEmpty())
    }

    @Test
    fun `no auto-listen - Saathi waits once per turn for the user's press, never starts listening itself`() {
        val r = guide(shop(), said("read more", "done"))
        assertEquals(r.io.turns.size, r.io.waits) // each turn: one wait for the user, nothing else
        assertTrue(r.io.said.first().endsWith(t.pressSpeak), r.io.said.first())
        assertTrue(r.io.turns.all { it.kind != GuideTurn.Kind.INFO || "Listening" !in it.text })
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
