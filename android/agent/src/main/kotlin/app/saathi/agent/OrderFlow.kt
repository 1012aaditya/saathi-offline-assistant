package app.saathi.agent

import app.saathi.safety.Action
import app.saathi.safety.Back
import app.saathi.safety.BlinkitReadback
import app.saathi.safety.CommandContext
import app.saathi.safety.Direction
import app.saathi.safety.Kind
import app.saathi.safety.OpenApp
import app.saathi.safety.PERMISSION_PACKAGES
import app.saathi.safety.SafetyGate
import app.saathi.safety.Screen
import app.saathi.safety.Scroll
import app.saathi.safety.Search
import app.saathi.safety.Tap
import app.saathi.safety.TypeText
import app.saathi.safety.Verdict

// -- settings that apply to grocery tasks --------------------------------------------------------

/** Settings > Apps > "Grocery app". */
enum class GroceryLevel {
    /** Never operate the grocery app. */
    OFF,

    /** Search and read the cart only: never ADD, never order, never empty the cart. */
    READ_ONLY,

    /** The normal flow, with the read-back HOLD before ordering. */
    ASK_FIRST,
}

/**
 * What the user allowed in Settings. [allowOrders] is the "Allow placing orders" switch, default
 * OFF (like ALLOW_ORDERS=false in the prototype): when off, the flow stops at the read-back.
 * [spendingLimit] is the Settings limit in rupees; an order total above it is refused (R6).
 */
data class OrderPolicy(
    val level: GroceryLevel = GroceryLevel.ASK_FIRST,
    val allowOrders: Boolean = false,
    val spendingLimit: Int = 500,
)

// -- plans the grocery flow needs (handled by AgentLoop) ----------------------------------------

/** Ask the user to choose one of [options] (voice or buttons, see [PickDialog]). */
data class PickRequest(val question: String, val options: List<String>, val hint: String)

/** Choose one option. No answer / unclear twice / cancel = the run ends with [cancelled]. */
class PickPlan(val request: PickRequest, val cancelled: String, val onPick: (Int) -> Unit) : Plan

/** Let a screen that loads in stages settle (Blinkit), then look again. Never acts. */
class WaitPlan(val ms: Long, val say: String = "") : Plan

/**
 * A yes / no before a change the gate doesn't hold by itself (emptying the cart). Shown on the
 * Confirm screen with the same rules: Cancel is the default; only a clear yes runs [onYes].
 */
class ConfirmPlan(val request: ConfirmRequest, val cancelled: String, val onYes: () -> Unit) : Plan

/** Extra hooks the loop uses for grocery runs. */
interface OrderAware {
    /** Added to whatever the run ends with (e.g. "your cart still has …"), or null. */
    fun endNote(outcome: Outcome): String?

    /** Checked again when the gate HOLDs (R3): a reason to refuse, or null to ask the user. */
    fun holdRefusal(verdict: Verdict, screen: Screen): String?

    /** The cart, verbatim, for the Confirm screen. */
    fun summary(screen: Screen): OrderSummary?

    /** Said after the user's yes, while the held tap runs. */
    val confirmedSay: String
}

/**
 * Blinkit ordering as a small state machine (port of saathi/blinkit.py next_action() plus the
 * agent's Blinkit notes). Pure: no I/O; every action it plans still goes through the gate.
 *
 * Order: open Blinkit -> search (deep link; the search box as fallback, which Blinkit hides from
 * accessibility) -> rank the cards -> on a close call ask once -> size picker -> ADD exactly the
 * asked quantity (only the chosen card's own ADD / +; never an upsell) -> open the cart -> read
 * it -> checkout -> tap the order button, which the gate HOLDs (R3) with the verbatim read-back:
 * it runs only after a clear yes. Payment, PIN, login, OTP, address, CAPTCHA -> hand over.
 */
