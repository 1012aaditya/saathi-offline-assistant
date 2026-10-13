package app.saathi.agent

import app.saathi.safety.Action
import app.saathi.safety.Apps
import app.saathi.safety.Back
import app.saathi.safety.Bounds
import app.saathi.safety.Direction
import app.saathi.safety.Element
import app.saathi.safety.Home
import app.saathi.safety.OpenApp
import app.saathi.safety.Point
import app.saathi.safety.Screen
import app.saathi.safety.Scroll
import app.saathi.safety.Search
import app.saathi.safety.Tap
import app.saathi.safety.TypeText

/**
 * A tiny synthetic Blinkit behind the [Actuator] interface, modelled on the prototype's logic
 * (saathi/blinkit.py): product cards "<name> is available for ₹<price>, <size>" with ADD / a
 * "quantity N" stepper / "N options" (size picker sheet), an upsell sheet after ADD, the
 * "View cart · N items" bar, and a checkout screen (rows above "You might also like", a
 * "Shipment of N items" note, and a "₹X · TOTAL · Place Order" bar). All products and texts are
 * made up. It records every performed action and every order placed, so tests can prove that
 * nothing was ordered without a yes.
 */
class FakeBlinkit(val catalog: List<Item>) : Actuator {
    data class Item(
        val name: String,
        val size: String,
        val price: Int,
        val ad: Boolean = false,
        val inStock: Boolean = true,
        /** Other sizes of the same product: ADD opens a size picker sheet. */
        val variants: List<Pair<String, Int>> = emptyList(),
        val words: Set<String> = emptySet(),
        /** "2 left" (inventory_info), real shapes only. */
        val left: String? = null,
    ) {
        fun matches(q: String): Boolean {
            val ws = Blinkit.withoutSize(q).lowercase().split(" ").filter { it.isNotEmpty() }
            val hay = (name.lowercase().split(" ") + words).toSet()
            return ws.any { it in hay }
        }
    }

    private data class Key(val name: String, val size: String)

    sealed interface View {
        data object Home : View
        data object SearchBox : View
        data class Results(val query: String) : View
        data class Sizes(val item: Item) : View
        data object Upsell : View
        data object Checkout : View
        data object Payment : View
        data object Placed : View
        data object Login : View
        data object Offline : View
    }

    var pkg = "app.saathi"
    var view: View = View.Home
    var loggedIn = true
    var online = true
    var addressSet = true
    var deepLinkWorks = true
    var upsellAfterAdd = false
    var landscape = false
    var paymentSaved = true
    var pinAfterOrder = false
    /** Snapshots that show the results page still loading (no cards yet). */
    var loadingSnapshots = 0

    // -- real shapes from the owner's tablet (13 Oct, portrait), opt-in ----------------------------
    /**
     * Cards with tv_name / tv_uom_title / price / "Ad" / "2 left" children, the cart bar as
     * view_cart "View cart" + tv_quantity "N items", and the real checkout: title desc "Checkout",
     * "Delivering to Home" + the address, an out-of-stock block with suggestions, the shipment with
     * cart rows (tv_name, tv_variant, stepper, total_item_price, "Move to wishlist"), "You might
     * also like", and tv_action_text ("Place Order" / "Select Payment Method").
     */
    var realShapes = false
    /** Snapshots of results drawn as shimmer placeholders (rids shimmer_image / shimmer_title / button1 / button2, no labels). */
    var shimmerSnapshots = 0
    /**
     * The owner's tablet, 14 Oct 00:40 (with [realShapes]): screen chrome around the content — a
     * "Navigate up" / "Search" / "펩 Share" toolbar (an icon-font glyph in front of "Share"), the
     * search box with what was typed, "Clear all", "Double tap to voice search", "Voice search
     * button", rating / delivery-time / wishlist-heart badges in cards, the cart bar as "View cart
     * · N items" + "N items", the address with "Change", and the out-of-stock row inside a
     * clickable, unlabelled container (which made the old guide say "1 item is not in stock: .").
     */
    var deviceChrome = false
    /** Real checkout: an out-of-stock item (title, subtitle) and what Blinkit suggests instead. */
    var outOfStockInCart: Pair<String, String>? = null
    val suggestions = mutableListOf(Item("Mother Dairy Toned Milk", "500 ml", 28))
    /** Made up. Must never be spoken or logged. */
    val address = "Flat 4B, 12 Example Road, Pune 411001"
    val wishlist = mutableListOf<String>()

