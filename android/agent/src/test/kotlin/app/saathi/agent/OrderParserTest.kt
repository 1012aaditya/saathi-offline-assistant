package app.saathi.agent

import app.saathi.safety.Answer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OrderParserTest {
    private fun parse(s: String) = CommandParser.parse(s)

    private fun order(s: String): GroceryCommand.Order = parse(s) as GroceryCommand.Order

    @Test
    fun `English, Hinglish and Hindi orders`() {
        order("order milk from Blinkit").let { assertEquals("milk", it.query); assertEquals(1, it.quantity); assertFalse(it.addOnly) }
        order("Blinkit se 2 Amul doodh mangao").let { assertEquals("Amul milk", it.query); assertEquals(2, it.quantity) }
        order("order 1 kg aata").let { assertEquals("1 kg atta", it.query); assertEquals(1, it.quantity) }
        order("एक किलो आटा मंगाओ").let { assertEquals("1 kg atta", it.query); assertEquals(1, it.quantity) }
        order("do packet doodh mangwao").let { assertEquals("milk", it.query); assertEquals(2, it.quantity) }
        order("Blinkit se do Amul doodh mangao").let { assertEquals("Amul milk", it.query); assertEquals(2, it.quantity) }
        order("ब्लिंकिट से अमूल दूध मंगाओ").let { assertEquals("Amul milk", it.query) }
        order("buy brown bread on Blinkit").let { assertEquals("brown bread", it.query) }
    }

    @Test
    fun `a recogniser self-correction keeps the last word`() {
        order("बेड, ब्रेड मंगाओ").let { assertEquals("bread", it.query); assertEquals(null, it.problem) }
    }

    @Test
    fun `add to cart, cart, place order, empty cart`() {
        order("add bread to my cart").let { assertEquals("bread", it.query); assertTrue(it.addOnly) }
        order("doodh cart mein daalo").let { assertEquals("milk", it.query); assertTrue(it.addOnly) }
        assertTrue(parse("what's in my cart") is GroceryCommand.ReadCart)
        assertTrue(parse("What’s in my Blinkit cart?") is GroceryCommand.ReadCart)
        assertTrue(parse("कार्ट में क्या है") is GroceryCommand.ReadCart)
        assertTrue(parse("place my order") is GroceryCommand.PlaceOrder)
        assertTrue(parse("order karo") is GroceryCommand.PlaceOrder)
        assertTrue(parse("empty my cart") is GroceryCommand.EmptyCart)
        assertTrue(parse("कार्ट खाली करो") is GroceryCommand.EmptyCart)
    }

    @Test
    fun `unclear items are asked about, never guessed`() {
        val t = OrderTexts(Lang.EN)
        assertEquals(GroceryCommand.Problem.Several(listOf("milk", "bread")), order("order milk and bread").problem)
        assertEquals(GroceryCommand.Problem.Unknown("भिंडी"), order("भिंडी मंगाओ").problem)
        assertEquals(GroceryCommand.Problem.NoItem, order("Blinkit se kuch mangao").problem)
        assertEquals(GroceryCommand.Problem.TooMany(OrderParser.MAX_QUANTITY), order("order 50 milk").problem)
        assertEquals(t.whatToOrder, OrderFlows.missing(order("Blinkit se kuch mangao"), t))
        assertEquals("I can order one item at a time. Which first: milk or bread?", OrderFlows.missing(order("order milk and bread"), t))
    }

    @Test
    fun `other commands are not taken as orders`() {
        assertTrue(parse("Mummy ko bolo doodh mangao") is Command.Reply)
        assertTrue(parse("open Blinkit") is Command.OpenApp)
        assertTrue(parse("read Mom's last message") is Command.ReadLast)
    }

    @Test
    fun `nothing Saathi says or shows while waiting for a yes has a yes word`() {
        for (lang in Lang.entries) {
            val t = OrderTexts(lang)
            val o = OrderSummary(listOf(CartLine("Amul Taaza Toned Milk", "500 ml", 1, "₹27")), "₹27", "Shipment of 3 items")
            val p = listOf(Product("Amul Taaza Toned Milk", "500 ml", "₹27"), Product("Mother Dairy Toned Milk", "500 ml", "₹28"))
            for (s in listOf(t.orderQuestion(o), t.emptyQuestion(o), t.whichProduct(p), t.whichSize(p), t.pickHint, t.readback(o))) {
                assertFalse(ConfirmDialog.containsYesWord(s), s)
            }
        }
    }

    @Test
    fun `order sheet - a yes with order words is a yes, order words alone are not`() {
        val order = ConfirmDialog(extraFillers = ConfirmDialog.ORDER_FILLERS)
        assertEquals(ConfirmDialog.Next.SEND, order.hear("yes order it"))
        assertEquals(ConfirmDialog.Next.SEND, ConfirmDialog(extraFillers = ConfirmDialog.ORDER_FILLERS).hear("haan order karo"))
        assertEquals(ConfirmDialog.Next.ASK_AGAIN, ConfirmDialog(extraFillers = ConfirmDialog.ORDER_FILLERS).hear("place the order"))
        assertEquals(ConfirmDialog.Next.CANCEL, ConfirmDialog(extraFillers = ConfirmDialog.ORDER_FILLERS).hear("no don't order"))
        // The WhatsApp sheet keeps its stricter vocabulary.
        assertEquals(ConfirmDialog.Next.ASK_AGAIN, ConfirmDialog().hear("yes order it"))
        assertEquals(Answer.UNCLEAR, ConfirmDialog.classify("yes order it"))
        // The real sheet passes its own question as an echo source: a short yes still counts.
        val asked = OrderTexts(Lang.EN).orderQuestion(OrderSummary(listOf(CartLine("Amul Taaza Toned Milk", "500 ml", 1, "₹27")), "₹27"))
        assertEquals(ConfirmDialog.Next.SEND, ConfirmDialog(extraFillers = ConfirmDialog.ORDER_FILLERS).hear("yes order it", listOf(asked)))
        assertEquals(ConfirmDialog.Next.SEND, ConfirmDialog(extraFillers = ConfirmDialog.ORDER_FILLERS).hear("haan", listOf(asked)))
        // An echo of the order question is never a yes.
        val q = OrderTexts(Lang.EN).orderQuestion(OrderSummary(listOf(CartLine("Brown Bread", "400 g", 1, "₹45")), "₹45"))
        assertEquals(ConfirmDialog.Next.ASK_AGAIN, ConfirmDialog(extraFillers = ConfirmDialog.ORDER_FILLERS).hear("Shall I order it", listOf(q)))
    }

    @Test
    fun `Blinkit's hidden search field is surfaced for typing, never elsewhere, never a password`() {
        val bar = RawNode("android.widget.FrameLayout", null, "Search products", null, "x:id/fl_search_bar", app.saathi.safety.Bounds(0, 40, 1000, 140), clickable = true)
        val snap = Normalizer.normalize(listOf(bar), Blinkit.PKG)
        val focused = RawNode("android.widget.EditText", "milk", null, null, "x:id/edit_text", app.saathi.safety.Bounds(0, 0, 0, 0), editable = true, handle = "node")
        val out = HiddenField.surface(snap, focused)
        val e = out.screen.elements.last()
        assertEquals(Blinkit.HIDDEN_FIELD to "milk", e.desc to e.text)
        assertTrue(e.editable && e.bounds.x2 == 0)
        assertEquals("node", out.handles.last())
        assertTrue(HiddenField.submitsWithEnter(Blinkit.PKG, e))
        assertFalse(HiddenField.submitsWithEnter(WhatsApp.PKG, e)) // Enter can send in WhatsApp
        assertEquals(1, HiddenField.surface(Normalizer.normalize(listOf(bar), WhatsApp.PKG), focused).screen.elements.size)
        val pwd = RawNode("android.widget.EditText", null, null, null, "x:id/pin", app.saathi.safety.Bounds(0, 0, 0, 0), editable = true, password = true)
        assertEquals(1, HiddenField.surface(snap, pwd).screen.elements.size)
    }
}
