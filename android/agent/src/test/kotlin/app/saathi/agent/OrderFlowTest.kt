package app.saathi.agent

import app.saathi.safety.Answer
import app.saathi.safety.CommandContext
import app.saathi.safety.Direction
import app.saathi.safety.Kind
import app.saathi.safety.SafetyGate
import app.saathi.safety.Screen
import app.saathi.safety.Scroll
import app.saathi.safety.Tap
import app.saathi.safety.TypeText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Blinkit ordering over [FakeBlinkit]: nothing is ever ordered without a clear yes on the read-back. */
class OrderFlowTest {
    private val gate = SafetyGate()
    private val t = OrderTexts(Lang.EN)
    private val on = OrderPolicy(allowOrders = true)

    private fun shop(vararg extra: FakeBlinkit.Item) = FakeBlinkit(
        listOf(
            FakeBlinkit.Item("Amul Taaza Toned Milk", "500 ml", 27),
            FakeBlinkit.Item("Mother Dairy Toned Milk", "500 ml", 28),
            FakeBlinkit.Item("Brown Bread", "400 g", 45),
            FakeBlinkit.Item("Choco Wafer Bar", "40 g", 20),
        ) + extra,
    )

    private class Run(
        val outcome: Outcome,
        val said: List<String>,
        val confirms: List<ConfirmRequest>,
        val picks: List<PickRequest>,
    )

    private fun parse(text: String): GroceryCommand = CommandParser.parse(text) as GroceryCommand

    private fun run(
        fake: FakeBlinkit,
        command: GroceryCommand,
        policy: OrderPolicy = on,
        pick: (PickRequest) -> Int? = { error("no question expected") },
        answer: (ConfirmRequest) -> Answer = { error("no confirmation expected") },
    ): Run = runBlocking {
        val said = mutableListOf<String>()
        val confirms = mutableListOf<ConfirmRequest>()
        val picks = mutableListOf<PickRequest>()
        val ctx = OrderFlows.contextFor(command, policy)
        val flow = OrderFlows.forCommand(command, policy, gate, ctx, t)!!
        val out = AgentLoop(GatedDevice(gate, fake), Texts(Lang.EN)).run(
            flow,
            ctx,
            { said += it },
            pickOption = { req ->
                picks += req
                fake.pkg = "app.saathi" // the sheet covers Blinkit; the loop brings it back
                pick(req)
            },
        ) { req ->
            confirms += req
            assertTrue(fake.orders.isEmpty(), "an order was placed before the answer")
            fake.pkg = "app.saathi"
            answer(req)
        }
        Run(out, said, confirms, picks)
    }

    private val amul = CartLine("Amul Taaza Toned Milk", "500 ml", 1, "₹27")

    // -- the happy path ---------------------------------------------------------------------------

    @Test
    fun `order milk - verbatim read-back HOLD, ordered only after yes`() {
        val fake = shop()
        val r = run(fake, parse("order Amul milk from Blinkit")) { Answer.YES }
        assertEquals(1, r.confirms.size)
        val req = r.confirms.single()
        assertEquals("R3", req.rule)
        assertEquals(listOf(amul), req.order!!.lines)
        assertEquals("₹27", req.order!!.total)
        assertTrue("Amul Taaza Toned Milk" in req.readback && "₹27" in req.readback, req.readback)
        assertEquals("Order from Blinkit: Amul Taaza Toned Milk 500 ml ×1 ₹27. Total ₹27. Shall I order it?", t.orderQuestion(req.order!!))
        assertEquals(listOf(mapOf("Amul Taaza Toned Milk 500 ml" to 1)), fake.orders)
        val out = r.outcome as Outcome.Answered
        assertEquals("Blinkit says: \"Order placed\".", out.message)
        assertTrue(t.placing in r.said)
    }

    @Test
    fun `no or unclear at the read-back - nothing ordered, the cart is said, empty my cart offered`() {
        for (a in listOf(Answer.NO, Answer.UNCLEAR)) {
            val fake = shop()
            val r = run(fake, parse("order Amul milk from Blinkit")) { a }
            assertTrue(fake.orders.isEmpty())
            val out = r.outcome as Outcome.Cancelled
            assertTrue(out.message.startsWith("Cancelled. Nothing was sent.") || out.message.startsWith("Cancelled"), out.message)
            assertTrue("still has: Amul Taaza Toned Milk 500 ml ×1 ₹27" in out.message, out.message)
            assertTrue("empty my cart" in out.message, out.message)
            assertEquals(1, fake.cartQty("Amul Taaza Toned Milk")) // left as it was, and the user was told
        }
    }