class OrderFlow(
    val mode: Mode,
    private val query: String,
    private val quantity: Int,
    private val policy: OrderPolicy,
    private val ctx: CommandContext,
    private val gate: SafetyGate,
    private val t: OrderTexts,
) : Flow, OrderAware {
    enum class Mode {
        /** Search, add, checkout, read-back HOLD, order on yes. */
        ORDER,

        /** Search and add; read the cart; never go further. */
        ADD_ONLY,

        /** Read only level: search and read the results; never add. */
        SEARCH_ONLY,

        /** Open the cart and read it. */
        READ_CART,

        /** Checkout of the cart as it is, read-back HOLD, order on yes. */
        PLACE,

        /** Confirm, then remove every item with "−". */
        EMPTY,
    }

    companion object {
        /** A search whose results page shows the cart bar (the prototype's empty-cart script). */
        const val CART_PROBE_QUERY = "milk"
        const val MAX_REMOVE_TAPS = 30
        private const val LOAD_WAIT_MS = 1_500L
    }

    private var opens = 0
    private var popups = 0
    private var searched = false
    private var waits = 0
    private var barTapped = false
    private var typedSearch = false
    private var fieldWaits = 0
    private var asks = 0
    private var chosen: Product? = null // what the user wants (a results card)
    private var target: Product? = null // the exact card being added (a size after the picker)
    private var sizePick: Product? = null
    private var sizeAsked = false
    private var sizeChosen = false
    private var startQty: Int? = null
    private var added = 0
    private var addTaps = 0
    private var expectSheet = false
    private var alreadyHad = false
    private var backs = 0
    private var cartTaps = 0
    private var cartWaits = 0
    private var probeSearched = false
    private var holdArmed = false
    private var orderTapped = false
    private var placed = false
    private var afterWaits = 0
    private var lastCart: List<CartLine> = emptyList()
    private var cartMessageGiven = false
    private var emptyConfirmed = false
    private var confirmedNames: Set<String> = emptySet()
    private var removeTaps = 0
    private var scrolls = 0
    private var emptyWaits = 0
    private var shimmerWaits = 0 // guide hand-off hook: results drawn as shimmer placeholders

    /** Units Saathi added in this run (seen on screen, never assumed). */
    val addedUnits: Int get() = added

    val orderPlaced: Boolean get() = placed

    override val confirmedSay: String get() = t.placing

    override fun next(screen: Screen): Plan {
        ctx.itemsAdded = added // the gate's R3 cart guard: no ADD / + beyond the asked quantity
        if (orderTapped) return afterOrder(screen)
        if (screen.packageName != Blinkit.PKG) {
            if (screen.packageName in PERMISSION_PACKAGES) return Plan.HandOver(t.permissionAsk) // never grant (R10)
            if (++opens > 2) return Plan.Fail(t.couldNotOpen)
            return Plan.Act(OpenApp("blinkit"), t.opening)
        }
        Blinkit.noNetworkText(screen)?.let { return Plan.Fail(t.noNetwork) }
        if (popups < 3) {
            Blinkit.selectManually(screen)?.let { popups++; return Plan.Act(Tap(it.index), t.manualLocation) }
            Blinkit.closeLocationSheet(screen)?.let { popups++; return Plan.Act(Tap(it.index), t.closingLocation) }
        }
        Blinkit.loginText(screen)?.let { return Plan.HandOver(t.loginNeeded) }
        Blinkit.addressMissingText(screen)?.let { return Plan.HandOver(t.addressNeeded) }
        Blinkit.notServiceableText(screen)?.let { return Plan.Done(t.notServiceable(it), quotes = listOf(it), title = "Blinkit") }
        if (Blinkit.isCheckout(screen)) return checkout(screen)
        return when (mode) {
            Mode.READ_CART, Mode.PLACE, Mode.EMPTY -> toCart(screen)
            else -> shop(screen)
        }
    }

    override fun executed(action: Action, changed: Boolean) {
        // The order button only runs through the HOLD (after a clear yes): from here on, read the result.
        if (holdArmed && action is Tap) orderTapped = true
    }

    // -- searching and adding ---------------------------------------------------------------------

    private fun shop(screen: Screen): Plan {
        if (mode != Mode.SEARCH_ONLY && (added >= quantity || alreadyHad)) return toCartAfterAdding(screen)
        val cards = Blinkit.cards(screen)
        if (cards.isEmpty()) return noCards(screen)
        val usable = cards.filter { it.add != null || it.qty > 0 }
        if (expectSheet) return sizeSheet(screen, usable)
        val want = target ?: chosen ?: return choose(screen, usable, cards)
        // Only the chosen card's own button, ever. Anything else here (an upsell sheet, other
        // suggestions) is closed, never added to (the gate's R3 cart guard is the backstop).
        val card = usable.firstOrNull { Blinkit.same(it, want) } ?: return back()
        return add(screen, card)
    }

    private fun noCards(screen: Screen): Plan {
        Blinkit.noResultsText(screen)?.let { return Plan.Done(t.nothingFound(query), title = "Blinkit") }
        if (!searched) {
            searched = true
            return Plan.Act(Search(query), t.searching(query))
        }
        // Real Blinkit (13 Oct): results show shimmer placeholders first. Still loading: wait, don't fail.
        if (GuideHooks.loading(screen) && shimmerWaits < 6) {
            shimmerWaits++
            return WaitPlan(LOAD_WAIT_MS, t.waiting)
        }
        if (waits < 2) {
            waits++
            return WaitPlan(LOAD_WAIT_MS, t.waiting)
        }
        if (Blinkit.outOfStockText(screen) != null) return Plan.Done(t.outOfStock(query), title = "Blinkit")
        return searchByField(screen)
    }

    /**
     * The deep link didn't show results: type into Blinkit's search box. Blinkit hides that box
     * from accessibility; the service surfaces the focused field ([Blinkit.HIDDEN_FIELD]) and
     * submits it with Enter (Blinkit only), like device.py did. The typed text is checked.
     */
    private fun searchByField(screen: Screen): Plan {
        val field = Blinkit.searchField(screen)
        if (typedSearch) {
            if (field != null && field.text.isNotEmpty() && !field.text.trim().equals(query.trim(), true)) {
                return Plan.Fail(t.searchFailed) // what's in the box isn't what we typed
            }
            if (fieldWaits < 2) {
                fieldWaits++
                return WaitPlan(LOAD_WAIT_MS, t.waiting)
            }
            return Plan.Done(t.nothingFound(query), title = "Blinkit")
        }
        if (field != null) {
            typedSearch = true
            return Plan.Act(TypeText(query, field.index), t.typingSearch(query))
        }
        if (!barTapped) {
            val bar = Blinkit.searchBar(screen) ?: return Plan.Fail(t.searchFailed)
            barTapped = true
            return Plan.Act(Tap(bar.index), t.openingSearch)
        }
        return Plan.Fail(t.searchFailed)
    }

    private fun choose(screen: Screen, usable: List<BlinkitReadback.Card>, all: List<BlinkitReadback.Card>): Plan {
        val ranked = Blinkit.rank(query, usable)
        val best = ranked.firstOrNull()
        if (best == null || best.first < Blinkit.MIN_SCORE) {
            // A good match that can't be added (no ADD button): out of stock.
            val unavailable = Blinkit.rank(query, all - usable.toSet()).firstOrNull()
            if (unavailable != null && unavailable.first >= Blinkit.MIN_SCORE) {
                return Plan.Done(t.outOfStock(unavailable.second.name), quotes = listOf(unavailable.second.name), title = "Blinkit")
            }
            if (best == null) {
                return if (Blinkit.outOfStockText(screen) != null) Plan.Done(t.outOfStock(query), title = "Blinkit")
                else Plan.Done(t.nothingFound(query), title = "Blinkit")
            }
            val seen = ranked.take(3).map { Product.of(it.second) }
            return Plan.Done(t.noGoodMatch(query, seen), quotes = seen.flatMap { listOf(it.name, it.price) }, title = "Blinkit")
        }
        if (mode == Mode.SEARCH_ONLY) {
            val seen = ranked.filter { it.first >= Blinkit.MIN_SCORE }.take(3).map { Product.of(it.second) }
            return Plan.Done(t.foundReadOnly(query, seen), quotes = seen.flatMap { listOf(it.name, it.price) }, title = "Blinkit")
        }
        // A close call: ask once (FR-A3), with each option verbatim (name, size, price).
        val close = ranked.drop(1).take(3).filter { best.first - it.first < Blinkit.CLEAR_MARGIN && it.second.add != null }
        if (close.isNotEmpty() && asks == 0) {
            asks++
            val options = (listOf(best) + close).take(3).map { Product.of(it.second) }
            return PickPlan(PickRequest(t.whichProduct(options), options.map(t::option), t.pickHint), t.pickCancelled) { i ->
                chosen = options[i]
            }
        }
        chosen = Product.of(best.second)
        return add(screen, best.second)
    }

    private fun add(screen: Screen, card: BlinkitReadback.Card): Plan {
        if (target == null) target = Product.of(card)
        val start = startQty ?: card.qty.also { startQty = it }
        if (start > 0 && addTaps == 0) {
            // Already in the cart before Saathi touched it: never add more on top (prototype rule).
            alreadyHad = true
            // Owner, 13 Oct: an item already in the cart -> ask, don't guess (guide mode takes over).
            return GuideHooks.alreadyInCart(card, screen, t.lang)
        }
        added = maxOf(0, card.qty - start)
        ctx.itemsAdded = added
        if (added >= quantity) return toCartAfterAdding(screen)
        if (++addTaps > quantity + 2) return Plan.Fail(t.couldNotAdd)
        val p = target!!
        if (card.qty == 0) {
            val btn = card.add ?: return Plan.Fail(t.couldNotAdd)
            if (Blinkit.hasOptions(screen, card)) expectSheet = true // ADD opens the size picker
            return Plan.Act(Tap(btn.index), t.adding(p))
        }
        val plus = Blinkit.increase(screen, card) ?: return Plan.Fail(t.couldNotAdd)
        return Plan.Act(Tap(plus.index), t.addingOneMore(p))
    }

    /** The size picker sheet (it hides the results behind it): its cards are the sizes. */
    private fun sizeSheet(screen: Screen, variants: List<BlinkitReadback.Card>): Plan {
        val ch = chosen!!
        if (variants.any { Blinkit.same(it, ch) && Blinkit.hasOptions(screen, it) }) {
            expectSheet = false // the sheet didn't open: try the ADD again (bounded by addTaps)
            return add(screen, variants.first { Blinkit.same(it, ch) })
        }
        if (variants.isEmpty()) return back()
        if (sizeChosen) {
            val card = variants.firstOrNull { Blinkit.same(it, target!!) } ?: return back()
            return add(screen, card)
        }
        val want = Blinkit.sizeOf(query) ?: Blinkit.sizeOf(ch.size).takeIf { asks > 0 } // the size the user heard and chose
        val match = if (want != null) variants.filter { Blinkit.sizeOf(it.size) == want } else emptyList()
        val choice = sizePick?.let { p -> variants.firstOrNull { Blinkit.same(it, p) } } ?: when {
            match.size == 1 -> match[0]
            variants.size == 1 -> variants[0]
            else -> null
        }
        if (choice == null) {
            if (sizeAsked) return GuidePlan(t.stuck)
            sizeAsked = true
            val options = variants.take(3).map(Product::of)
            return PickPlan(PickRequest(t.whichSize(options), options.map(t::option), t.pickHint), t.pickCancelled) { i ->
                sizePick = options[i]
            }
        }
        target = Product.of(choice)
        sizeChosen = true
        startQty = choice.qty
        addTaps = 0
        return add(screen, choice)
    }

    private fun toCartAfterAdding(screen: Screen): Plan {
        val bar = Blinkit.cartBar(screen)
        if (bar != null) {
            if (++cartTaps > 3) return GuidePlan(t.stuck)
            return Plan.Act(Tap(bar.index), t.openingCart)
        }
        if (Blinkit.cards(screen).isNotEmpty() && cartWaits == 0 && !expectSheet) {
            cartWaits++ // the cart bar renders ~1 s after the products (prototype note)
            return WaitPlan(1_000L, t.lookingForCart)
        }
        expectSheet = false
        return back() // a sheet (size picker, upsell) hides the cart bar: close it
    }

    private fun back(): Plan {
        if (++backs > 4) return GuidePlan(t.stuck)
        return Plan.Act(Back, t.closingSheet)
    }

    // -- the cart -----------------------------------------------------------------------------------

    private fun toCart(screen: Screen): Plan {
        val bar = Blinkit.cartBar(screen)
        if (bar != null) {
            if (++cartTaps > 3) return GuidePlan(t.stuck)
            return Plan.Act(Tap(bar.index), t.openingCart)
        }
        if (emptyConfirmed) {
            // The cart left the screen after the last "−": empty (check once more for a late bar).
            if (emptyWaits++ < 1) return WaitPlan(1_000L, t.lookingForCart)
            return Plan.Done(t.cartEmptied, title = "Blinkit")
        }
        if (cartWaits < 2) {
            cartWaits++
            return WaitPlan(1_000L, t.lookingForCart)
        }
        if (!probeSearched) {
            probeSearched = true
            cartWaits = 1
            return Plan.Act(Search(CART_PROBE_QUERY), t.lookingForCart)
        }
        return Plan.Done(t.cartEmpty, title = "Blinkit")
    }

    private fun checkout(screen: Screen): Plan {
        val summary = OrderSummary.from(screen)
        if (mode == Mode.EMPTY) return emptyStep(screen, summary)
        // An out-of-stock block in the cart: the user decides (guide mode), never Saathi.
        if (mode != Mode.READ_CART) GuideHooks.checkoutProblem(screen, t.lang)?.let { return it }
        if (summary == null) {
            if (added > 0 || alreadyHad) return Plan.Fail(t.cartChanged)
            return Plan.Done(t.cartEmpty, title = "Blinkit")
        }
        lastCart = summary.lines
        when (mode) {
            Mode.READ_CART, Mode.SEARCH_ONLY -> return Plan.Done(t.readback(summary), quotes = summary.quotes, title = "Blinkit cart")
            Mode.ADD_ONLY -> {
                val p = target ?: return Plan.Fail(t.cartChanged)
                if (summary.lines.none { it.name == p.name }) return Plan.Fail(t.cartChanged)
                cartMessageGiven = true
                val first = if (alreadyHad) t.alreadyInCart(p) else t.added(p, added)
                return Plan.Done("$first ${t.readback(summary)} ${t.sayPlaceOrder}", quotes = summary.quotes, title = "Blinkit cart")
            }
            Mode.ORDER -> {
                val p = target ?: return Plan.Fail(t.cartChanged)
                if (summary.lines.none { it.name == p.name }) return Plan.Fail(t.cartChanged)
            }
            else -> Unit
        }
        // ORDER / PLACE: the stop-and-confirm point.
        if (Blinkit.addressNeeded(screen) != null) return Plan.HandOver(t.addressNeeded)
        if (summary.total.isEmpty()) return Plan.Fail(t.noTotal)
        val amount = Blinkit.rupees(summary.total) ?: return Plan.Fail(t.noTotal)
        if (amount > policy.spendingLimit) return Plan.Fail(t.overLimit(summary.total, policy.spendingLimit)) // R6
        if (policy.level != GroceryLevel.ASK_FIRST) return Plan.Fail(t.groceryReadOnly)
        if (!policy.allowOrders) {
            cartMessageGiven = true
            return Plan.Done("${t.readback(summary)} ${t.orderingOff}", quotes = summary.quotes, title = "Blinkit cart")
        }
        val btn = Blinkit.orderButton(screen) ?: return Plan.Fail(t.noOrderButton)
        // "Select Payment Method": choosing how to pay is the user's job; hand over before any tap.
        if (GuideHooks.choosesPayment(btn)) return Plan.HandOver(t.payYourself)
        // The gate must treat this tap as the R3 order HOLD; anything else and we don't tap.
        val v = gate.check(Tap(btn.index), screen, ctx)
        if (v.kind == Kind.HANDOFF) return Plan.HandOver(v.message.ifEmpty { t.payYourself })
        if (v.kind != Kind.HOLD || v.rule != "R3") return Plan.Fail(t.notGated)
        holdArmed = true
        return Plan.Act(Tap(btn.index), t.readyToOrder)
    }

    private fun emptyStep(screen: Screen, summary: OrderSummary?): Plan {
        if (summary == null) return Plan.Done(if (emptyConfirmed) t.cartEmptied else t.cartEmpty, title = "Blinkit")
        lastCart = summary.lines
        if (!emptyConfirmed) {
            if (policy.level != GroceryLevel.ASK_FIRST) return Plan.Fail(t.groceryReadOnly)
            val o = summary.copy(purpose = OrderSummary.Purpose.EMPTY)
            val req = ConfirmRequest(readback = t.emptyQuestion(o), rule = "EMPTY_CART", recipient = "", message = "", order = o)
            return ConfirmPlan(req, t.emptyCancelled) {
                emptyConfirmed = true
                confirmedNames = summary.lines.map { it.name }.toSet()
            }
        }
        // Only what the user heard and agreed to: anything new in the cart stops it.
        if (summary.lines.any { it.name !in confirmedNames }) return Plan.Fail(t.cartChanged)
        if (++removeTaps > MAX_REMOVE_TAPS) return Plan.Fail(t.cartPartlyEmptied)
        val item = BlinkitReadback.cartItems(screen).first()
        val minus = Blinkit.decrease(screen, item) ?: return Plan.Fail(t.stuck)
        if (gate.coveredByIrreversible(minus, screen) != null) {
            // R11: under the floating order bar; a tap there could land on "Place Order". Scroll.
            if (++scrolls > 3) return Plan.Fail(t.stuck)
            return Plan.Act(Scroll(Direction.DOWN), t.scrollingClear)
        }
        return Plan.Act(Tap(minus.index), t.removingOne)
    }

    private fun afterOrder(screen: Screen): Plan {
        if (screen.packageName == Blinkit.PKG) {
            Blinkit.orderPlacedText(screen)?.let {
                placed = true
                return Plan.Done(t.orderPlaced(it), quotes = listOf(it), title = "Blinkit order")
            }
        }
        // Another app (a UPI app) or a payment page: the user pays; Saathi never types a PIN.
        if (screen.packageName != Blinkit.PKG) return Plan.HandOver(t.payYourself)
        if (!Blinkit.isCheckout(screen) && Blinkit.paymentText(screen) != null) return Plan.HandOver(t.payYourself)
        if (afterWaits < 2) {
            afterWaits++
            return WaitPlan(2_000L)
        }
        if (Blinkit.paymentText(screen) != null) return Plan.HandOver(t.payYourself)
        return Plan.HandOver(t.checkScreen)
    }

    // -- OrderAware ---------------------------------------------------------------------------------

    override fun endNote(outcome: Outcome): String? {
        if (placed || cartMessageGiven || orderTapped) return null
        if (mode == Mode.EMPTY) {
            return if (emptyConfirmed && outcome !is Outcome.Answered && lastCart.isNotEmpty()) t.cartPartlyEmptied else null
        }
        if (mode == Mode.READ_CART || mode == Mode.SEARCH_ONLY) return null
        val lines = when {
            lastCart.isNotEmpty() -> lastCart
            added > 0 -> target?.let { listOf(CartLine(it.name, it.size, added, it.price)) }.orEmpty()
            else -> emptyList()
        }
        return if (lines.isEmpty()) null else t.cartStillHas(lines)
    }

    override fun holdRefusal(verdict: Verdict, screen: Screen): String? {
        if (verdict.rule != "R3") return null
        if (mode != Mode.ORDER && mode != Mode.PLACE) return t.notGated
        if (policy.level != GroceryLevel.ASK_FIRST) return t.groceryReadOnly
        if (!policy.allowOrders) return t.orderingOff
        val total = BlinkitReadback.cartTotal(screen)
        val amount = Blinkit.rupees(total) ?: return t.noTotal
        if (amount > policy.spendingLimit) return t.overLimit(total, policy.spendingLimit)
        return null
    }

    override fun summary(screen: Screen): OrderSummary? = OrderSummary.from(screen)
}

