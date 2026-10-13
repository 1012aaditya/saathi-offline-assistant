package app.saathi.safety

import java.math.BigInteger

/**
 * The part of saathi/blinkit.py that the safety gate's R3 read-back uses: product cards,
 * cart items, the verbatim total and order_readback(). Ranking / next_action are not part of
 * the gate and are not ported here.
 */
object BlinkitReadback {
    data class Card(
        val name: String,
        val price: String, // verbatim, e.g. "₹59"
        val size: String, // verbatim, e.g. "1 ltr" ("" in the cart)
        val card: Element,
        val add: Element?, // ADD button inside the card
        val qty: Int, // already in cart
        val ad: Boolean,
    )

    private val CARD = Py.re("^(?<name>.+?) is available for (?<price>₹[${Py.D}.,]+)(?:, (?<size>.+))?$")
    private val QTY = Py.re("^quantity (${Py.D}+)$", ignoreCase = true)
    private val SHIPMENT = Py.re("Shipment of (${Py.D}+) items?", ignoreCase = true)

    private fun inside(e: Element, box: Element): Boolean {
        val (x, y) = e.center
        val (x1, y1, x2, y2) = box.bounds
        return x1 <= x && x <= x2 && y1 <= y && y <= y2
    }

    fun cards(screen: Screen): List<Card> {
        val out = mutableListOf<Card>()
        for (e in screen.elements) {
            val m = Py.match(CARD, e.desc) ?: Py.match(CARD, e.text) ?: continue
            val inner = screen.elements.filter { it !== e && inside(it, e) }
            val add = inner.firstOrNull { Py.strip(it.desc.ifEmpty { it.text }).uppercase() == "ADD" }
            val qty = inner.firstNotNullOfOrNull { x ->
                Py.match(QTY, x.desc)?.let { Py.saturatedInt(Py.parseInt(it.group(1))) }
            } ?: 0
            val size = m.group("size").orEmpty().ifEmpty {
                inner.firstOrNull { it.rid == "tv_variant" || it.rid == "tv_uom_title" }?.desc ?: ""
            }
            out += Card(
                Py.strip(m.group("name")),
                m.group("price"),
                Py.strip(size),
                e,
                add,
                qty,
                inner.any { it.desc == "Ad" },
            )
        }
        return out
    }

    /** Items on the checkout screen: cards with a quantity, above "You might also like". */
    fun cartItems(screen: Screen): List<Card> {
        val cut = screen.elements.firstOrNull { "You might also like" in (it.text + it.desc) }?.center?.y
            ?: 1_000_000_000
        return cards(screen).filter { it.qty > 0 && it.card.center.y < cut }
    }

    /** Verbatim total, only when the screen shows one next to "TOTAL". */
    fun cartTotal(screen: Screen): String {
        for (e in screen.elements) {
            if ("TOTAL" in e.text && "₹" in e.text) {
                return e.text.split("·").firstOrNull { "₹" in it }?.let { Py.strip(it) } ?: ""
            }
        }
        return ""
    }

    /**
     * R3 read-back from the checkout screen: each cart item verbatim (+ total if shown).
     * Returns (spoken text, the verbatim parts for R4 grounding), or null if not a cart.
     */
    fun orderReadback(screen: Screen): Pair<String, List<String>>? {
        val items = cartItems(screen)
        if (items.isEmpty()) return null
        val parts = mutableListOf<String>()
        val lines = mutableListOf<String>()
        for (c in items) {
            parts += listOf(c.name, c.price)
            lines += "${c.name} ${c.size} ×${c.qty} ${c.price}".replace("  ", " ")
        }
        val total = cartTotal(screen)
        if (total.isNotEmpty()) parts += total
        val shipment = screen.elements.firstNotNullOfOrNull { e ->
            Py.search(SHIPMENT, e.desc.ifEmpty { e.text })?.group()
        } ?: ""
        val shown = items.fold(BigInteger.ZERO) { acc, c -> acc + BigInteger.valueOf(c.qty.toLong()) }
        var more = ""
        if (shipment.isNotEmpty() && Py.parseInt(Py.search(SHIPMENT, shipment)!!.group(1)) > shown) {
            // Never let a total cover items the user didn't hear about.
            parts += shipment
            more = " ($shipment, बाकी आइटम स्क्रीन के नीचे हैं)"
        }
        val tail = if (total.isNotEmpty()) " कुल $total." else " कुल रकम इस स्क्रीन पर नहीं दिख रही।"
        return "कार्ट में: ${lines.joinToString(", ")}$more.$tail ऑर्डर कर दूँ?" to parts
    }
}