    private val cart = linkedMapOf<Key, Int>()
    private var upsellShown = false
    private var scrolled = 0
    private var typed = ""

    val performed = mutableListOf<Action>()
    /** Taps on a "−" that the floating order bar covered (must stay 0). */
    var coveredTaps = 0
    val orders = mutableListOf<Map<String, Int>>()
    val addTaps = mutableListOf<String>()

    fun putInCart(name: String, size: String, qty: Int) { cart[Key(name, size)] = qty }
    fun cartQty(name: String): Int = cart.filterKeys { it.name == name }.values.sum()
    val cartItems: Map<String, Int> get() = cart.mapKeys { "${it.key.name} ${it.key.size}" }

    private fun price(k: Key): Int {
        val item = catalog.first { it.name == k.name }
        return if (item.size == k.size) item.price else item.variants.first { it.first == k.size }.second
    }

    private val total: Int get() = cart.entries.sumOf { price(it.key) * it.value }

    // -- rendering ----------------------------------------------------------------------------------

    private class Built(val screen: Screen, val handlers: Map<Int, () -> Boolean>)

    private fun build(): Built {
        val els = mutableListOf<Element>()
        val handlers = mutableMapOf<Int, () -> Boolean>()
        fun add(
            text: String = "",
            desc: String = "",
            rid: String = "",
            cls: String = "View",
            clickable: Boolean = false,
            editable: Boolean = false,
            b: Bounds,
            onTap: (() -> Boolean)? = null,
        ) {
            val i = els.size
            els += Element(i, cls, text, desc, rid, clickable, editable, false, b, Point((b.x1 + b.x2) / 2, (b.y1 + b.y2) / 2))
            if (onTap != null) handlers[i] = onTap
        }
        val width = if (landscape) 2000 else 1080
        val height = if (landscape) 1200 else 2000
        fun cartBar() {
            if (cart.isEmpty()) return
            val n = cart.values.sum()
            if (realShapes && deviceChrome) {
                add(desc = "View cart · $n item${if (n == 1) "" else "s"}", rid = "view_cart", clickable = true, b = Bounds(300, height - 200, 780, height - 140)) { view = View.Checkout; scrolled = 0; true }
                add(text = "$n item${if (n == 1) "" else "s"}", rid = "tv_quantity", b = Bounds(300, height - 140, 780, height - 80))
                return
            }
            if (realShapes) {
                add(text = "View cart", rid = "view_cart", clickable = true, b = Bounds(300, height - 200, 780, height - 140)) { view = View.Checkout; scrolled = 0; true }
                add(text = "$n item${if (n == 1) "" else "s"}", rid = "tv_quantity", b = Bounds(300, height - 140, 780, height - 80))
                return
            }
            add(text = "View cart · $n item${if (n == 1) "" else "s"}", rid = "container", clickable = true, b = Bounds(300, height - 200, 780, height - 80)) {
                view = View.Checkout; scrolled = 0; true
            }
        }
        fun card(item: Item, size: String, price: Int, x: Int, y: Int, inSheet: Boolean) {
            val key = Key(item.name, size)
            val qty = cart[key] ?: 0
            add(desc = "${item.name} is available for ₹$price, $size", cls = "ViewGroup", clickable = true, b = Bounds(x, y, x + 320, y + 600))
            if (realShapes && deviceChrome) {
                add(desc = "Add to wishlist", rid = "wishlist_icon", clickable = true, b = Bounds(x + 270, y + 10, x + 310, y + 50)) { true }
                add(text = "4.3", rid = "rating", b = Bounds(x + 10, y + 405, x + 60, y + 425))
                add(text = "(12.4k)", rid = "rating_count", b = Bounds(x + 60, y + 405, x + 140, y + 425))
                add(text = "10 MINS", rid = "eta", b = Bounds(x + 10, y + 300, x + 120, y + 330))
            }
            if (realShapes) {
                add(text = item.name, rid = "tv_name", b = Bounds(x + 10, y + 340, x + 310, y + 400))
                add(text = "₹$price", rid = "price", b = Bounds(x + 10, y + 470, x + 170, y + 500))
                item.left?.let { add(text = it, rid = "inventory_info", b = Bounds(x + 10, y + 270, x + 120, y + 295)) }
            }
            if (item.ad) add(desc = "Ad", rid = "bottom_left_overlay", b = Bounds(x + 10, y + 300, x + 50, y + 330))
            add(desc = size, rid = "tv_uom_title", b = Bounds(x + 10, y + 430, x + 170, y + 460))
            if (!item.inStock) {
                add(desc = "Notify me", rid = "tv_title", clickable = true, b = Bounds(x + 180, y + 420, x + 320, y + 480)) { true }
                return
            }
            if (qty == 0) {
                add(desc = "ADD", rid = "tv_title", clickable = true, b = Bounds(x + 180, y + 420, x + 320, y + 470)) {
                    addTaps += "${item.name} $size"
                    if (!inSheet && item.variants.isNotEmpty()) {
                        view = View.Sizes(item)
                    } else {
                        cart[key] = 1
                        if (upsellAfterAdd && !upsellShown) { upsellShown = true; view = View.Upsell }
                    }
                    true
                }
                if (!inSheet && item.variants.isNotEmpty()) {
                    add(desc = "${item.variants.size + 1} options", rid = "tv_subtext_inside", b = Bounds(x + 180, y + 472, x + 320, y + 495))
                }
            } else {
                add(desc = "Decrease quantity", rid = "icon_decrement", clickable = true, b = Bounds(x + 180, y + 420, x + 225, y + 480)) {
                    if (qty <= 1) cart.remove(key) else cart[key] = qty - 1; true
                }
                add(desc = "quantity $qty", rid = "tv_title", clickable = true, b = Bounds(x + 225, y + 420, x + 275, y + 480))
                add(desc = "Increase quantity", rid = "icon_increment", clickable = true, b = Bounds(x + 275, y + 420, x + 320, y + 480)) {
                    addTaps += "+ ${item.name} $size"
                    cart[key] = qty + 1; true
                }
            }
        }
        fun searchBar() = add(desc = "Search products", rid = "fl_search_bar", cls = "FrameLayout", clickable = true, b = Bounds(0, 40, width, 140)) {
            view = View.SearchBox; typed = ""; true
        }

        if (pkg != Apps.BLINKIT) {
            add(text = "Stop", cls = "Button", clickable = true, b = Bounds(0, 100, 500, 200))
            return Built(Screen(pkg, els, Normalizer.screenHash(pkg, els)), handlers)
        }
        when (val v = view) {
            View.Login -> {
                add(text = "Log in or sign up", b = Bounds(0, 300, width, 360))
                add(desc = "Enter mobile number", cls = "EditText", editable = true, clickable = true, b = Bounds(0, 400, width, 480))
                add(text = "Continue", cls = "Button", clickable = true, b = Bounds(0, 520, width, 600)) { true }
            }
            View.Offline -> {
                add(text = "No internet connection", b = Bounds(0, 300, width, 360))
                add(text = "Retry", cls = "Button", clickable = true, b = Bounds(0, 400, width, 480)) { true }
            }
            View.Home -> {
                searchBar()
                add(text = "Shop by category", b = Bounds(0, 200, width, 260))
                cartBar()
            }
            View.SearchBox -> {
                searchBar()
                // Blinkit hides its text field from accessibility; the service surfaces the focused
                // one with zero bounds and desc "focused field" (Blinkit.HIDDEN_FIELD).
                add(text = typed, desc = Blinkit.HIDDEN_FIELD, cls = "EditText", editable = true, b = Bounds(0, 0, 0, 0))
            }
            is View.Results -> {
                if (realShapes && deviceChrome) {
                    add(desc = "Search for atta, dal, coke and more", rid = "fl_search_bar", cls = "FrameLayout", clickable = true, b = Bounds(0, 40, width, 140)) { view = View.SearchBox; typed = ""; true }
                    add(desc = "Navigate up", rid = "back", cls = "ImageButton", clickable = true, b = Bounds(0, 150, 80, 210)) { view = View.Home; true }
                    add(text = v.query, desc = "Search for atta…", rid = "search_edit_text", cls = "EditText", editable = true, clickable = true, b = Bounds(80, 150, 800, 210))
                    add(desc = "Clear all", rid = "clear", cls = "ImageButton", clickable = true, b = Bounds(800, 150, 880, 210)) { true }
                    add(desc = "Double tap to voice search", rid = "voice_hint", b = Bounds(880, 150, 960, 210))
                    add(desc = "Voice search button", rid = "voice_search", cls = "ImageButton", clickable = true, b = Bounds(960, 150, 1040, 210)) { true }
                } else {
                    searchBar()
                }
                if (shimmerSnapshots > 0) {
                    shimmerSnapshots--
                    for (i in 0 until 4) {
                        val x = 10 + (i % 3) * 340
                        add(rid = "shimmer_image", b = Bounds(x, 230, x + 320, 500))
                        add(rid = "shimmer_title", b = Bounds(x, 510, x + 320, 540))
                        add(rid = "button1", cls = "Button", b = Bounds(x, 550, x + 150, 600))
                        add(rid = "button2", cls = "Button", b = Bounds(x + 160, 550, x + 320, 600))
                    }
                } else if (loadingSnapshots > 0) {
                    loadingSnapshots--
                } else {
                    if (!deviceChrome) add(text = "Results for ${v.query}", rid = "tv_title", b = Bounds(10, 160, 600, 200))
                    val hits = catalog.filter { it.matches(v.query) }
                    if (hits.isEmpty()) add(text = "No results found for ${v.query}", b = Bounds(10, 300, width, 360))
                    hits.forEachIndexed { i, item ->
                        card(item, item.size, item.price, 10 + (i % 3) * 340, 230 + (i / 3) * 640, inSheet = false)
                    }
                    cartBar()
                }
            }
            is View.Sizes -> {
                // A modal sheet: only the sheet is in the tree (the results are hidden behind it).
                add(text = v.item.name, rid = "tv_title", b = Bounds(10, 500, 700, 540))
                val sizes = listOf(v.item.size to v.item.price) + v.item.variants
                sizes.forEachIndexed { i, (size, p) -> card(v.item, size, p, 10 + i * 340, 600, inSheet = true) }
            }
            View.Upsell -> {
                add(text = "Frequently bought together", b = Bounds(10, 500, 700, 540))
                card(Item("Choco Cream Biscuits", "100 g", 30), "100 g", 30, 10, 600, inSheet = true)
                card(Item("Salted Butter", "100 g", 58), "100 g", 58, 350, 600, inSheet = true)
            }
            View.Checkout -> if (realShapes) realCheckout(::add, width, height) else {
                add(desc = "Checkout", rid = "title", b = Bounds(10, 40, 400, 100))
                val n = cart.values.sum()
                if (n > 0) add(desc = "Shipment of $n item${if (n == 1) "" else "s"}", b = Bounds(10, 120, 600, 160))
                val top = (if (landscape) 850 else 200) - scrolled * 400
                var y = top
                for ((k, q) in cart.toList()) {
                    val item = catalog.first { it.name == k.name }
                    val rowY = y
                    add(desc = "${k.name} is available for ₹${price(k)}, ${k.size}", cls = "ViewGroup", b = Bounds(0, rowY, width, rowY + 180))
                    add(desc = "Decrease quantity", rid = "icon_decrement", clickable = true, b = Bounds(700, rowY + 60, 780, rowY + 120)) {
                        if (landscape && rowY + 90 in 930..1130) coveredTaps++ // a real tap there lands on the order bar
                        if (q <= 1) cart.remove(k) else cart[k] = q - 1; true
                    }
                    add(desc = "quantity $q", rid = "tv_title", b = Bounds(780, rowY + 60, 860, rowY + 120))
                    add(desc = "Increase quantity", rid = "icon_increment", clickable = true, b = Bounds(860, rowY + 60, 940, rowY + 120)) {
                        addTaps += "+ ${item.name} ${k.size}"; cart[k] = q + 1; true
                    }
                    y += 200
                }
                if (cart.isEmpty()) add(text = "Your cart is empty", b = Bounds(10, 300, width, 360))
                add(text = "You might also like", b = Bounds(10, y + 20, 600, y + 60))
                card(Item("Choco Cream Biscuits", "100 g", 30), "100 g", 30, 10, y + 80, inSheet = true)
                if (cart.isNotEmpty()) {
                    if (!addressSet) {
                        add(text = "Add address to proceed", rid = "cv_checkout_container", clickable = true, b = Bounds(0, height - 200, width, height - 60)) { true }
                    } else {
                        val label = if (paymentSaved) "Place Order" else "Select Payment Method"
                        val barTop = if (landscape) 930 else height - 200
                        val barBottom = if (landscape) 1130 else height - 60
                        add(text = "₹$total · TOTAL · $label", rid = "cv_checkout_container", clickable = true, b = Bounds(0, barTop, width, barBottom)) {
                            orders += cart.mapKeys { "${it.key.name} ${it.key.size}" }
                            when {
                                pinAfterOrder -> { pkg = "com.phonepe.app"; view = View.Payment }
                                paymentSaved -> { cart.clear(); view = View.Placed }
                                else -> view = View.Payment
                            }
                            true
                        }
                    }
                }
            }
            View.Payment -> {
                if (pinAfterOrder) {
                    add(text = "Enter UPI PIN", b = Bounds(0, 300, width, 360))
                } else {
                    add(text = "Select payment method", b = Bounds(0, 100, width, 160))
                    add(text = "UPI", clickable = true, b = Bounds(0, 200, width, 280)) { true }
                    add(text = "Cash on delivery", clickable = true, b = Bounds(0, 300, width, 380)) { true }
                }
            }
            View.Placed -> add(text = "Order placed", b = Bounds(0, 300, width, 360))
        }
        return Built(Screen(pkg, els, Normalizer.screenHash(pkg, els)), handlers)
    }

