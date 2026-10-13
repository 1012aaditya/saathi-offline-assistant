package app.saathi.agent

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

/** One line of a screen description, and what it refers to. */
data class GuideItem(val spoken: String, val name: String, val element: Element?, val card: GuideCard? = null, val field: Boolean = false)

/**
 * What a screen looks like to the guide: title, highlights (dialog, out-of-stock block, cart
 * badge), and items in reading order. Every quoted label is verbatim from the screen (hard
 * rule 4). Never contains: a password field's text, private-looking text (codes, PINs, phone
 * numbers), or the delivery address (said as "your saved address").
 */
data class ScreenView(
    val packageName: String,
    val hash: String,
    val title: String?,
    val highlights: List<String>,
    val items: List<GuideItem>,
    val cards: List<GuideCard>,
    /** "View cart · 2 items" / "View cart, 2 items", verbatim parts joined; null when no cart bar. */
    val cartBadge: String?,
    val cartBar: Element?,
    /** "2 items" from the badge or "Shipment of 2 items" on the checkout screen. */
    val cartCount: String?,
    val loading: Boolean,
    /** Out-of-stock block on a checkout screen: the item (title + subtitle), verbatim. */
    val outOfStock: String?,
    /** Elements that must never be read or targeted (passwords, private text, the address). */
    val hidden: Set<Int>,
)

object GuideView {
    private val CARD_A = Regex("^(.+?) is available for (₹[\\d.,]+)(?:, (.+))?$")
    private val CARD_B = Regex("^(.+?) quantity (.+?) (₹[\\d.,]+) rupees$")
    private val QTY = Regex("^quantity (\\d+)$", RegexOption.IGNORE_CASE)
    private val ADD = Regex("^(add|\\+|add to cart|जोड़ें)$", RegexOption.IGNORE_CASE)
    private val MINUS = Regex("^(decrease quantity|−|-|remove one)$", RegexOption.IGNORE_CASE)
    private val PLUS = Regex("^(increase quantity|\\+)$", RegexOption.IGNORE_CASE)
    private val NOT_IN_STOCK = Regex("^\\d+ items? (is|are) not in stock$", RegexOption.IGNORE_CASE)
    private val DELIVERING = Regex("^delivering to\\b", RegexOption.IGNORE_CASE)
    private val PINCODE = Regex("(?<!\\d)\\d{6}(?!\\d)")
    private val ITEMS = Regex("^\\d+\\s+items?$", RegexOption.IGNORE_CASE)
    private val SHIPMENT = Regex("Shipment of \\d+ items?", RegexOption.IGNORE_CASE)
    private val SHIMMER = Regex("^shimmer", RegexOption.IGNORE_CASE)
    private val TITLE_RIDS = setOf("title", "toolbar_title", "action_bar_title", WhatsApp.CHAT_TITLE)
    private val DIALOG_RIDS = setOf("alertTitle", "message", "dialog_title", "dialog_message")
    private const val MAX_TEXT = 100

    fun label(e: Element): String = e.text.ifEmpty { e.desc }.trim()

    /** Text and desc both, when they differ (for matching). */
    fun fullLabel(e: Element): String = listOf(e.text, e.desc).filter { it.isNotBlank() }.distinct().joinToString(" ").trim()

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
            val name = byRid("tv_name") ?: (a?.groupValues?.get(1) ?: b!!.groupValues[1]).trim()
            val size = byRid("tv_uom_title", "tv_variant") ?: (a?.groupValues?.get(3) ?: b!!.groupValues[2]).trim()
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

    fun spokenCard(c: GuideCard, t: GuideTexts? = null): String {
        val state = when {
            c.qty > 0 -> c.qtyText
            c.add != null -> label(c.add)
            else -> ""
        }
        return (listOf(c.name, c.size, c.price) + c.extras + listOf(state) + c.actions.map(::label))
            .filter { it.isNotEmpty() }.distinct().joinToString(", ")
    }

    private fun isAddress(screen: Screen, e: Element): Boolean {
        if ("address" in e.rid.lowercase()) return true
        // A postal address line: a 6-digit PIN code inside a longer, comma-separated text (not a bare code).
        val l = label(e)
        if (PINCODE.containsMatchIn(l) && "," in l && l.count { it.isLetter() } >= 6) return true
        val prev = screen.elements.getOrNull(e.index - 1) ?: return false
        return DELIVERING.containsMatchIn(label(prev)) && e.center.y >= prev.center.y && e.center.y - prev.center.y < 250
    }

