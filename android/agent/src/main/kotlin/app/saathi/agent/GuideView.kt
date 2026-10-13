package app.saathi.agent

import app.saathi.safety.Apps
import app.saathi.safety.Element
import app.saathi.safety.SECRET_REGEX
import app.saathi.safety.Screen

/**
 * A product card as any shopping app shows it, verbatim (Blinkit shapes, seen on the owner's
 * tablet 12–13 Oct): a container whose desc is "<name> is available for ₹<price>[, <size>]"
 * (results, cart rows) or "<name> quantity <size> ₹<price> rupees" (checkout suggestions), with
 * children tv_name, tv_uom_title / tv_variant, price / tv_price / total_item_price, an "ADD"
 * button or a stepper ("Decrease quantity", "quantity N", "Increase quantity"), and optional
 * "Ad", "2 left", "Move to wishlist".
 */
data class GuideCard(
    val name: String,
    val size: String,
    val price: String,
    /** "quantity N" exactly as shown, or "" when not in the cart. */
    val qtyText: String,
    val qty: Int,
    val container: Element,
    val add: Element?,
    val minus: Element?,
    val plus: Element?,
    /** Other things shown in the card, verbatim ("Ad", "2 left", "Notify me"). */
    val extras: List<String>,
    /** Other buttons inside the card ("Move to wishlist"). */
    val actions: List<Element>,
    /** Indexes of every element inside the card (they are not listed again). */
    val inner: Set<Int>,
) {
    val label: String get() = listOf(name, size).filter { it.isNotEmpty() }.joinToString(" ")
}

/**
 * One line of the full screen list ("read everything"), and what it refers to. [chrome]: a
 * control the friendly summary leaves out (navigate up, share, voice search, hints, badges …).
 */
data class GuideItem(
    val spoken: String,
    val name: String,
    val element: Element?,
    val card: GuideCard? = null,
    val field: Boolean = false,
    val chrome: Boolean = false,
)

/** What kind of screen it is; the summary is shaped by it. */
enum class ScreenKind { LOADING, DIALOG, CHECKOUT, RESULTS, CHAT_LIST, CHAT, GENERIC }

/** A WhatsApp chat-list row: the name and "3 unread messages" (verbatim) when shown. */
data class ChatRow(val name: String, val unread: String?)

/**
 * What a screen looks like to the guide. Every quoted label is verbatim from the screen (hard
 * rule 4), cleaned only by dropping icon-font glyphs and repeated parts. Never contains: a
 * password field's text, private-looking text (codes, PINs, phone numbers), or the delivery
 * address.
 */
data class ScreenView(
    val packageName: String,
    val hash: String,
    val kind: ScreenKind,
    val title: String?,
    /** Everything in reading order (for "read everything" and for naming targets). */
    val items: List<GuideItem>,
    val cards: List<GuideCard>,
    /** "View cart · 2 items" (repeated parts said once); null when no cart bar. */
    val cartBadge: String?,
    val cartBar: Element?,
    /** "2 items" from the badge or "Shipment of 2 items" on the checkout screen. */
    val cartCount: String?,
    val loading: Boolean,
    /** The first out-of-stock item on a checkout screen (title, subtitle), verbatim. */
    val outOfStock: String?,
    val outOfStockItems: List<String>,
    /** "1 item is not in stock", when shown. */
    val outOfStockHeading: String?,
    /** What the app suggests instead of the out-of-stock item ("Add these items instead"). */
    val suggested: List<GuideCard>,
    /** "₹73" when the screen shows a total. */
    val total: String?,
    /** "Select Payment Method": the user chooses how to pay. */
    val payment: String?,
    /** "Place Order", verbatim, when that button shows. */
    val placeOrder: String?,
    /** What was searched for ("milk"), from the search box or "Results for …". */
    val query: String?,
    val dialogQuestion: String?,
    val dialogOptions: List<String>,
    val chats: List<ChatRow>,
    /** WhatsApp messages on screen (private-looking ones excluded). */
    val messages: List<Element>,
    /** What is typed in WhatsApp's message box (never private-looking text). */
    val typed: String,
    /** Elements that must never be read or targeted (passwords, private text, the address). */
    val hidden: Set<Int>,
) {
    val inCart: List<GuideCard> get() = cards.filter { it.qty > 0 }
}

/** What Saathi says, and the on-screen hint that goes with it (shown, never spoken). */
data class Spoken(val text: String, val hint: String? = null, val suggestions: List<String> = emptyList())