/** Builds grocery flows and decides what to say before starting one. */
object OrderFlows {
    /** Settings > Apps > Grocery app: why this command can't run at all, or null. */
    fun refusal(command: GroceryCommand, policy: OrderPolicy, t: OrderTexts): String? = when {
        policy.level == GroceryLevel.OFF -> t.groceryOff
        policy.level == GroceryLevel.READ_ONLY && (command is GroceryCommand.PlaceOrder || command is GroceryCommand.EmptyCart) -> t.groceryReadOnly
        else -> null
    }

    /** The question to ask when the item is missing or unclear (never guessed at). */
    fun missing(command: GroceryCommand, t: OrderTexts): String? = when (val p = (command as? GroceryCommand.Order)?.problem) {
        null -> if (command is GroceryCommand.Order && command.query == null) t.whatToOrder else null
        GroceryCommand.Problem.NoItem -> t.whatToOrder
        is GroceryCommand.Problem.Several -> t.oneAtATime(p.items)
        is GroceryCommand.Problem.Unknown -> t.unknownItem(p.word)
        is GroceryCommand.Problem.TooMany -> t.tooMany(p.max)
    }

    /**
     * The safety context. `quantity` is how many units the gate's R3 cart guard lets Saathi add:
     * the asked number for an order, 0 for everything else (no ADD / + can pass at all).
     */
    fun contextFor(command: GroceryCommand, policy: OrderPolicy): CommandContext {
        val adds = command is GroceryCommand.Order && policy.level == GroceryLevel.ASK_FIRST
        return CommandContext(command = command.utterance, quantity = if (adds) (command as GroceryCommand.Order).quantity else 0)
    }

    /** Null when [refusal] or [missing] has something to say instead. [ctx] must be the run's context. */
    fun forCommand(command: GroceryCommand, policy: OrderPolicy, gate: SafetyGate, ctx: CommandContext, t: OrderTexts): OrderFlow? {
        if (refusal(command, policy, t) != null || missing(command, t) != null) return null
        fun flow(mode: OrderFlow.Mode, query: String = "", qty: Int = 0) = OrderFlow(mode, query, qty, policy, ctx, gate, t)
        return when (command) {
            is GroceryCommand.Order -> when {
                policy.level == GroceryLevel.READ_ONLY -> flow(OrderFlow.Mode.SEARCH_ONLY, command.query!!)
                command.addOnly -> flow(OrderFlow.Mode.ADD_ONLY, command.query!!, command.quantity)
                else -> flow(OrderFlow.Mode.ORDER, command.query!!, command.quantity)
            }
            is GroceryCommand.ReadCart -> flow(OrderFlow.Mode.READ_CART)
            is GroceryCommand.PlaceOrder -> flow(OrderFlow.Mode.PLACE)
            is GroceryCommand.EmptyCart -> flow(OrderFlow.Mode.EMPTY)
        }
    }
}