    private fun clip(s: String): String = if (s.length <= MAX_TEXT) s else s.take(MAX_TEXT).trimEnd() + "…"

    fun of(screen: Screen, appLabel: String, t: GuideTexts): ScreenView {
        val cards = cards(screen)
        val inCards = cards.flatMap { it.inner + it.container.index }.toSet()
        val hidden = mutableSetOf<Int>()
        val consumed = mutableSetOf<Int>()
        val highlights = mutableListOf<String>()
        val items = mutableListOf<GuideItem>()
        val els = screen.elements

        // Title.
        val titleEl = WhatsApp.chatTitle(screen) ?: els.firstOrNull { it.rid in TITLE_RIDS && label(it).isNotEmpty() && it.index !in inCards }
        val title = titleEl?.let(::label)
        titleEl?.let { consumed += it.index }

        // Private things: never read, never targeted.
        for (e in els) {
            if (e.password) hidden += e.index
            else if (isAddress(screen, e)) hidden += e.index
        }

        // Dialog.
        val dialogParts = els.filter { it.rid in DIALOG_RIDS && label(it).isNotEmpty() && it.index !in hidden }
        if (dialogParts.isNotEmpty()) {
            val buttons = els.filter { it.rid.matches(Regex("button[123]")) && label(it).isNotEmpty() }
            highlights += t.dialog((dialogParts.map { clip(label(it)) } + buttons.map(::label)).joinToString(". "))
            consumed += dialogParts.map { it.index }
        }

        // Out-of-stock block (checkout): heading, then the item's title and subtitle.
        var outOfStock: String? = null
        els.firstOrNull { NOT_IN_STOCK.matches(label(it)) }?.let { h ->
            val after = els.filter { it.index > h.index && it.index !in inCards }
            val stop = after.indexOfFirst { it.clickable || label(it).equals("Add these items instead", true) }
            val parts = (if (stop < 0) after.take(2) else after.take(stop).take(2)).filter { label(it).isNotEmpty() && it.index !in hidden }
            outOfStock = parts.joinToString(", ") { label(it) }.ifEmpty { null }
            highlights += t.outOfStock(label(h), outOfStock ?: "")
            consumed += h.index
            consumed += parts.map { it.index }
        }

        // Cart badge.
        val bar = els.firstOrNull { it.index !in inCards && "view cart" in fullLabel(it).lowercase() }
        var badge: String? = null
        var count: String? = null
        if (bar != null) {
            val qtyEl = els.firstOrNull { it.rid == "tv_quantity" && label(it).isNotEmpty() } ?: els.firstOrNull { it !== bar && ITEMS.matches(label(it)) && it.index !in inCards }
            badge = listOfNotNull(label(bar), qtyEl?.let(::label)).joinToString(", ")
            count = qtyEl?.let(::label) ?: Regex("(\\d+\\s+items?)", RegexOption.IGNORE_CASE).find(label(bar))?.value
            highlights += t.cartBadge(badge)
            consumed += bar.index
            qtyEl?.let { consumed += it.index }
        }
        if (count == null) els.firstNotNullOfOrNull { SHIPMENT.find(label(it))?.value }?.let { count = Regex("\\d+\\s+items?").find(it)?.value }

        // Items in reading order.
        val seen = mutableSetOf<String>()
        val cardByContainer = cards.associateBy { it.container.index }
        for (e in els) {
            if (e.index in consumed || SHIMMER.containsMatchIn(e.rid)) continue
            val c = cardByContainer[e.index]
            if (c != null) {
                items += GuideItem(spokenCard(c, t), c.name, c.container, c)
                continue
            }
            if (e.index in inCards) continue
            if (e.index in hidden) {
                if (e.password) items += GuideItem(t.passwordField, "", null, field = true)
                else if (isAddress(screen, e) && seen.add("\u0000address")) items += GuideItem(t.savedAddress, "", null)
                continue
            }
            val l = label(e)
            if (e.editable) {
                val whatsappBox = screen.packageName == WhatsApp.PKG && e.rid == WhatsApp.ENTRY
                val name = (if (whatsappBox) e.desc.ifEmpty { "Message" } else e.desc.ifEmpty { e.rid.replace('_', ' ') }).trim()
                // WhatsApp shows its "Message" placeholder as the empty box's text: that isn't typed text.
                val typed = if (whatsappBox) WhatsApp.typedText(screen) else e.text
                val secret = SECRET_REGEX.matcher(name).find() || PrivateText.looksPrivate(typed)
                if (secret) {
                    hidden += e.index
                    items += GuideItem(t.codeField, name, null, field = true)
                    continue
                }
                val spoken = when {
                    typed.isNotEmpty() && name.isNotEmpty() -> t.fieldHas(name, clip(typed))
                    typed.isNotEmpty() -> t.fieldHas(t.emptyField, clip(typed))
                    name.isNotEmpty() -> t.field(name)
                    else -> t.emptyField
                }
                items += GuideItem(spoken, name.ifEmpty { typed }, e, field = true)
                continue
            }
            if (l.isEmpty()) continue
            if (PrivateText.looksPrivate(l)) {
                hidden += e.index
                items += GuideItem(t.codeText, "", null)
                continue
            }
            if (!seen.add(l.lowercase()) && !e.clickable) continue
            items += GuideItem(clip(l), l, e)
        }
        return ScreenView(
            packageName = screen.packageName,
            hash = screen.hash,
            title = title,
            highlights = highlights,
            items = items,
            cards = cards,
            cartBadge = badge,
            cartBar = bar,
            cartCount = count,
            loading = loading(screen),
            outOfStock = outOfStock,
            hidden = hidden,
        )
    }