    private fun realCheckout(
        add: (String, String, String, String, Boolean, Boolean, Bounds, (() -> Boolean)?) -> Unit,
        width: Int,
        height: Int,
    ) {
        fun el(text: String = "", desc: String = "", rid: String = "", cls: String = "View", clickable: Boolean = false, b: Bounds, onTap: (() -> Boolean)? = null) =
            add(text, desc, rid, cls, clickable, false, b, onTap)
        if (deviceChrome) {
            el(desc = "Navigate up", rid = "back", cls = "ImageButton", clickable = true, b = Bounds(0, 40, 80, 100)) { view = lastResults ?: View.Home; true }
            el(desc = "Search", rid = "search", cls = "ImageButton", clickable = true, b = Bounds(800, 40, 880, 100)) { true }
            el(text = "\uD3A9 Share", rid = "share", clickable = true, b = Bounds(880, 40, 1070, 100)) { true }
        }
        el(desc = "Checkout", rid = "title", b = Bounds(10, 40, 400, 100))
        el(text = "Delivering to Home", rid = "header_title", b = Bounds(10, 110, 700, 150))
        el(text = address, rid = "header_subtitle", b = Bounds(10, 150, 900, 190))
        if (deviceChrome) el(text = "Change", rid = "change_button", clickable = true, b = Bounds(900, 110, 1070, 190)) { true }
        var y = 220
        outOfStockInCart?.let { (title, subtitle) ->
            el(desc = "1 item is not in stock", rid = "title", b = Bounds(10, y, 700, y + 40))
            // On the device the row is a clickable container without a label of its own.
            if (deviceChrome) el(cls = "ViewGroup", clickable = true, b = Bounds(0, y + 45, width, y + 125)) { true }
            el(text = title, rid = "item_title", b = Bounds(10, y + 50, 700, y + 90))
            el(text = subtitle, rid = "item_subtitle", b = Bounds(10, y + 90, 700, y + 120))
            el(text = "Move to wishlist", rid = "promo_offer_tv", clickable = true, b = Bounds(700, y + 50, 1000, y + 110)) {
                wishlist += title; outOfStockInCart = null; true
            }
            el(text = "Add these items instead", b = Bounds(10, y + 130, 700, y + 170))
            var x = 10
            for (s in suggestions) {
                val k = Key(s.name, s.size)
                el(desc = "${s.name} quantity ${s.size} ₹${s.price} rupees", cls = "ViewGroup", b = Bounds(x, y + 180, x + 320, y + 480))
                el(text = s.name, rid = "tv_name", b = Bounds(x + 10, y + 190, x + 310, y + 240))
                el(text = s.size, rid = "tv_variant", b = Bounds(x + 10, y + 250, x + 200, y + 280))
                el(text = "₹${s.price}", rid = "tv_price", b = Bounds(x + 10, y + 290, x + 150, y + 320))
                if ((cart[k] ?: 0) == 0) {
                    el(desc = "ADD", rid = "tv_title", clickable = true, b = Bounds(x + 180, y + 400, x + 310, y + 460)) {
                        addTaps += "${s.name} ${s.size}"; cart[k] = 1; true
                    }
                }
                x += 340
            }
            y += 500
        }
        val n = cart.values.sum()
        if (n > 0) el(desc = "Shipment of $n item${if (n == 1) "" else "s"}", rid = "subtitle", b = Bounds(10, y, 600, y + 40))
        y += 60
        for ((k, q) in cart.toList()) {
            val rowY = y
            val p = runCatching { price(k) }.getOrElse { suggestions.first { it.name == k.name }.price }
            el(desc = "${k.name} is available for ₹$p", cls = "ViewGroup", b = Bounds(0, rowY, width, rowY + 180))
            el(text = k.name, rid = "tv_name", b = Bounds(10, rowY + 10, 600, rowY + 50))
            el(text = k.size, rid = "tv_variant", b = Bounds(10, rowY + 60, 300, rowY + 90))
            el(desc = "Decrease quantity", rid = "icon_decrement", clickable = true, b = Bounds(700, rowY + 60, 780, rowY + 120)) {
                if (q <= 1) cart.remove(k) else cart[k] = q - 1; true
            }
            el(desc = "quantity $q", rid = "tv_title", clickable = true, b = Bounds(780, rowY + 60, 860, rowY + 120))
            el(desc = "Increase quantity", rid = "icon_increment", clickable = true, b = Bounds(860, rowY + 60, 940, rowY + 120)) {
                addTaps += "+ ${k.name} ${k.size}"; cart[k] = q + 1; true
            }
            el(text = "₹${p * q}", rid = "total_item_price", b = Bounds(950, rowY + 60, 1070, rowY + 120))
            el(text = "Move to wishlist", rid = "promo_offer_tv", clickable = true, b = Bounds(10, rowY + 120, 300, rowY + 170)) {
                wishlist += k.name; cart.remove(k); true
            }
            y += 200
        }
        el(text = "You might also like", b = Bounds(10, y + 20, 600, y + 60))
        val up = Item("Choco Cream Biscuits", "100 g", 30)
        el(desc = "${up.name} is available for ₹${up.price}, ${up.size}", cls = "ViewGroup", b = Bounds(10, y + 80, 330, y + 680))
        el(desc = "ADD", rid = "tv_title", clickable = true, b = Bounds(190, y + 500, 330, y + 550)) { addTaps += "${up.name} ${up.size}"; true }
        if (cart.isNotEmpty()) {
            val label = if (paymentSaved) "Place Order" else "Select Payment Method"
            el(text = label, rid = "tv_action_text", clickable = true, b = Bounds(0, height - 200, width, height - 60)) {
                if (paymentSaved) { orders += cart.mapKeys { "${it.key.name} ${it.key.size}" }; cart.clear(); view = View.Placed } else view = View.Payment
                true
            }
        }
    }

