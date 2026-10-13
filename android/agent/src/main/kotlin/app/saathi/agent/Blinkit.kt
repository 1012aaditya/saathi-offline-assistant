package app.saathi.agent

import app.saathi.safety.Apps
import app.saathi.safety.BlinkitReadback
import app.saathi.safety.Element
import app.saathi.safety.Fuzz
import app.saathi.safety.ORDER_REGEX
import app.saathi.safety.Screen
import java.net.URLEncoder

/**
 * Deterministic Blinkit helpers (port of saathi/blinkit.py: search link, card ranking, cart bar,
 * checkout screen). Product cards, cart items and the verbatim total come from the safety
 * module's [BlinkitReadback] (the same code the gate's R3 read-back uses), so the flow and the
 * gate always agree on what is in the cart.
 *
 * Built against real screens (traces, 12 Oct): product cards are ViewGroups with desc
 * "<name> is available for ₹<price>[, <size>]"; inside each card an "ADD" button (or a
 * "quantity N" stepper once in the cart), "N options" when ADD opens a size picker, and an "Ad"
 * badge for sponsored items. The checkout screen has title "Checkout".
 */
object Blinkit {
    const val PKG = Apps.BLINKIT
    private const val SEARCH_URL = "https://blinkit.com/s/?q="

    /** Score gap needed to pick without asking (FR-A3), as in blinkit.py. */
    const val CLEAR_MARGIN = 6.0

    /** Below this the best card is not what was asked for (not in the prototype: it asked the model). */
    const val MIN_SCORE = 45.0

    /** Search results deep link (`am start -a VIEW -d <url> <blinkit>` in the prototype). */
    fun searchUrl(query: String): String =
        SEARCH_URL + URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")

    // -- product cards and ranking ------------------------------------------------------------

    private val SIZE = Regex(
        "(\\d+(?:\\.\\d+)?)\\s*(litres?|liters?|ltrs?|l|ml|kgs?|kg|g|gm|grams?|pcs|pieces?)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val UNIT: Map<String, Pair<String, Double>> = mapOf(
        "l" to ("ml" to 1000.0), "ltr" to ("ml" to 1000.0), "litre" to ("ml" to 1000.0), "liter" to ("ml" to 1000.0),
        "ml" to ("ml" to 1.0), "kg" to ("g" to 1000.0), "g" to ("g" to 1.0), "gm" to ("g" to 1.0), "gram" to ("g" to 1.0),
        "pcs" to ("pcs" to 1.0), "pc" to ("pcs" to 1.0), "piece" to ("pcs" to 1.0),
    )

    /** "1 ltr" -> ("ml", 1000.0); null when there is no size. */
    fun sizeOf(text: String): Pair<String, Double>? {
        val m = SIZE.find(text) ?: return null
        val unit = m.groupValues[2].lowercase().removeSuffix("s")
        val (base, mult) = UNIT[unit] ?: (unit to 1.0)
        return base to m.groupValues[1].toDouble() * mult
    }