object GuideView {
    private val I = RegexOption.IGNORE_CASE
    private val CARD_A = Regex("^(.+?) is available for (₹[\\d.,]+)(?:, (.+))?$")
    private val CARD_B = Regex("^(.+?) quantity (.+?) (₹[\\d.,]+) rupees$")
    private val QTY = Regex("^quantity (\\d+)$", I)
    private val ADD = Regex("^(add|\\+|add to cart|जोड़ें)$", I)
    private val MINUS = Regex("^(decrease quantity|−|-|remove one)$", I)
    private val PLUS = Regex("^(increase quantity|\\+)$", I)
    private val NOT_IN_STOCK = Regex("^(\\d+) items? (is|are) not in stock$", I)
    private val DELIVERING = Regex("^delivering to\\b", I)
    private val PINCODE = Regex("(?<!\\d)\\d{6}(?!\\d)")
    private val ITEMS = Regex("^\\d+\\s+items?$", I)
    private val ITEMS_IN = Regex("\\d+\\s+items?", I)
    private val SHIPMENT = Regex("Shipment of \\d+ items?", I)
    private val SHIMMER = Regex("^shimmer", I)
    private val END_OOS = Regex("^(add these items instead|you might also like|similar products|bill details)$", I)
    private val OOS_ACTION = Regex("^(move to wishlist|remove|delete|notify me|view similar|see similar|similar products)$", I)
    private val SIZE_LIKE = Regex("\\d\\s*(ml|l|ltr|litre|liter|g|gm|kg|pc|pcs|pack|packs|unit|units|piece|pieces|x)\\b|•", I)
    private val STOCK = Regex("\\b\\d+ left\\b|out of stock|sold out|notify me|unavailable", I)
    private val RESULTS_FOR = Regex("^(?:showing )?results for \"?(.+?)\"?$", I)
    private val SEARCHY = Regex("search|खोज", I)
    private val PLACE = Regex("place order", I)
    private val TOTAL_INLINE = Regex("^(₹[\\d,.]+)\\s*·?\\s*TOTAL\\b", I)
    private val TOTAL_LABEL = Regex("^(grand total|to pay|bill total|total amount|total)\\s*:?\\s*(₹[\\d,.]+)?$", I)
    private val RUPEES = Regex("^₹[\\d,.]+$")
    private val TITLE_RIDS = setOf("title", "toolbar_title", "action_bar_title", WhatsApp.CHAT_TITLE)
    private val DIALOG_RIDS = setOf("alertTitle", "message", "dialog_title", "dialog_message")
    private const val MAX_TEXT = 100

    /** Screen controls a friend wouldn't read out (still available on "read everything"). */
    private val CHROME = Regex(
        "^(navigate up|up|back|go back|close|search|share|clear|clear all|clear search|clear text|voice search|voice search button|" +
            "mic|microphone|more options|menu|options|filter|filters|sort|sort by|scan|profile|account|wishlist|add to wishlist|" +
            "remove from wishlist|favourite|favorite|like|unlike|change|ad|ads|sponsored|bestseller|navigate back)$",
        I,
    )
    private val HINT = Regex("^(double[- ]tap|tap|swipe|long[- ]press)\\b", I)
    private val SEARCH_HINT = Regex("^search\\b|^(showing )?results for\\b", I)
    private val RATING = Regex("^[0-5](\\.\\d)?\\s*★?$|^\\(?[\\d.,]+\\s*[kKmM]?\\+?\\)?$|\\bratings?\\b|\\breviews?\\b|★", I)
    private val DELIVERY_TIME = Regex("^\\d+\\s*(mins?|minutes?|hrs?|hours?)$|^(delivery|delivering) (in|to)\\b|^\\d+\\s*mins?\\b", I)
    private val CHROME_RID = Regex("wishlist|heart|favou?rite|voice|toolbar_home|share|navigate", I)

    // -- cleaning ---------------------------------------------------------------------------------

    private fun isPrivateUse(cp: Int): Boolean = cp in 0xE000..0xF8FF || cp in 0xF0000..0xFFFFD || cp in 0x100000..0x10FFFD

    private fun mainScript(cp: Int): Boolean {
        if (!Character.isLetter(cp)) return false
        val s = Character.UnicodeScript.of(cp)
        return s == Character.UnicodeScript.LATIN || s == Character.UnicodeScript.DEVANAGARI
    }

    /** A token made only of letters / symbols from another script (e.g. an icon font's "펩"). */
    private fun foreignGlyph(token: String): Boolean {
        val cps = token.codePoints().toArray()
        if (cps.isEmpty() || cps.size > 2) return false
        return cps.all { cp ->
            val type = Character.getType(cp)
            (Character.isLetter(cp) && !mainScript(cp)) || type == Character.OTHER_SYMBOL.toInt()
        }
    }

    /**
     * Drop icon-font glyphs (private-use code points; a stray 1–2 character token from another
     * script next to a Latin / Devanagari label, like "펩 Share"), say repeated parts once
     * ("2 items, 2 items"), no empty clauses. Never adds or reorders words.
     */
    fun clean(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder()
        raw.codePoints().forEach { cp -> if (isPrivateUse(cp)) sb.append(' ') else sb.appendCodePoint(cp) }
        val tokens = sb.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val hasMain = tokens.any { t -> t.codePoints().anyMatch(::mainScript) }
        val kept = if (hasMain) tokens.filterNot(::foreignGlyph) else tokens
        val joined = kept.joinToString(" ").trim().trim(',', '·', '|', ';').trim()
        if (", " !in joined) return joined
        val parts = joined.split(", ").map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (p in parts) if (out.none { it.equals(p, ignoreCase = true) }) out += p
        return out.joinToString(", ")
    }