    fun render(): Screen = build().screen

    // -- Actuator -------------------------------------------------------------------------------------

    private var lastBuilt: Built? = null

    override suspend fun snapshot(): Screen {
        if (pkg == Apps.BLINKIT && !online) view = View.Offline
        if (pkg == Apps.BLINKIT && !loggedIn) view = View.Login
        return build().also { lastBuilt = it }.screen
    }

    override suspend fun perform(action: Action, screen: Screen): Boolean {
        val built = lastBuilt
        check(built != null && screen.hash == built.screen.hash) { "acted on a stale screen" }
        performed += action
        when (action) {
            is OpenApp -> { pkg = Apps.APP_ALIASES.getValue(action.app); return true }
            is Search -> {
                pkg = Apps.BLINKIT
                if (deepLinkWorks) { view = View.Results(action.query); upsellShown = false }
                return true
            }
            is Back -> {
                view = when (view) {
                    is View.Sizes, View.Upsell -> (lastResults ?: View.Home)
                    View.Checkout -> lastResults ?: View.Home
                    else -> View.Home
                }
                return true
            }
            is Home -> { pkg = "launcher"; return true }
            is Scroll -> { if (action.direction == Direction.DOWN && view == View.Checkout) scrolled++; return true }
            is Tap -> {
                val h = built.handlers[action.index] ?: return false
                val before = view
                val ok = h()
                if (before is View.Results) lastResults = before
                return ok
            }
            is TypeText -> {
                val e = screen.elements[action.index ?: return false]
                if (view != View.SearchBox || e.desc != Blinkit.HIDDEN_FIELD) return false
                typed = action.text
                // The service presses Enter after typing into Blinkit's search box (device.py port).
                view = View.Results(action.text)
                return true
            }
            else -> return false
        }
    }

    private var lastResults: View.Results? = null

    override suspend fun clearText(element: Element, screen: Screen): Boolean = false

    override suspend fun waitForChange(oldHash: String, timeoutMs: Long): Boolean = render().hash != oldHash
}