    /** The query without its size words ("amul milk 500 ml" -> "amul milk"). */
    fun withoutSize(query: String): String = SIZE.replace(query, "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** Port of blinkit.score(): fuzzy name match, brand present, size match, ads last. */
    fun score(query: String, c: BlinkitReadback.Card): Double {
        val words = withoutSize(query)
        var s = Fuzz.tokenSetRatio(words.lowercase(), c.name.lowercase())
        val brand = words.split(" ").firstOrNull { it.isNotEmpty() }?.lowercase().orEmpty()
        if (brand.isNotEmpty() && brand !in c.name.lowercase()) s -= 30 // "Amul" asked, "Mother Dairy" shown
        val want = sizeOf(query)
        val have = sizeOf(c.size)
        if (want != null && have != null) s += if (want == have) 15 else -15
        if (c.ad) s -= 5
        return s
    }

    /** Best first; ties keep screen order (Python's sorted() is stable too). */
    fun rank(query: String, cards: List<BlinkitReadback.Card>): List<Pair<Double, BlinkitReadback.Card>> =
        cards.map { score(query, it) to it }.sortedByDescending { it.first }

    fun cards(screen: Screen): List<BlinkitReadback.Card> = BlinkitReadback.cards(screen)

    fun inside(e: Element, box: Element): Boolean {
        val (x, y) = e.center
        val (x1, y1, x2, y2) = box.bounds
        return x1 <= x && x <= x2 && y1 <= y && y <= y2
    }

    private fun inner(screen: Screen, card: BlinkitReadback.Card): List<Element> =
        screen.elements.filter { it !== card.card && inside(it, card.card) }

    private val OPTIONS = Regex("^(\\d+) options?$", RegexOption.IGNORE_CASE)

    /** "2 options" under the ADD button: ADD opens a size picker instead of adding. */
    fun hasOptions(screen: Screen, card: BlinkitReadback.Card): Boolean =
        inner(screen, card).any { OPTIONS.matches(it.desc.trim()) || OPTIONS.matches(it.text.trim()) }

    /** The stepper's "+" inside a card already in the cart. */
    fun increase(screen: Screen, card: BlinkitReadback.Card): Element? =
        inner(screen, card).firstOrNull { it.desc.equals("Increase quantity", true) || it.text.trim() == "+" }

    /** The stepper's "−" inside a card in the cart. */
    fun decrease(screen: Screen, card: BlinkitReadback.Card): Element? =
        inner(screen, card).firstOrNull { it.desc.equals("Decrease quantity", true) || it.text.trim() == "−" || it.text.trim() == "-" }

    /** Same product on another screen: same name, and the same size when both show one. */
    fun same(c: BlinkitReadback.Card, p: Product): Boolean =
        c.name == p.name && (c.size.isEmpty() || p.size.isEmpty() || c.size == p.size)

    // -- cart bar, checkout -----------------------------------------------------------------------

    private val CART_COUNT = Regex("view cart\\s*·?\\s*(\\d+)\\s+items?", RegexOption.IGNORE_CASE)
    private val SHIPMENT = Regex("Shipment of (\\d+) items?", RegexOption.IGNORE_CASE)

    private fun label(e: Element) = "${e.text} ${e.desc}".trim()

    /** The floating "View cart · N items" bar (hidden while a sheet is open). */
    fun cartBar(screen: Screen): Element? = screen.elements.firstOrNull { "view cart" in label(it).lowercase() }

    fun cartCount(screen: Screen): Int? =
        screen.elements.firstNotNullOfOrNull { e -> CART_COUNT.find(label(e))?.groupValues?.get(1)?.toIntOrNull() }

    fun isCheckout(screen: Screen): Boolean =
        screen.packageName == PKG && screen.elements.any { it.desc == "Checkout" && it.rid == "title" }

    /**
     * The checkout button (Place Order / Select Payment Method / Pay): the control the gate HOLDs
     * with the R3 read-back. Prefers the rids seen on device, then any matching label.
     */
    fun orderButton(screen: Screen): Element? {
        val matching = screen.elements.filter { ORDER_REGEX.matcher(label(it)).find() }
        return matching.firstOrNull { it.rid == "tv_action_text" || it.rid == "cv_checkout_container" }
            ?: matching.firstOrNull { it.clickable }
            ?: matching.firstOrNull()
    }

    /** "Shipment of N items" verbatim, or "". */
    fun shipment(screen: Screen): String =
        screen.elements.firstNotNullOfOrNull { e -> SHIPMENT.find(e.desc.ifEmpty { e.text })?.value } ?: ""

    fun shipmentCount(text: String): Int? = SHIPMENT.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** "₹1,234.50" -> 1234.5 (the verbatim total from [BlinkitReadback.cartTotal]). */
    fun rupees(text: String): Double? =
        Regex("₹\\s?([\\d,]+(?:\\.\\d{1,2})?)").find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

    // -- other screens Blinkit may show ---------------------------------------------------------

    private fun anyText(screen: Screen, rx: Regex): String? =
        screen.elements.flatMap { listOf(it.text, it.desc) }.firstOrNull { it.isNotEmpty() && rx.containsMatchIn(it) }

    private val LOGIN = Regex(
        "\\b(log ?in|sign ?in|sign ?up)\\b|enter (your )?(mobile|phone) number|\\botp\\b|verification code|" +
            "लॉग ?इन|मोबाइल नंबर",
        RegexOption.IGNORE_CASE,
    )
    private val NO_NETWORK = Regex(
        "no internet|you('| a)re offline|check your (internet|network|connection)|network error|" +
            "connection (lost|error|failed)|unable to connect|something went wrong|इंटरनेट नहीं",
        RegexOption.IGNORE_CASE,
    )
    private val ADDRESS_MISSING = Regex(
        "add (a |new )?(delivery )?address|enter (your )?(complete |delivery )?address|" +
            "select (your )?(delivery )?(address|location)|where should we deliver|पता (जोड़ें|डालें)",
        RegexOption.IGNORE_CASE,
    )
    private val NOT_SERVICEABLE = Regex(
        "not (yet )?serviceable|we don'?t deliver|currently unavailable in your area|store (is )?closed|" +
            "not delivering",
        RegexOption.IGNORE_CASE,
    )
    private val NO_RESULTS = Regex(
        "no results|nothing (found|matched)|couldn'?t find|did not match|0 results|no products? found|" +
            "कुछ नहीं मिला",
        RegexOption.IGNORE_CASE,
    )
    private val OUT_OF_STOCK = Regex("out of stock|sold out|currently unavailable|notify me|स्टॉक में नहीं", RegexOption.IGNORE_CASE)
    private val ORDER_PLACED = Regex(
        "order (placed|confirmed|successful)|order has been placed|thank you for (your )?order|" +
            "your order is on (its|the) way|ऑर्डर (हो गया|मिल गया)",
        RegexOption.IGNORE_CASE,
    )
    private val PAYMENT = Regex(
        "payment|pay (using|via|with|by)|\\bupi\\b|net ?banking|debit card|credit card|wallet|cash on delivery|" +
            "भुगतान",
        RegexOption.IGNORE_CASE,
    )

    fun loginText(screen: Screen): String? = if (cards(screen).isEmpty()) anyText(screen, LOGIN) else null
    fun noNetworkText(screen: Screen): String? = anyText(screen, NO_NETWORK)
    fun addressMissingText(screen: Screen): String? = if (cards(screen).isEmpty()) anyText(screen, ADDRESS_MISSING) else null
    fun notServiceableText(screen: Screen): String? = anyText(screen, NOT_SERVICEABLE)

    /** On the checkout screen (which has cards): "Add address to proceed" and the like. */
    fun addressNeeded(screen: Screen): String? = anyText(screen, ADDRESS_MISSING)
    fun noResultsText(screen: Screen): String? = anyText(screen, NO_RESULTS)
    fun outOfStockText(screen: Screen): String? = anyText(screen, OUT_OF_STOCK)
    fun orderPlacedText(screen: Screen): String? = anyText(screen, ORDER_PLACED)
    fun paymentText(screen: Screen): String? = anyText(screen, PAYMENT)

    /** Blinkit's location prompt: choose "Select location manually" (never grant location, R10). */
    fun selectManually(screen: Screen): Element? =
        screen.elements.firstOrNull { label(it).equals("select location manually", true) }

    /** "Device location not enabled" sheet: close it (the saved address is used). */
    fun closeLocationSheet(screen: Screen): Element? {
        if (anyText(screen, Regex("device location not enabled", RegexOption.IGNORE_CASE)) == null) return null
        return screen.elements.firstOrNull { it.rid == "crossButtonContainer" }
    }

    /** Blinkit's search box (visible, or the hidden focused field the service surfaces). */
    fun searchField(screen: Screen): Element? =
        screen.elements.firstOrNull { it.editable && !it.password && (it.desc == HIDDEN_FIELD || SEARCH_LABEL.containsMatchIn(label(it) + " " + it.rid)) }
            ?: screen.elements.firstOrNull { it.editable && !it.password }

    /** The search bar to tap so the (hidden) search field gets focus. */
    fun searchBar(screen: Screen): Element? =
        screen.elements.firstOrNull { it.clickable && !it.editable && (it.rid == "fl_search_bar" || SEARCH_LABEL.containsMatchIn(label(it))) }

    private val SEARCH_LABEL = Regex("search|खोज", RegexOption.IGNORE_CASE)

    /**
     * The desc the service gives a focused text field that the app hides from accessibility
     * (Blinkit's search box; port of device.py _hidden_focused_field). Zero bounds: never tapped.
     */
    const val HIDDEN_FIELD = "focused field"
}

/** A product as Blinkit shows it, verbatim. [size] may be "" (cart rows, some cards). */
data class Product(val name: String, val size: String, val price: String) {
    val label: String get() = listOf(name, size).filter { it.isNotEmpty() }.joinToString(" ")