    fun label(e: Element): String = clean(e.text).ifEmpty { clean(e.desc) }.trim()

    /** Text and desc both, when they differ (for matching). */
    fun fullLabel(e: Element): String = listOf(clean(e.text), clean(e.desc)).filter { it.isNotBlank() }.distinct().joinToString(" ").trim()

    /** A control a friend wouldn't read out by default. */
    fun isChrome(e: Element, l: String = label(e)): Boolean {
        if (l.isEmpty()) return true
        if (CHROME.matches(l) || HINT.containsMatchIn(l) || SEARCH_HINT.containsMatchIn(l) || RATING.containsMatchIn(l)) return true
        if (DELIVERY_TIME.containsMatchIn(l) || DELIVERING.containsMatchIn(l)) return true
        if (CHROME_RID.containsMatchIn(e.rid) && l.split(" ").size <= 3) return true
        return false
    }

    private fun inside(e: Element, box: Element): Boolean {
        val (x, y) = e.center
        val (x1, y1, x2, y2) = box.bounds
        return x1 <= x && x <= x2 && y1 <= y && y <= y2
    }

    /** Placeholders while results load (Blinkit's shimmer_image / shimmer_title / button1 without labels). */
    fun loading(screen: Screen): Boolean {
        val shimmer = screen.elements.any { SHIMMER.containsMatchIn(it.rid) }
        val labelled = screen.elements.count { label(it).isNotEmpty() }
        return (shimmer && cards(screen).isEmpty()) || labelled == 0
    }