    /** One page of the description: header + highlights (page 0 only) + up to [size] items. */
    fun describe(v: ScreenView, appLabel: String, t: GuideTexts, page: Int = 0, size: Int = PAGE): String {
        val parts = mutableListOf<String>()
        if (page == 0) {
            parts += t.on(appLabel, v.title)
            if (v.loading && v.items.isEmpty()) parts += t.loading
            parts += v.highlights
        }
        val from = page * size
        val slice = v.items.drop(from).take(size)
        if (slice.isEmpty()) {
            parts += if (page == 0 && v.highlights.isEmpty()) t.nothingVisible else t.noMore
        } else {
            parts += "${t.iSee} ${slice.joinToString("; ") { it.spoken }}."
            val left = v.items.size - from - slice.size
            if (left > 0) parts += t.more(left)
        }
        parts += t.whatNext
        return parts.joinToString(" ")
    }

    const val PAGE = 6

    /** What changed between two looks at the screen (said first after an action). */
    fun changes(before: ScreenView, after: ScreenView, appLabel: (String) -> String, t: GuideTexts): List<String> {
        val out = mutableListOf<String>()
        if (before.packageName != after.packageName) out += t.nowIn(appLabel(after.packageName))
        else if (after.title != null && after.title != before.title) out += t.nowOn(after.title)
        val samePlace = before.packageName == after.packageName && before.title == after.title
        if (samePlace) {
            for (c in after.cards) {
                val old = before.cards.firstOrNull { it.name == c.name && it.size == c.size } ?: continue
                if (old.qty != c.qty) {
                    out += if (c.qty > 0) t.qtyNow(c.name, c.qtyText) else c.add?.let { t.showsAdd(c.name, label(it)) } ?: t.screenChanged
                }
            }
            for (c in before.cards) {
                if (c.qty > 0 && after.cards.none { it.name == c.name && it.size == c.size }) out += t.notInCart(c.name)
            }
        }
        if (after.cartBadge != before.cartBadge) {
            out += if (after.cartBadge != null) t.cartBadge(after.cartBadge) else if (samePlace) t.cartGone else ""
        }
        if (after.outOfStock != null && after.outOfStock != before.outOfStock) out += after.highlights.firstOrNull { after.outOfStock in it } ?: ""
        val res = out.filter { it.isNotEmpty() }
        if (res.isNotEmpty()) return res
        return listOf(if (before.hash == after.hash) t.nothingChanged else t.screenChanged)
    }

    /** True when the screen is a different place (another app or title): describe it fully. */
    fun isNewPlace(before: ScreenView, after: ScreenView): Boolean =
        before.packageName != after.packageName || before.title != after.title ||
            (before.cards.map { it.label }.toSet() != after.cards.map { it.label }.toSet() && after.cards.isNotEmpty())
}