    companion object {
        fun of(c: BlinkitReadback.Card) = Product(c.name, c.size, c.price)
    }
}

/** One cart row for a read-back: every field verbatim from the checkout screen. */
data class CartLine(val name: String, val size: String, val qty: Int, val price: String) {
    val label: String get() = listOf(name, size).filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * What the Confirm screen shows before an order (or before emptying the cart): the cart rows,
 * the total and the "Shipment of N items" note, all verbatim from the screen.
 */
data class OrderSummary(
    val lines: List<CartLine>,
    val total: String,
    val shipment: String = "",
    val purpose: Purpose = Purpose.PLACE,
) {
    enum class Purpose { PLACE, EMPTY }

    /** Every string that must be on screen (hard rule 4). */
    val quotes: List<String> get() = lines.flatMap { listOf(it.name, it.price) } + listOf(total, shipment).filter { it.isNotEmpty() }

    companion object {
        fun from(screen: Screen, purpose: Purpose = Purpose.PLACE): OrderSummary? {
            val items = BlinkitReadback.cartItems(screen)
            if (items.isEmpty()) return null
            val shown = items.sumOf { it.qty }
            val ship = Blinkit.shipment(screen).takeIf { s -> (Blinkit.shipmentCount(s) ?: 0) > shown }.orEmpty()
            return OrderSummary(
                items.map { CartLine(it.name, it.size, it.qty, it.price) },
                BlinkitReadback.cartTotal(screen),
                ship,
                purpose,
            )
        }
    }
}

/**
 * Port of device.py _hidden_focused_field(): Blinkit hides its search box from accessibility even
 * though Android reports it as the focused input. The service passes that focused node here and
 * it is added as an element (desc [Blinkit.HIDDEN_FIELD], zero bounds: never tapped, only typed
 * into). Order apps only (Blinkit); never a password field.
 */
object HiddenField {
    fun surface(snap: Snapshot, focused: RawNode?): Snapshot {
        val screen = snap.screen
        if (focused == null || screen.packageName !in app.saathi.safety.Apps.ORDER_APPS) return snap
        if ("EditText" !in (focused.className ?: "") || focused.password) return snap
        val rid = Normalizer.shortRid(focused.resourceId)
        if (screen.elements.any { it.editable && (rid.isEmpty() || it.rid == rid) }) return snap
        if (screen.elements.size >= Normalizer.MAX_ELEMENTS) return snap
        val text = if (focused.showingHint || (focused.hint != null && focused.text == focused.hint)) "" else Normalizer.clean(focused.text)
        val e = app.saathi.safety.Element(
            index = screen.elements.size,
            cls = "EditText",
            text = text,
            desc = Blinkit.HIDDEN_FIELD,
            rid = rid,
            clickable = false,
            editable = true,
            password = false,
            bounds = app.saathi.safety.Bounds(0, 0, 0, 0),
            center = app.saathi.safety.Point(0, 0),
        )
        val els = screen.elements + e
        return Snapshot(screen.copy(elements = els, hash = Normalizer.screenHash(screen.packageName, els)), snap.handles + focused.handle)
    }

    /** After typing into Blinkit's search box, the service presses Enter (results only show then). */
    fun submitsWithEnter(packageName: String, target: app.saathi.safety.Element?): Boolean =
        target != null && packageName in app.saathi.safety.Apps.ORDER_APPS && target.editable && !target.password &&
            (target.desc == Blinkit.HIDDEN_FIELD || Regex("search|खोज", RegexOption.IGNORE_CASE).containsMatchIn("${target.text} ${target.desc} ${target.rid}"))
}