    fun cards(screen: Screen): List<GuideCard> {
        val out = mutableListOf<GuideCard>()
        val taken = mutableSetOf<Int>()
        for (e in screen.elements) {
            if (e.index in taken) continue
            val a = CARD_A.matchEntire(e.desc.ifEmpty { e.text })
            val b = if (a == null) CARD_B.matchEntire(e.desc.ifEmpty { e.text }) else null
            if (a == null && b == null) continue
            val inner = screen.elements.filter { it.index > e.index && it.index !in taken && inside(it, e) && CARD_A.matchEntire(it.desc) == null && CARD_B.matchEntire(it.desc) == null }
            fun byRid(vararg rids: String) = inner.firstOrNull { it.rid in rids }?.let(::label)?.takeIf { it.isNotEmpty() }
            val name = byRid("tv_name") ?: clean((a?.groupValues?.get(1) ?: b!!.groupValues[1]).trim())
            val size = byRid("tv_uom_title", "tv_variant") ?: clean((a?.groupValues?.get(3) ?: b!!.groupValues[2]).trim())
            val price = byRid("total_item_price", "price", "tv_price") ?: (a?.groupValues?.get(2) ?: b!!.groupValues[3])
            val qtyEl = inner.firstOrNull { QTY.matches(it.desc.trim()) || QTY.matches(it.text.trim()) }
            val qtyText = qtyEl?.let(::label).orEmpty()
            val qty = qtyText.let { QTY.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
            val add = inner.firstOrNull { ADD.matches(label(it)) && qtyEl == null }
            val minus = inner.firstOrNull { MINUS.matches(label(it)) }
            val plus = inner.firstOrNull { PLUS.matches(label(it)) && qtyEl != null }
            val used = setOfNotNull(add, minus, plus, qtyEl).map { it.index }.toSet()
            val known = setOf("tv_name", "tv_uom_title", "tv_variant", "total_item_price", "price", "tv_price")
            val rest = inner.filter { it.index !in used && it.rid !in known && label(it).isNotEmpty() && label(it) != name && label(it) != size && label(it) != price }
            val actions = rest.filter { it.clickable && !ADD.matches(label(it)) }
            val extras = rest.filter { !it.clickable }.map(::label).distinct()
            out += GuideCard(name, size, price, qtyText, qty, e, add, minus, plus, extras, actions, inner.map { it.index }.toSet())
            taken += e.index
            taken += inner.map { it.index }
        }
        return out
    }

    /** A card in full (for "read everything"): name, size, price, badges, ADD / quantity, buttons. */
    fun spokenCard(c: GuideCard): String {
        val state = when {
            c.qty > 0 -> c.qtyText
            c.add != null -> label(c.add)
            else -> ""
        }
        return (listOf(c.name, c.size, c.price) + c.extras + listOf(state) + c.actions.map(::label))
            .filter { it.isNotEmpty() }.distinct().joinToString(", ")
    }

    /** True when the card says it can't be bought now ("Notify me", "Out of stock"). */
    fun outOfStockCard(c: GuideCard): Boolean =
        (c.extras + c.actions.map(::label)).any { Regex("out of stock|sold out|notify me|unavailable", I).containsMatchIn(it) }

    /** A card the friendly way: name, size, price, stock note, how many are in the cart. */
    fun cardLine(c: GuideCard, t: GuideTexts): String {
        val stock = (c.extras + c.actions.map(::label)).filter { STOCK.containsMatchIn(it) }.map { if (Regex("notify me", I).matches(it)) t.outOfStockShort else it }
        val inCart = if (c.qty > 0) t.inCartQty(c.qty) else ""
        return (listOf(c.name, c.size, c.price) + stock + inCart).filter { it.isNotEmpty() }.distinct().joinToString(", ")
    }

    /** A cart row: name, size, quantity when more than one, price. */
    fun cartLine(c: GuideCard): String =
        listOf(c.name, c.size, if (c.qty > 1) c.qtyText else "", c.price).filter { it.isNotEmpty() }.distinct().joinToString(", ")

    private fun isAddress(screen: Screen, e: Element): Boolean {
        if ("address" in e.rid.lowercase()) return true
        // A postal address line: a 6-digit PIN code inside a longer, comma-separated text (not a bare code).
        val l = label(e)
        if (PINCODE.containsMatchIn(l) && "," in l && l.count { it.isLetter() } >= 6) return true
        val prev = screen.elements.getOrNull(e.index - 1) ?: return false
        return DELIVERING.containsMatchIn(label(prev)) && e.center.y >= prev.center.y && e.center.y - prev.center.y < 250
    }

    private fun clip(s: String): String = if (s.length <= MAX_TEXT) s else s.take(MAX_TEXT).trimEnd() + "…"

    private fun totalOf(els: List<Element>): String? {
        for (e in els) TOTAL_INLINE.find(label(e))?.let { return it.groupValues[1] }
        for (e in els) {
            val m = TOTAL_LABEL.matchEntire(label(e)) ?: continue
            m.groups[2]?.value?.let { return it }
            els.drop(e.index + 1).take(3).firstOrNull { RUPEES.matches(label(it)) }?.let { return label(it) }
        }
        return null
    }

    /** The out-of-stock items under "N item(s) not in stock": title (+ size line), verbatim. */
    private fun outOfStockItems(els: List<Element>, heading: Element, cards: List<GuideCard>, inCards: Set<Int>, hidden: Set<Int>): Pair<List<String>, Int> {
        val endMarker = els.firstOrNull { it.index > heading.index && (END_OOS.matches(label(it)) || SHIPMENT.containsMatchIn(label(it))) }?.index
        val firstOther = cards.filter { it.container.index > heading.index && (it.qty > 0 || it.add != null) }.minOfOrNull { it.container.index }
        val end = listOfNotNull(endMarker, firstOther).minOrNull() ?: els.size
        val names = mutableListOf<MutableList<String>>()
        val cardsHere = cards.filter { it.container.index in (heading.index + 1) until end }
        for (e in els) {
            if (e.index <= heading.index || e.index >= end || e.index in hidden) continue
            val c = cardsHere.firstOrNull { it.container.index == e.index }
            if (c != null) { names += mutableListOf(c.name, c.size).filter { it.isNotEmpty() }.toMutableList(); continue }
            if (e.index in inCards) continue
            val l = label(e)
            if (l.isEmpty() || OOS_ACTION.matches(l) || isChrome(e, l) || NOT_IN_STOCK.matches(l)) continue
            if (e.rid == "item_title" || names.isEmpty() || (!SIZE_LIKE.containsMatchIn(l) && e.rid != "item_subtitle")) names += mutableListOf(l)
            else if (names.last().size < 2) names.last() += l
        }
        return names.map { it.joinToString(", ") } to end
    }

    fun of(screen: Screen, appLabel: String, t: GuideTexts): ScreenView {
        val els = screen.elements
        val cards = cards(screen)
        val inCards = cards.flatMap { it.inner + it.container.index }.toSet()
        val hidden = mutableSetOf<Int>()
        val consumed = mutableSetOf<Int>()
        val items = mutableListOf<GuideItem>()

        // Title.
        val titleEl = WhatsApp.chatTitle(screen) ?: els.firstOrNull {
            it.rid in TITLE_RIDS && label(it).isNotEmpty() && it.index !in inCards && !NOT_IN_STOCK.matches(label(it)) && !SHIPMENT.containsMatchIn(label(it))
        }
        val title = titleEl?.let(::label)
        titleEl?.let { consumed += it.index }

        // Private things: never read, never targeted.
        for (e in els) {
            if (e.password) hidden += e.index
            else if (isAddress(screen, e)) hidden += e.index
        }

        // Dialog: the question and its buttons.
        val dialogParts = els.filter { it.rid in DIALOG_RIDS && label(it).isNotEmpty() && it.index !in hidden && !PrivateText.looksPrivate(label(it)) }
        val dialogQuestion = dialogParts.takeIf { it.isNotEmpty() }?.joinToString(" ") { clip(label(it)).let { s -> if (s.last() in ".?!।:") s else "$s." } }
        val dialogOptions = if (dialogQuestion == null) emptyList() else els.filter { it.rid.matches(Regex("button[123]")) && label(it).isNotEmpty() }.map(::label)

        // Out-of-stock block (checkout).
        val heading = els.firstOrNull { NOT_IN_STOCK.matches(label(it)) }
        var oos = emptyList<String>()
        var suggested = emptyList<GuideCard>()
        if (heading != null) {
            val (found, end) = outOfStockItems(els, heading, cards, inCards, hidden)
            oos = found
            val ship = els.firstOrNull { it.index > heading.index && SHIPMENT.containsMatchIn(label(it)) }?.index
            val upsell = els.firstOrNull { it.index > heading.index && Regex("^you might also like$", I).matches(label(it)) }?.index
            val stop = listOfNotNull(ship, upsell).minOrNull() ?: Int.MAX_VALUE
            suggested = cards.filter { it.qty == 0 && it.container.index >= end && it.container.index < stop }
        }

        // Cart badge ("View cart · 2 items" + "2 items": said once).
        val bar = els.firstOrNull { it.index !in inCards && "view cart" in fullLabel(it).lowercase() }
        var badge: String? = null
        var count: String? = null
        if (bar != null) {
            val qtyEl = els.firstOrNull { it.rid == "tv_quantity" && label(it).isNotEmpty() } ?: els.firstOrNull { it !== bar && ITEMS.matches(label(it)) && it.index !in inCards }
            val barText = label(bar)
            val qtyText = qtyEl?.let(::label)
            badge = if (qtyText != null && !barText.contains(qtyText, ignoreCase = true)) "$barText, $qtyText" else barText
            count = qtyText ?: ITEMS_IN.find(fullLabel(bar))?.value
            consumed += bar.index
            qtyEl?.let { consumed += it.index }
        }
        if (count == null) els.firstNotNullOfOrNull { SHIPMENT.find(label(it))?.value }?.let { count = ITEMS_IN.find(it)?.value }

        // Total, paying, searching.
        val total = totalOf(els)
        val payment = els.firstOrNull { it.clickable && GuideHooks.choosesPayment(it) }?.let(::label)
        val placeOrder = els.firstOrNull { it.clickable && PLACE.containsMatchIn(fullLabel(it)) }?.let { PLACE.find(fullLabel(it))!!.value }
            ?.let { v -> els.firstNotNullOfOrNull { e -> Regex("place order", I).find(fullLabel(e))?.value?.takeIf { e.clickable } } ?: v }
        val resultsFor = els.firstNotNullOfOrNull { RESULTS_FOR.matchEntire(label(it))?.groupValues?.get(1) }
        val searchBox = els.firstOrNull { e ->
            e.editable && !e.password && e.text.isNotBlank() && !e.text.trim().startsWith("search", ignoreCase = true) &&
                (SEARCHY.containsMatchIn("${e.desc} ${e.rid}") || e.desc == Blinkit.HIDDEN_FIELD) && !PrivateText.looksPrivate(e.text)
        }
        val query = resultsFor ?: searchBox?.text?.trim()?.let(::clean)?.takeIf { it.isNotEmpty() }

        // WhatsApp.
        val rows = if (screen.packageName == WhatsApp.PKG) els.filter { it.rid == WhatsApp.ROW_NAME && label(it).isNotEmpty() } else emptyList()
        val chats = rows.mapIndexed { i, r ->
            val next = rows.getOrNull(i + 1)?.index ?: Int.MAX_VALUE
            val unread = els.firstOrNull { it.rid == WhatsApp.ROW_COUNT && it.index > r.index && it.index < next }?.let(::label)?.takeIf { it.isNotEmpty() }
            ChatRow(label(r), unread)
        }
        val messages = if (screen.packageName == WhatsApp.PKG) els.filter { it.rid == WhatsApp.MESSAGE && label(it).isNotEmpty() && !PrivateText.looksPrivate(label(it)) } else emptyList()
        val typed = if (screen.packageName == WhatsApp.PKG) WhatsApp.typedText(screen).takeUnless { PrivateText.looksPrivate(it) }.orEmpty() else ""

        // Everything, in reading order.
        val seen = mutableSetOf<String>()
        val cardByContainer = cards.associateBy { it.container.index }
        bar?.let { items += GuideItem(t.cartBadge(badge!!), label(it), it) }
        for (e in els) {
            if (e.index in consumed || SHIMMER.containsMatchIn(e.rid)) continue
            val c = cardByContainer[e.index]
            if (c != null) {
                items += GuideItem(spokenCard(c), c.name, c.container, c)
                continue
            }
            if (e.index in inCards) continue
            if (e.index in hidden) {
                if (e.password) items += GuideItem(t.passwordField, "", null, field = true)
                else if (isAddress(screen, e) && seen.add("\u0000address")) items += GuideItem(t.savedAddress, "", null, chrome = true)
                continue
            }
            val l = label(e)
            if (e.editable) {
                val whatsappBox = screen.packageName == WhatsApp.PKG && e.rid == WhatsApp.ENTRY
                val name = clean((if (whatsappBox) e.desc.ifEmpty { "Message" } else e.desc.ifEmpty { e.rid.replace('_', ' ') }).trim())
                // WhatsApp shows its "Message" placeholder as the empty box's text: that isn't typed text.
                val typedHere = if (whatsappBox) WhatsApp.typedText(screen) else clean(e.text)
                val secret = SECRET_REGEX.matcher(name).find() || PrivateText.looksPrivate(typedHere)
                if (secret) {
                    hidden += e.index
                    items += GuideItem(t.codeField, name, null, field = true)
                    continue
                }
                val spoken = when {
                    typedHere.isNotEmpty() && typedHere != name && name.isNotEmpty() -> t.fieldHas(name, clip(typedHere))
                    typedHere.isNotEmpty() && typedHere != name -> t.fieldHas(t.emptyField, clip(typedHere))
                    name.isNotEmpty() -> t.field(name)
                    else -> t.emptyField
                }
                items += GuideItem(spoken, name.ifEmpty { typedHere }, e, field = true, chrome = SEARCHY.containsMatchIn("$name ${e.rid}"))
                continue
            }
            if (l.isEmpty()) continue
            if (PrivateText.looksPrivate(l)) {
                hidden += e.index
                items += GuideItem(t.codeText, "", null)
                continue
            }
            if (!seen.add(l.lowercase()) && !e.clickable) continue
            items += GuideItem(clip(l), l, e, chrome = isChrome(e, l))
        }

        val loadingNow = loading(screen)
        val isCheckout = title.equals("Checkout", true) || heading != null || els.any { SHIPMENT.containsMatchIn(label(it)) } ||
            ((payment != null || placeOrder != null) && cards.any { it.qty > 0 })
        val kind = when {
            dialogQuestion != null -> ScreenKind.DIALOG
            loadingNow && cards.isEmpty() && rows.isEmpty() -> ScreenKind.LOADING
            WhatsApp.chatTitle(screen) != null -> ScreenKind.CHAT
            rows.isNotEmpty() -> ScreenKind.CHAT_LIST
            isCheckout -> ScreenKind.CHECKOUT
            cards.isNotEmpty() -> ScreenKind.RESULTS
            else -> ScreenKind.GENERIC
        }
        return ScreenView(
            packageName = screen.packageName,
            hash = screen.hash,
            kind = kind,
            title = title,
            items = items,
            cards = cards,
            cartBadge = badge,
            cartBar = bar,
            cartCount = count,
            loading = loadingNow,
            outOfStock = oos.firstOrNull(),
            outOfStockItems = oos,
            outOfStockHeading = heading?.let(::label),
            suggested = suggested,
            total = total,
            payment = payment,
            placeOrder = placeOrder,
            query = query,
            dialogQuestion = dialogQuestion,
            dialogOptions = dialogOptions,
            chats = chats,
            messages = messages,
            typed = typed,
            hidden = hidden,
        )
    }

    // -- the friendly summary ---------------------------------------------------------------------

    const val CARDS = 3
    const val GENERIC_ITEMS = 4
    const val CART_LINES = 4
    const val PAGE = 6

    /** What a friend would say about this screen: content first, chrome left out, one question at the end. */
    fun summary(v: ScreenView, app: String, t: GuideTexts): Spoken = when (v.kind) {
        ScreenKind.LOADING -> Spoken(t.loading)
        ScreenKind.DIALOG -> dialog(v, app, t)
        ScreenKind.CHECKOUT -> checkout(v, app, t)
        ScreenKind.RESULTS -> results(v, app, t)
        ScreenKind.CHAT_LIST -> chatList(v, t)
        ScreenKind.CHAT -> chat(v, t)
        ScreenKind.GENERIC -> generic(v, app, t)
    }

    private fun spoken(parts: List<String?>, suggestions: List<String>, t: GuideTexts, hint: String? = null): Spoken {
        val all = parts.filterNotNull().filter { it.isNotBlank() } + listOfNotNull(t.couldSay(suggestions))
        return Spoken(all.joinToString(" "), hint, suggestions)
    }

    private fun results(v: ScreenView, app: String, t: GuideTexts): Spoken {
        val shown = v.cards.take(CARDS)
        val head = when {
            v.query != null -> t.foundFor(v.query)
            v.title != null -> t.inPlace(app, v.title)
            else -> t.found(app)
        }
        val left = v.cards.size - shown.size
        val sugg = buildList {
            val addable = v.cards.firstOrNull { it.qty == 0 && it.add != null }
            addable?.let { add(t.sayAdd(shortName(it, v.cards))) }
            if (v.cartBar != null) add(t.sayOpenCart)
            else v.cards.firstOrNull { it.qty == 0 && it.add != null && it !== addable }?.let { add(t.sayAdd(shortName(it, v.cards))) }
        }
        return spoken(
            listOf(
                "$head ${t.joinCards(shown.map { cardLine(it, t) })}${t.stop}",
                if (left > 0) t.andMore(left) else null,
                v.cartCount?.let { t.cartHas(it) },
                t.wantAdd,
            ),
            sugg, t, if (left > 0) t.moreHint else null,
        )
    }

    /** The word the out-of-stock item and every suggestion share ("Milk"), lowercased, or null. */
    private fun sharedWord(item: String, suggested: List<GuideCard>): String? {
        val words = item.substringBefore(", ").split(" ").filter { w -> w.length >= 3 && w.all { it.isLetter() } }.reversed()
        return words.firstOrNull { w -> suggested.all { s -> s.name.split(" ").any { it.equals(w, ignoreCase = true) } } }?.lowercase()
    }

    private fun checkout(v: ScreenView, app: String, t: GuideTexts): Spoken {
        val parts = mutableListOf<String?>(t.atCheckout)
        if (v.outOfStockHeading != null) {
            val spokenItems = v.outOfStockItems.map { it.replace(" • ", ", ") }
            parts += when (spokenItems.size) {
                0 -> t.oosUnnamed
                1 -> t.oosOne(spokenItems[0])
                else -> t.oosMany(spokenItems.size, t.joinAnd(spokenItems))
            }
            if (v.suggested.isNotEmpty()) parts += t.suggesting(app, v.outOfStock?.let { sharedWord(it, v.suggested) })
        }
        val cart = v.inCart
        if (cart.isNotEmpty()) {
            val lines = cart.take(CART_LINES).map(::cartLine)
            parts += t.inYourCart(lines, cart.size - lines.size)
        } else if (v.outOfStockHeading == null) parts += t.cartEmpty
        v.total?.let { parts += t.totalIs(it) }
        when {
            v.payment != null -> parts += t.payYourself
            v.placeOrder != null && cart.isNotEmpty() -> parts += t.placeWhenReady
        }
        parts += t.checkoutAsk
        return spoken(parts, emptyList(), t)
    }

    private fun chatList(v: ScreenView, t: GuideTexts): Spoken {
        val unread = v.chats.filter { it.unread != null }
        val first = unread.firstOrNull() ?: v.chats.firstOrNull()
        val parts = if (unread.isNotEmpty()) {
            listOf(t.inChats, t.unreadChats(unread.size, t.joinAnd(unread.take(4).map { "${it.name}, ${it.unread}" })))
        } else {
            listOf(t.inChats, t.noUnread, t.chatsInclude(t.joinAnd(v.chats.take(3).map { it.name })))
        }
        return spoken(parts + t.wantOpen, listOfNotNull(first?.let { t.sayOpen(it.name) }), t)
    }

    private fun chat(v: ScreenView, t: GuideTexts): Spoken {
        val parts = listOf(
            t.chatWith(v.title ?: ""),
            t.messagesOnScreen(v.messages.size),
            if (v.typed.isNotEmpty()) t.boxHas(clip(v.typed)) else null,
            t.chatAsk,
        )
        return spoken(parts, if (v.messages.isNotEmpty()) listOf(t.sayReadMessages) else emptyList(), t)
    }

    private fun dialog(v: ScreenView, app: String, t: GuideTexts): Spoken =
        spoken(listOf(t.asking(app, v.dialogQuestion ?: ""), if (v.dialogOptions.isEmpty()) null else t.choices(t.joinAnd(v.dialogOptions)), t.whichOne), v.dialogOptions.take(2).map(t::sayTap), t)

    /** The non-chrome items a friend would mention, in reading order. */
    fun content(v: ScreenView): List<GuideItem> = v.items.filter { !it.chrome && it.spoken.isNotEmpty() && it.element !== v.cartBar }

    private fun generic(v: ScreenView, app: String, t: GuideTexts): Spoken {
        // Don't say the screen's title (e.g. a store's slogan) a second time as an item.
        val content = content(v).filter { v.title.isNullOrBlank() || !it.spoken.equals(v.title, ignoreCase = true) }
        val shown = content.take(GENERIC_ITEMS)
        val left = content.size - shown.size
        val order = v.packageName in Apps.ORDER_APPS
        // A shop's home slogan isn't a place: "You're in Blinkit." reads better than the tagline.
        val parts = mutableListOf<String?>(t.youreIn(app, if (order) null else v.title))
        v.cartCount?.let { parts += t.cartHas(it) }
        parts += if (shown.isEmpty()) t.nothingVisible else t.iCanSee(t.joinAnd(shown.map { it.spoken }))
        if (left > 0) parts += t.andMore(left)
        parts += if (order) t.whatToBuy else t.whatNext
        val sugg = buildList {
            // In a shop, a friend suggests searching, not tapping a banner.
            if (order) add(t.saySearch("milk"))
            else shown.firstOrNull { it.element?.clickable == true && it.card == null && !it.field && it.name.isNotEmpty() && it.name.length <= 30 }?.let { add(t.sayTap(it.name)) }
            if (v.cartBar != null) add(t.sayOpenCart)
        }.take(2)
        return spoken(parts, sugg, t, if (left > 0) t.moreHint else null)
    }

    /** "read more" after the summary: the next cards / items. Page 1 is the first page after the summary. */
    fun more(v: ScreenView, app: String, t: GuideTexts, page: Int): Spoken {
        if (v.kind == ScreenKind.RESULTS) {
            val from = CARDS + (page - 1) * CARDS
            val slice = v.cards.drop(from).take(CARDS)
            if (slice.isEmpty()) return Spoken("${t.noMore} ${t.whatNext}")
            val left = v.cards.size - from - slice.size
            return spoken(listOf("${t.alsoHere} ${t.joinCards(slice.map { cardLine(it, t) })}${t.stop}", if (left > 0) t.andMore(left) else null, t.wantAdd), emptyList(), t, if (left > 0) t.moreHint else null)
        }
        val content = content(v)
        val skip = if (v.kind == ScreenKind.GENERIC) GENERIC_ITEMS else 0
        val from = skip + (page - 1) * PAGE
        val slice = content.drop(from).take(PAGE)
        if (slice.isEmpty()) return Spoken("${t.noMore} ${t.whatNext}")
        val left = content.size - from - slice.size
        return spoken(listOf(t.iCanSee(t.joinAnd(slice.map { it.spoken })), if (left > 0) t.andMore(left) else null, t.whatNext), emptyList(), t, if (left > 0) t.moreHint else null)
    }

    /** "read everything" / "what buttons are there": every line, chrome included, a page at a time. */
    fun everything(v: ScreenView, app: String, t: GuideTexts, page: Int = 0, size: Int = PAGE): Spoken {
        val parts = mutableListOf<String>()
        if (page == 0) {
            parts += t.on(app, v.title)
            if (v.loading && v.items.isEmpty()) parts += t.loading
        }
        val from = page * size
        val slice = v.items.drop(from).take(size)
        var hint: String? = null
        if (slice.isEmpty()) {
            parts += if (page == 0) t.nothingVisible else t.noMore
        } else {
            parts += "${t.iSee} ${slice.joinToString("; ") { it.spoken }}."
            val left = v.items.size - from - slice.size
            if (left > 0) { parts += t.andMore(left); hint = t.moreHint }
        }
        parts += t.whatNext
        return Spoken(parts.joinToString(" "), hint)
    }

    /** The shortest start of the card's name (2+ words) that names only this card here. */
    fun shortName(c: GuideCard, cards: List<GuideCard>): String {
        val words = c.name.split(" ").filter { it.isNotEmpty() }
        if (words.size <= 2) return c.name
        val targets = cards.map { GuideTarget(it.label, it.name, it.size, it.container, it) }
        for (n in 2 until words.size) {
            val prefix = words.take(n).joinToString(" ")
            val r = GuideTargets.resolve(prefix, targets)
            if (r is Resolved.One && r.target.card === c) return prefix
        }
        return c.name
    }

    // -- after an action ------------------------------------------------------------------------

    private fun same(a: GuideCard, b: GuideCard) = a.name == b.name && a.size == b.size

    private fun samePlace(before: ScreenView, after: ScreenView) = before.packageName == after.packageName && before.title == after.title

    /** What changed in the cart / on the cards, said first after an action ("Amul Buffalo A2 Milk is gone."). */
    fun changes(before: ScreenView, after: ScreenView, t: GuideTexts): List<String> {
        if (!samePlace(before, after)) return emptyList()
        val out = mutableListOf<String>()
        for (c in after.cards) {
            val old = before.cards.firstOrNull { same(it, c) } ?: continue
            if (old.qty == c.qty) continue
            out += when {
                old.qty == 0 -> t.nowInCart(c.name, c.qty, c.qtyText)
                c.qty == 0 -> t.gone(c.name)
                else -> t.qtyNow(c.name, c.qtyText)
            }
        }
        for (c in before.cards) if (c.qty > 0 && after.cards.none { same(it, c) }) out += t.gone(c.name)
        for (o in before.outOfStockItems) if (o !in after.outOfStockItems) out += t.gone(o.substringBefore(", "))
        return out.distinct()
    }

    /** The cart after an action: on checkout its items; elsewhere the new count, if it changed. */
    fun cartAfter(before: ScreenView, after: ScreenView, t: GuideTexts): String? {
        if (after.kind == ScreenKind.CHECKOUT && samePlace(before, after)) {
            val cart = after.inCart
            if (cart.isEmpty()) return t.cartEmptyNow
            val lines = cart.take(CART_LINES).map(::cartLine)
            return t.cartNow(after.cartCount ?: t.itemsCount(cart.sumOf { it.qty }), lines, cart.size - lines.size)
        }
        if (after.cartCount != before.cartCount) {
            after.cartCount?.let { return t.cartNowCount(it) }
            if (before.cartBar != null && after.cartBar == null && samePlace(before, after)) return t.cartEmptyNow
        }
        return null
    }

    /** True when the screen is a different place (another app or title, other cards): describe it again. */
    fun isNewPlace(before: ScreenView, after: ScreenView): Boolean =
        before.packageName != after.packageName || before.title != after.title || before.kind != after.kind ||
            (before.cards.map { it.label }.toSet() != after.cards.map { it.label }.toSet() && after.cards.isNotEmpty() && after.kind == ScreenKind.RESULTS)
}