    // -- choosing ----------------------------------------------------------------------------------

    @Test
    fun `close call - asks once with each option verbatim, adds the one picked`() {
        val fake = shop()
        val r = run(fake, parse("Blinkit se doodh mangao"), policy = OrderPolicy(allowOrders = false), pick = { 1 })
        val q = r.picks.single()
        assertEquals(listOf("Amul Taaza Toned Milk 500 ml for ₹27", "Mother Dairy Toned Milk 500 ml for ₹28"), q.options)
        assertTrue(q.question.endsWith("Which one?"), q.question)
        assertEquals(1, fake.cartQty("Mother Dairy Toned Milk"))
        assertEquals(0, fake.cartQty("Amul Taaza Toned Milk"))
        assertTrue(fake.orders.isEmpty())
    }

    @Test
    fun `close call - no choice cancels, nothing added`() {
        val fake = shop()
        val r = run(fake, parse("order milk"), pick = { null })
        assertEquals(t.pickCancelled, r.outcome.message)
        assertTrue(fake.addTaps.isEmpty())
    }

    @Test
    fun `pick dialog - names, ordinals, Hindi, echo, cancel`() {
        val options = listOf("Amul Taaza Toned Milk 500 ml for ₹27", "Mother Dairy Toned Milk 500 ml for ₹28")
        assertEquals(PickDialog.Next.Picked(1), PickDialog(options).hear("mother dairy wala"))
        assertEquals(PickDialog.Next.Picked(1), PickDialog(options).hear("doosra"))
        assertEquals(PickDialog.Next.Picked(0), PickDialog(options).hear("the first one"))
        assertEquals(PickDialog.Next.Picked(0), PickDialog(options).hear("अमूल वाला"))
        assertEquals(PickDialog.Next.Picked(1), PickDialog(options).hear("2"))
        assertEquals(PickDialog.Next.Cancel, PickDialog(options).hear("cancel"))
        assertEquals(PickDialog.Next.Cancel, PickDialog(options).hear("koi nahi"))
        // Both named (what an echo of the question sounds like): unclear, then cancel.
        val d = PickDialog(options)
        assertEquals(PickDialog.Next.AskAgain, d.hear("Amul Taaza or Mother Dairy"))
        assertEquals(PickDialog.Next.Cancel, d.hear("hmm"))
        // The question's own frame words repeated back are unclear too.
        val q = t.whichProduct(listOf(Product("Amul Taaza Toned Milk", "500 ml", "₹27"), Product("Mother Dairy Toned Milk", "500 ml", "₹28")))
        assertEquals(PickDialog.Next.AskAgain, PickDialog(options).hear("I found a few close matches", listOf(q)))
    }

    @Test
    fun `quantity 2 from Hinglish - ADD then plus, read back as x2`() {
        val fake = shop()
        val cmd = parse("Blinkit se 2 Amul doodh mangao") as GroceryCommand.Order
        assertEquals("Amul milk", cmd.query)
        assertEquals(2, cmd.quantity)
        val r = run(fake, cmd) { Answer.NO }
        assertEquals(listOf("Amul Taaza Toned Milk 500 ml", "+ Amul Taaza Toned Milk 500 ml"), fake.addTaps)
        assertEquals(listOf(amul.copy(qty = 2)), r.confirms.single().order!!.lines)
        assertTrue(fake.orders.isEmpty())
    }

    @Test
    fun `size picker - the asked size is chosen in the sheet`() {
        val fake = FakeBlinkit(listOf(FakeBlinkit.Item("Amul Taaza Toned Milk", "500 ml", 27, variants = listOf("1 ltr" to 54))))
        val r = run(fake, parse("order 1 litre Amul milk"), policy = OrderPolicy(allowOrders = false))
        assertEquals(listOf("Amul Taaza Toned Milk 500 ml", "Amul Taaza Toned Milk 1 ltr"), fake.addTaps)
        assertEquals(mapOf("Amul Taaza Toned Milk 1 ltr" to 1), fake.cartItems)
        assertTrue(r.outcome.message.contains("Amul Taaza Toned Milk 1 ltr ×1 ₹54"), r.outcome.message)
    }

    @Test
    fun `size picker - no size said, Saathi asks which size`() {
        val fake = FakeBlinkit(listOf(FakeBlinkit.Item("Amul Taaza Toned Milk", "500 ml", 27, variants = listOf("1 ltr" to 54))))
        val r = run(fake, parse("order Amul milk"), policy = OrderPolicy(allowOrders = false), pick = { 1 })
        assertEquals(listOf("Amul Taaza Toned Milk 500 ml for ₹27", "Amul Taaza Toned Milk 1 ltr for ₹54"), r.picks.single().options)
        assertEquals(mapOf("Amul Taaza Toned Milk 1 ltr" to 1), fake.cartItems)
    }

    // -- guards --------------------------------------------------------------------------------------

    @Test
    fun `upsell sheet after ADD is closed, never added to`() {
        val fake = shop().apply { upsellAfterAdd = true }
        val r = run(fake, parse("order Amul milk")) { Answer.NO }
        assertEquals(listOf("Amul Taaza Toned Milk 500 ml"), fake.addTaps)
        assertEquals(mapOf("Amul Taaza Toned Milk 500 ml" to 1), fake.cartItems)
        assertEquals(listOf(amul), r.confirms.single().order!!.lines)
        // And the gate's R3 cart guard blocks an extra ADD outright once the asked quantity is in.
        fake.upsellAfterAdd = false
        val ctx = CommandContext("order Amul milk", quantity = 1, itemsAdded = 1)
        val results = runBlocking { fake.pkg = app.saathi.safety.Apps.BLINKIT; fake.view = FakeBlinkit.View.Results("bread"); fake.snapshot() }
        val add = results.elements.first { it.desc == "ADD" }
        val v = gate.check(Tap(add.index), results, ctx)
        assertEquals(Kind.BLOCK to "R3", v.kind to v.rule)
    }

    @Test
    fun `R11 - a minus button under the floating Place Order bar is scrolled clear, never tapped there`() {
        val fake = shop().apply {
            landscape = true
            putInCart("Amul Taaza Toned Milk", "500 ml", 1)
        }
        // The gate itself refuses the covered tap.
        fake.pkg = app.saathi.safety.Apps.BLINKIT
        fake.view = FakeBlinkit.View.Checkout
        val s: Screen = runBlocking { fake.snapshot() }
        val minus = s.elements.first { it.desc == "Decrease quantity" }
        assertEquals("R11", gate.check(Tap(minus.index), s, CommandContext("")).rule)
        fake.view = FakeBlinkit.View.Home

        val r = run(fake, parse("empty my cart")) { req ->
            assertEquals("EMPTY_CART", req.rule)
            assertEquals(OrderSummary.Purpose.EMPTY, req.order!!.purpose)
            Answer.YES
        }
        assertTrue(Scroll(Direction.DOWN) in fake.performed, "${fake.performed}")
        assertEquals(0, fake.coveredTaps)
        assertTrue(fake.orders.isEmpty())
        assertTrue(fake.cartItems.isEmpty())
        assertEquals(t.cartEmptied, r.outcome.message)
    }

    @Test
    fun `over the spending limit - refused honestly, nothing ordered, cart said`() {
        val fake = shop()
        val r = run(fake, parse("order Amul milk"), policy = OrderPolicy(allowOrders = true, spendingLimit = 20))
        val out = r.outcome as Outcome.Failed
        assertTrue(out.message.startsWith("The total is ₹27, above your spending limit of ₹20"), out.message)
        assertTrue("still has" in out.message)
        assertTrue(r.confirms.isEmpty())
        assertTrue(fake.orders.isEmpty())
    }

    @Test
    fun `UPI PIN after the order tap - handed over, nothing typed`() {
        val fake = shop().apply { pinAfterOrder = true }
        val r = run(fake, parse("order Amul milk")) { Answer.YES }
        assertTrue(r.outcome is Outcome.HandedOver)
        assertTrue("PIN" in r.outcome.message, r.outcome.message)
        assertTrue(fake.performed.none { it is TypeText })
    }

    @Test
    fun `Select Payment Method - handed over before any tap, Saathi never chooses how to pay`() {
        val fake = shop().apply { paymentSaved = false }
        val r = run(fake, parse("order Amul milk")) { Answer.YES }
        assertTrue(r.outcome is Outcome.HandedOver && r.outcome.message.startsWith(t.payYourself), r.outcome.message)
        assertTrue("still has" in r.outcome.message, r.outcome.message) // where things are left
        assertTrue(r.confirms.isEmpty())
        assertTrue(fake.orders.isEmpty())
    }

    @Test
    fun `Allow placing orders OFF - stops at the read-back, cart ready`() {
        val fake = shop()
        val r = run(fake, parse("order Amul milk"), policy = OrderPolicy(allowOrders = false))
        val out = r.outcome as Outcome.Answered
        assertEquals(
            "Your Blinkit cart: Amul Taaza Toned Milk 500 ml ×1 ₹27. Total ₹27. Ordering is turned off in Settings; your cart is ready. Nothing was ordered.",
            out.message,
        )
        assertTrue(r.confirms.isEmpty())
        assertTrue(fake.orders.isEmpty()) // the order bar's tap was never even planned
    }

    @Test
    fun `Read only level - searches and reads, never adds, cart changes refused`() {
        val ro = OrderPolicy(level = GroceryLevel.READ_ONLY, allowOrders = true)
        val fake = shop()
        val r = run(fake, parse("order Amul milk"), policy = ro)
        val out = r.outcome as Outcome.Answered
        assertTrue(out.message.startsWith("On Blinkit for Amul milk: Amul Taaza Toned Milk 500 ml for ₹27"), out.message)
        assertTrue("Read only" in out.message)
        assertTrue(fake.addTaps.isEmpty())
        assertTrue(fake.cartItems.isEmpty())
        assertEquals(t.groceryReadOnly, OrderFlows.refusal(parse("place my order"), ro, t))
        assertEquals(t.groceryReadOnly, OrderFlows.refusal(parse("empty my cart"), ro, t))
        assertNull(OrderFlows.refusal(parse("what's in my cart"), ro, t))
        assertNull(OrderFlows.forCommand(parse("place my order"), ro, gate, CommandContext(""), t))
        val off = OrderPolicy(level = GroceryLevel.OFF)
        for (c in listOf("order milk", "what's in my cart", "place my order")) assertEquals(t.groceryOff, OrderFlows.refusal(parse(c), off, t))
        // Read only never even allows the gate to pass an ADD (quantity 0 in the context).
        assertEquals(0, OrderFlows.contextFor(parse("order milk"), ro).quantity)
    }

    @Test
    fun `out of stock - said honestly, nothing added`() {
        val fake = FakeBlinkit(listOf(FakeBlinkit.Item("Amul Taaza Toned Milk", "500 ml", 27, inStock = false)))
        val r = run(fake, parse("order Amul milk"))
        assertEquals(t.outOfStock("Amul Taaza Toned Milk"), r.outcome.message)
        assertTrue(fake.addTaps.isEmpty())
    }

    @Test
    fun `nothing found - said honestly`() {
        val r = run(shop(), parse("order paneer"))
        assertEquals(t.nothingFound("paneer"), r.outcome.message)
    }

    @Test
    fun `not logged in - handed over, Saathi never types the number or OTP`() {
        val fake = shop().apply { loggedIn = false }
        val r = run(fake, parse("order Amul milk"))
        assertEquals(Outcome.HandedOver(t.loginNeeded), r.outcome)
        assertTrue(fake.performed.none { it is TypeText })
    }

    @Test
    fun `no internet inside Blinkit - honest message`() {
        val r = run(shop().apply { online = false }, parse("order Amul milk"))
        assertEquals(Outcome.Failed(t.noNetwork), r.outcome)
    }

    @Test
    fun `no delivery address - handed over before any order`() {
        val fake = shop().apply { addressSet = false }
        val r = run(fake, parse("order Amul milk"))
        assertTrue(r.outcome is Outcome.HandedOver && r.outcome.message.startsWith(t.addressNeeded), r.outcome.message)
        assertTrue(fake.orders.isEmpty())
    }

    @Test
    fun `hidden search field - deep link fails, types into the focused field and checks it`() {
        val fake = shop().apply { deepLinkWorks = false }
        run(fake, parse("order Amul milk"), policy = OrderPolicy(allowOrders = false))
        assertTrue(fake.performed.any { it is TypeText && it.text == "Amul milk" }, "${fake.performed}")
        assertEquals(mapOf("Amul Taaza Toned Milk 500 ml" to 1), fake.cartItems)
    }

    @Test
    fun `results loading in stages - waits, then goes on`() {
        val fake = shop().apply { loadingSnapshots = 2 }
        run(fake, parse("order Amul milk"), policy = OrderPolicy(allowOrders = false))
        assertEquals(mapOf("Amul Taaza Toned Milk 500 ml" to 1), fake.cartItems)
    }

    // -- the cart --------------------------------------------------------------------------------------

    @Test
    fun `what's in my cart - read verbatim, nothing changed`() {
        val fake = shop().apply { putInCart("Brown Bread", "400 g", 2) }
        val r = run(fake, parse("what's in my cart"))
        assertEquals("Your Blinkit cart: Brown Bread 400 g ×2 ₹45. Total ₹90.", r.outcome.message)
        assertTrue(fake.addTaps.isEmpty())
        assertEquals(mapOf("Brown Bread 400 g" to 2), fake.cartItems)
    }

    @Test
    fun `empty cart is said`() {
        val r = run(shop(), parse("what's in my cart"))
        assertEquals(t.cartEmpty, r.outcome.message)
    }

    @Test
    fun `add bread to my cart - added and read back, never goes to ordering`() {
        val fake = shop()
        val r = run(fake, parse("add bread to my cart"))
        assertEquals(
            "Added Brown Bread 400 g ×1 to your Blinkit cart. Your Blinkit cart: Brown Bread 400 g ×1 ₹45. Total ₹45. Nothing was ordered. Say “place my order” when you're ready.",
            r.outcome.message,
        )
        assertTrue(r.confirms.isEmpty())
        assertTrue(fake.orders.isEmpty())
    }

    @Test
    fun `place my order - read-back HOLD of the cart as it is`() {
        val fake = shop().apply { putInCart("Brown Bread", "400 g", 1) }
        val r = run(fake, parse("place my order")) { Answer.YES }
        assertEquals(listOf(CartLine("Brown Bread", "400 g", 1, "₹45")), r.confirms.single().order!!.lines)
        assertEquals(1, fake.orders.size)
    }

    @Test
    fun `empty my cart - Cancel (the default) leaves it as it was`() {
        val fake = shop().apply { putInCart("Brown Bread", "400 g", 1) }
        val r = run(fake, parse("empty my cart")) { Answer.UNCLEAR }
        assertEquals(t.emptyCancelled, r.outcome.message)
        assertEquals(mapOf("Brown Bread 400 g" to 1), fake.cartItems)
    }

    @Test
    fun `an order HOLD from any other flow is refused`() {
        val fake = shop().apply { putInCart("Brown Bread", "400 g", 1); pkg = app.saathi.safety.Apps.BLINKIT; view = FakeBlinkit.View.Checkout }
        val sneaky = object : Flow {
            override fun next(screen: Screen): Plan =
                Plan.Act(Tap(screen.elements.indexOfFirst { it.rid == "cv_checkout_container" }), "tap")
        }
        val out = runBlocking { AgentLoop(GatedDevice(gate, fake), Texts(Lang.EN)).run(sneaky, CommandContext(""), {}) { Answer.YES } }
        assertTrue(out is Outcome.Failed)
        assertTrue(fake.orders.isEmpty())
    }

    @Test
    fun `hold refusal re-checks Settings when the gate holds`() {
        val fake = shop().apply { putInCart("Brown Bread", "400 g", 1); pkg = app.saathi.safety.Apps.BLINKIT; view = FakeBlinkit.View.Checkout }
        val s = runBlocking { fake.snapshot() }
        val v = gate.check(Tap(s.elements.indexOfFirst { it.rid == "cv_checkout_container" }), s, CommandContext(""))
        assertEquals(Kind.HOLD to "R3", v.kind to v.rule)
        fun flow(p: OrderPolicy) = OrderFlows.forCommand(parse("place my order"), p, gate, CommandContext(""), t)!!
        assertNull(flow(on).holdRefusal(v, s))
        assertEquals(t.orderingOff, flow(OrderPolicy(allowOrders = false)).holdRefusal(v, s))
        assertEquals(t.overLimit("₹45", 40), flow(OrderPolicy(allowOrders = true, spendingLimit = 40)).holdRefusal(v, s))
        assertFalse(t.orderQuestion(flow(on).summary(s)!!).isEmpty())
    }
}
