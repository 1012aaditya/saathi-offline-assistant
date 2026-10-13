package app.saathi.agent

import app.saathi.safety.Action
import app.saathi.safety.Answer
import app.saathi.safety.Apps
import app.saathi.safety.Back
import app.saathi.safety.BlinkitReadback
import app.saathi.safety.CommandContext
import app.saathi.safety.Direction
import app.saathi.safety.Element
import app.saathi.safety.Fuzz
import app.saathi.safety.Kind
import app.saathi.safety.OpenApp
import app.saathi.safety.SECRET_REGEX
import app.saathi.safety.Screen
import app.saathi.safety.Scroll
import app.saathi.safety.Search
import app.saathi.safety.Tap
import app.saathi.safety.TypeText
import app.saathi.safety.Verdict
import app.saathi.safety.isCaptcha
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

// -- hand-off from the scripted flows ---------------------------------------------------------------

/**
 * A scripted flow (OrderFlow, WhatsApp flows) reached a screen it can't handle safely by itself
 * (an out-of-stock item in the cart, an item already in the cart, an unexpected sheet): instead
 * of guessing or failing, it hands the screen to the user in guide mode. [message] is the
 * failure said when guide mode can't start; [intro] is what the guide says first (null: "I'm not
 * sure what to do on this screen").
 */
class GuidePlan(val message: String, val intro: String? = null) : Plan

/** Attached to [Outcome.Failed] when the user can carry on together with Saathi in [packageName]. */
data class GuideOffer(val packageName: String, val intro: String?)

/** Small, pure checks the scripted flows use to hand off (real Blinkit shapes, 13 Oct). */
object GuideHooks {
    private val PAYMENT_CHOICE = Regex("select payment method|choose (a )?payment|payment method|pay using|add (a )?payment", RegexOption.IGNORE_CASE)
    private val NOT_IN_STOCK = Regex("^\\d+ items? (is|are) not in stock$", RegexOption.IGNORE_CASE)

    /** Results still loading (Blinkit's shimmer placeholders): wait, don't fail. */
    fun loading(screen: Screen): Boolean = GuideView.loading(screen)

    /** A control that would make Saathi choose how to pay ("Select Payment Method"): the user does that. */
    fun choosesPayment(e: Element?): Boolean = e != null && PAYMENT_CHOICE.containsMatchIn("${e.text} ${e.desc}")

    /** Checkout with an out-of-stock block: ask the user (guide mode), never decide for them. */
    fun checkoutProblem(screen: Screen, lang: Lang): GuidePlan? {
        if (screen.elements.none { NOT_IN_STOCK.matches(GuideView.label(it)) }) return null
        val t = GuideTexts(lang)
        val v = GuideView.of(screen, "Blinkit", t)
        val item = v.outOfStock ?: return GuidePlan(t.notSure, null)
        return GuidePlan(t.outOfStockAsk(item), t.outOfStockAsk(item))
    }

    /** The product the user asked for is already in the cart: ask, don't add on top. */
    fun alreadyInCart(c: BlinkitReadback.Card, screen: Screen, lang: Lang): GuidePlan {
        val t = GuideTexts(lang)
        val card = GuideView.cards(screen).firstOrNull { it.container === c.card }
        val item = listOf(c.name, c.size).filter { it.isNotEmpty() }.joinToString(" ")
        val msg = t.alreadyInCart(item, card?.qtyText?.ifEmpty { null } ?: "quantity ${c.qty}")
        return GuidePlan(msg, msg)
    }
}

// -- what guide mode needs from the app -----------------------------------------------------------

/** One thing Saathi says in the conversation. Every turn waits for the user (cue: NeedsAnswer). */
data class GuideTurn(val text: String, val kind: Kind) {
    enum class Kind { DESCRIBE, RESULT, INFO, REFUSED }
}

/** Settings > Apps levels, as guide mode applies them. */
enum class AppAccess { OFF, READ_ONLY, ASK_FIRST }

data class GuidePolicy(
    val grocery: OrderPolicy = OrderPolicy(),
    val whatsapp: AppAccess = AppAccess.ASK_FIRST,
    val payments: AppAccess = AppAccess.OFF,
)

/**
 * The app side of a guide session. [next] waits for the user's next command; the app only ever
 * produces one when the user pressed Speak / held Volume-down and talked (push-to-talk), tapped a
 * button (Repeat, Read more, Stop), or a debug-build `--es text` command arrived. Saathi never
 * opens the microphone by itself in guide mode: the description contains the very words a
 * command uses (13 Oct incidents).
 */
interface GuideIO {
    suspend fun say(turn: GuideTurn)

    /** The user's next command, or null after [timeoutMs] of silence. */
    suspend fun next(timeoutMs: Long): Heard?

    /** Bring [packageName] to the front (to read or act on it). True when it is in front. */
    suspend fun showApp(packageName: String): Boolean = true

    /** Bring Saathi back to the front (to talk). */
    suspend fun showSaathi() {}

    /** The Confirm sheet (VoiceAnswer pipeline). Only [Answer.YES] proceeds. */
    suspend fun confirm(request: ConfirmRequest): Answer

    /** The "which one?" sheet (PickDialog). Null = cancel (the default). */
    suspend fun pick(request: PickRequest): Int?

    fun appLabel(packageName: String): String = when (packageName) {
        Apps.WHATSAPP -> "WhatsApp"
        Apps.BLINKIT -> "Blinkit"
        Apps.GPAY -> "Google Pay"
        Apps.SETTINGS_APP -> "Settings"
        else -> "this app"
    }
}

// -- resolving what the user named ----------------------------------------------------------------

/** Something on screen the user can name: the [option] text (verbatim labels) and the element to act on. */
data class GuideTarget(val option: String, val label: String, val context: String, val element: Element, val card: GuideCard? = null)

sealed interface Resolved {
    data class One(val target: GuideTarget) : Resolved
    data class Many(val targets: List<GuideTarget>) : Resolved
    data object None : Resolved
}

object GuideTargets {
    const val MIN_SCORE = 70.0
    const val MIN_BARE = 90.0
    private const val TIE = 4.0

    private fun norm(s: String): List<String> = PickDialog.words(s.replace("₹", " "))

    /** How well [query] names [label] (0–101). Fuzzy (token set), penalised for words the label lacks. */
    fun score(query: String, label: String): Double {
        val q = norm(query)
        val l = norm(label)
        if (q.isEmpty() || l.isEmpty()) return 0.0
        if (q == l) return 101.0
        var s = Fuzz.tokenSetRatio(q.joinToString(" "), l.joinToString(" "))
        val common = q.count { w -> l.any { it == w || (w.length >= 4 && Fuzz.tokenSetRatio(w, it) >= 80) } }
        if (common < q.size) s *= 0.75 + 0.25 * common / q.size
        if (common == 0) s = minOf(s, 60.0)
        return s
    }

    /** Every target on screen: cards (by name), buttons inside cards, the cart bar, other labelled items. */
    fun all(v: ScreenView): List<GuideTarget> {
        val out = mutableListOf<GuideTarget>()
        for (item in v.items) {
            val e = item.element ?: continue
            val c = item.card
            if (c != null) {
                out += GuideTarget(listOf(c.label, c.price).filter { it.isNotEmpty() }.joinToString(", "), c.name, c.size, c.container, c)
                listOfNotNull(c.add, c.minus, c.plus).plus(c.actions).forEach { b ->
                    out += GuideTarget("${GuideView.label(b)}, ${c.label}", GuideView.label(b), c.label, b, c)
                }
            } else if (item.name.isNotEmpty()) {
                out += GuideTarget(item.name, item.name, "", e)
            }
        }
        v.cartBar?.let { out += GuideTarget(GuideView.label(it), GuideView.label(it), "", it) }
        return out.filter { it.element.index !in v.hidden }
    }

    fun resolve(query: String, targets: List<GuideTarget>, min: Double = MIN_SCORE): Resolved {
        if (query.isBlank() || targets.isEmpty()) return Resolved.None
        val scored = targets.map { t ->
            val s = maxOf(score(query, t.label), if (t.context.isEmpty()) 0.0 else score(query, "${t.label} ${t.context}"))
            t to s
        }
        val best = scored.maxOf { it.second }
        if (best < min) return Resolved.None
        val top = scored.filter { it.second >= best - TIE }.map { it.first }.distinctBy { it.element.index }
        return if (top.size == 1) Resolved.One(top[0]) else Resolved.Many(top)
    }

    /** The same control on a fresh screen (it was planned on an older look). */
    fun refind(old: Element, card: GuideCard?, fresh: Screen): Element? {
        if (card != null) {
            val c = GuideView.cards(fresh).firstOrNull { it.name == card.name && it.size == card.size } ?: return null
            if (old === card.container || old.index == card.container.index) return c.container
            val l = GuideView.label(old)
            return listOfNotNull(c.add, c.minus, c.plus).plus(c.actions).firstOrNull { GuideView.label(it) == l }
        }
        val same = fresh.elements.filter { it.text == old.text && it.desc == old.desc && it.rid == old.rid && it.cls == old.cls }
        return same.minByOrNull { Math.abs(it.center.y - old.center.y) + Math.abs(it.center.x - old.center.x) }
    }
}

// -- the session ----------------------------------------------------------------------------------

/**
 * "Let's do it together": a conversation about the app in front. Saathi DESCRIBES the screen
 * (verbatim labels), the user says what to do (push-to-talk only), Saathi UNDERSTANDS it offline
 * ([GuideGrammar] + [GuideTargets]), ACTS through the safety gate ([GatedDevice]: ALLOW runs;
 * HOLD -> the Confirm sheet with the verbatim read-back; BLOCK -> says why; HANDOFF -> the
 * Handover screen), then says what changed. Repeat until the user says done / stop / bas, or 3
 * minutes pass without a command. The end message always says where things are left.
 *
 * Extra care on top of the gate (never instead of it): in apps the gate doesn't know, a button
 * that looks like it sends, pays or buys is never pressed; Saathi never chooses a payment method;
 * never types codes / PINs / passwords; never acts on Saathi's own screen; Settings levels apply.
 */
class GuideSession(
    private val device: GatedDevice,
    private val lang: Lang,
    private val io: GuideIO,
    private val policy: GuidePolicy = GuidePolicy(),
    private var target: String? = null,
    private val ownPackage: String = "app.saathi",
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val IDLE_MS = 3 * 60_000L
        const val CONFIRM_TIMEOUT_MS = 90_000L
        const val TAP_WAIT_MS = 3_000L
        const val SETTLE_MS = 600L
        const val SETTLE_TRIES = 3
        const val LOAD_WAIT_MS = 1_000L
        const val LOAD_TRIES = 5
        const val MAX_COUNT = 5

        /** Echo defence for long transcripts (commands are short): this many words or more are checked. */
        const val ECHO_MIN_WORDS = 6

        private val RISKY_ELSEWHERE = Regex(
            "\\b(send|pay|buy|order|purchase|place order|checkout|check out|confirm|book|submit|transfer|subscribe|donate|post|publish|upload)\\b|भेजें|भुगतान|खरीदें|ऑर्डर",
            RegexOption.IGNORE_CASE,
        )
        private val LOGIN_FIELD = Regex("(mobile|phone) number|\\botp\\b|password|passcode|verification code|मोबाइल नंबर", RegexOption.IGNORE_CASE)
        private val LOGIN_TEXT = Regex("\\b(log ?in|sign ?in|sign ?up|verify)\\b|लॉग ?इन", RegexOption.IGNORE_CASE)
    }

    private val t = GuideTexts(lang)
    private val texts = Texts(lang)
    private val orderTexts = OrderTexts(lang)

    private var screen: Screen? = null
    private var view: ScreenView? = null
    private var page = 0
    private var lastSaid = ""
    private var lastCard: GuideCard? = null
    private var ordered = false
    private var sent = 0

    /** The last command's kind, for logs/tests (never the words). */
    var lastCommand: String = ""
        private set

    private fun label(pkg: String) = io.appLabel(pkg)

    private suspend fun say(text: String, kind: GuideTurn.Kind = GuideTurn.Kind.INFO) {
        io.showSaathi()
        lastSaid = text
        io.say(GuideTurn(text, kind))
    }

    private fun describe(page: Int = 0): String {
        val v = view ?: return t.nothingVisible
        return GuideView.describe(v, label(v.packageName), t, page)
    }

    private fun setScreen(s: Screen) {
        screen = s
        view = GuideView.of(s, label(s.packageName), t)
        page = 0
        if (s.packageName != ownPackage) target = s.packageName
    }

    /**
     * Run the conversation. [open]: an app alias to open first (gated OpenApp). [intro]: said
     * before the first description (the hand-off reason). Returns how it ended.
     */
    suspend fun run(intro: String? = null, open: String? = null): Outcome {
        try {
            if (open != null) {
                val pkg = Apps.APP_ALIASES[open.lowercase().trim()] ?: return Outcome.Failed(t.noTarget)
                refusalFor(pkg)?.let { return Outcome.Failed(it) }
                if (!bringOrOpen(pkg)) return Outcome.Failed(texts.appCouldNotOpen(label(pkg)))
                target = pkg
            }
            val pkg = target ?: return Outcome.Asked(t.noTarget)
            refusalFor(pkg)?.let { return Outcome.Failed(it) }
            val s = look(settle = true) ?: return Outcome.Failed(t.couldNotReturn)
            handover(s)?.let { return it }
            setScreen(s)
            val first = listOfNotNull(intro ?: t.start, if (intro != null) t.hereIsWhat else null, describe(0), t.pressSpeak)
            say(first.joinToString(" "), GuideTurn.Kind.DESCRIBE)
            while (true) {
                coroutineContext.ensureActive()
                val heard = io.next(IDLE_MS) ?: return end(t.idle)
                if (heard.msAfterSpeech < EchoGuard.MIN_HEARD_AFTER_SPEECH_MS) {
                    log("guide: heard too soon after speech (${heard.msAfterSpeech} ms), ignored")
                    say(t.tooSoon, GuideTurn.Kind.REFUSED)
                    continue
                }
                if (EchoGuard.tokens(heard.text).size >= ECHO_MIN_WORDS && EchoGuard.looksLikeEcho(heard.text, listOf(lastSaid))) {
                    log("guide: transcript looked like an echo of Saathi's last turn, ignored")
                    say(t.echo, GuideTurn.Kind.REFUSED)
                    continue
                }
                val cmd = GuideGrammar.parse(heard.text)
                lastCommand = cmd::class.simpleName.orEmpty()
                log("guide: command $lastCommand")
                handle(cmd, heard.text)?.let { return it }
            }
        } finally {
            withContext(NonCancellable) { runCatching { io.showSaathi() } }
        }
    }

    // -- commands ---------------------------------------------------------------------------------

    private suspend fun handle(cmd: GuideCommand, utterance: String): Outcome? {
        when (cmd) {
            GuideCommand.Done -> return end(t.bye)
            GuideCommand.Repeat -> say(lastSaid.ifEmpty { describe() }, GuideTurn.Kind.DESCRIBE)
            GuideCommand.More -> { page++; say(describe(page), GuideTurn.Kind.DESCRIBE) }
            GuideCommand.Help -> say("${t.help} ${t.whatNext}")
            GuideCommand.Keep -> say("${t.keptIt} ${t.whatNext}")
            GuideCommand.WhatsOnScreen -> {
                val s = look(settle = true) ?: return sayCantReturn()
                handover(s)?.let { return it }
                setScreen(s)
                say(describe(0), GuideTurn.Kind.DESCRIBE)
            }
            GuideCommand.Cart -> {
                val s = look(settle = true) ?: return sayCantReturn()
                handover(s)?.let { return it }
                setScreen(s)
                say("${cartText()} ${t.whatNext}")
            }
            GuideCommand.OpenCart, GuideCommand.Checkout -> {
                val s = look(settle = true) ?: return sayCantReturn()
                handover(s)?.let { return it }
                setScreen(s)
                val v = view!!
                val bar = v.cartBar
                if (bar != null) return tap(bar, null, GuideView.label(bar), utterance)
                if (cmd == GuideCommand.Checkout) {
                    val btn = if (s.packageName in Apps.ORDER_APPS) Blinkit.orderButton(s) else null
                    if (btn != null) return tap(btn, null, GuideView.label(btn), utterance)
                    return tapNamed("checkout", utterance, true)
                }
                say("${t.noCart} ${t.whatNext}")
            }
            is GuideCommand.Scroll -> return act(Scroll(if (cmd.down) Direction.DOWN else Direction.UP), if (cmd.down) t.scrolledDown else t.scrolledUp, CommandContext(utterance))
            GuideCommand.Back -> return act(Back, t.wentBack, CommandContext(utterance))
            is GuideCommand.Search -> return search(cmd.query, utterance)
            is GuideCommand.Type -> return type(cmd.text, cmd.field, utterance)
            is GuideCommand.Add -> return add(cmd.item, cmd.count, utterance)
            is GuideCommand.Remove -> return step(cmd.item, plus = false, utterance)
            is GuideCommand.Increase -> return step(cmd.item, plus = true, utterance)
            is GuideCommand.Tap -> return tapNamed(cmd.target, utterance, cmd.explicit)
            is GuideCommand.Unknown -> say(t.didntUnderstand, GuideTurn.Kind.REFUSED)
        }
        return null
    }

    private suspend fun sayCantReturn(): Outcome? {
        say("${t.couldNotReturn} ${t.whatNext}", GuideTurn.Kind.REFUSED)
        return null
    }

    private fun cartText(): String {
        val v = view ?: return t.noCart
        val inCart = v.cards.filter { it.qty > 0 }
        val onCheckout = v.title.equals("Checkout", true)
        return when {
            onCheckout && inCart.isNotEmpty() -> t.cartIs(inCart.joinToString("; ") { c -> listOf(c.label, c.qtyText, c.price).filter { it.isNotEmpty() }.joinToString(", ") }) +
                (v.outOfStock?.let { " " + v.highlights.first { h -> it in h } } ?: "")
            v.cartBadge != null -> t.cartBadge(v.cartBadge)
            else -> t.noCart
        }
    }

    /** "tap <label>" / a bare label: resolve on the screen as last described, then act on a fresh look. */
    private suspend fun tapNamed(query: String, utterance: String, explicit: Boolean): Outcome? {
        val v = view ?: return null
        val q = if (query.lowercase() in GuideGrammar.PRONOUNS && lastCard != null) lastCard!!.name else query
        val targets = GuideTargets.all(v)
        return when (val r = GuideTargets.resolve(q, targets, if (explicit) GuideTargets.MIN_SCORE else GuideTargets.MIN_BARE)) {
            is Resolved.One -> tapTarget(r.target, utterance)
            is Resolved.Many -> choose(r.targets)?.let { tapTarget(it, utterance) }
            Resolved.None -> {
                if (explicit) say("${t.cantSee(query, seenNames(v))} ${t.whatNext}", GuideTurn.Kind.REFUSED)
                else say(t.didntUnderstand, GuideTurn.Kind.REFUSED)
                null
            }
        }
    }

    private fun seenNames(v: ScreenView): List<String> = v.items.filter { it.element != null && it.name.isNotEmpty() }.map { it.card?.label ?: it.name }.distinct().take(4)

    /** Several things match: the "which one?" sheet (cancel is the default). Null = nothing chosen (said). */
    private suspend fun choose(options: List<GuideTarget>): GuideTarget? {
        val shown = options.take(4)
        val texts = shown.map { it.option }
        if (texts.distinct().size != texts.size) {
            say("${t.whichOne(texts.distinct())} ${t.whatNext}", GuideTurn.Kind.INFO)
            return null
        }
        io.showSaathi()
        val i = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { io.pick(PickRequest(t.whichOne(texts), texts, t.pickHint)) }
        coroutineContext.ensureActive()
        if (i == null || i !in shown.indices) {
            say("${t.pickCancelled} ${t.whatNext}")
            return null
        }
        return shown[i]
    }

    private suspend fun tapTarget(target: GuideTarget, utterance: String): Outcome? {
        target.card?.let { lastCard = it }
        return tap(target.element, target.card, target.label, utterance)
    }

    private suspend fun tap(element: Element, card: GuideCard?, label: String, utterance: String, ctx: CommandContext = CommandContext(utterance)): Outcome? =
        act(Tap(element.index), t.tapped(label), ctx) { fresh -> GuideTargets.refind(element, card, fresh) }

    private suspend fun add(item: String, count: Int, utterance: String): Outcome? {
        if (count > MAX_COUNT) { say("${t.tooMany} ${t.whatNext}", GuideTurn.Kind.REFUSED); return null }
        val v = view ?: return null
        if (v.cards.isEmpty()) return tapNamed(listOf("add", item).filter { it.isNotEmpty() }.joinToString(" "), utterance, true)
        val card = pickCard(item, v.cards) ?: return null
        lastCard = card
        if (card.qty > 0) {
            // Already in the cart: never add on top without being told exactly that ("plus …").
            say("${t.alreadyInCartHow(card.label, card.qtyText)} ${t.whatNext}")
            return null
        }
        if (card.add == null) { say("${t.noAddButton(card.label)} ${t.whatNext}", GuideTurn.Kind.REFUSED); return null }
        val ctx = CommandContext(command = utterance, quantity = count)
        var r = act(Tap(card.add.index), t.added, ctx, quiet = count > 1) { fresh -> GuideTargets.refind(card.add, card, fresh) }
        if (r != null) return r
        repeat(count - 1) { i ->
            if (ctx.itemsAdded == 0) return null // the first ADD didn't run (refused / failed): already said
            val last = i == count - 2
            r = act(Tap(0), t.addedOne, ctx, quiet = !last) { fresh -> GuideView.cards(fresh).firstOrNull { it.name == card.name && it.size == card.size }?.plus }
            if (r != null) return r
        }
        return null
    }

    /** "minus X" / "plus X": the stepper of an item already in the cart. */
    private suspend fun step(item: String, plus: Boolean, utterance: String): Outcome? {
        val v = view ?: return null
        val inCart = v.cards.filter { it.qty > 0 }
        val card = when {
            item.isEmpty() || item.lowercase() in GuideGrammar.PRONOUNS -> lastCard?.let { c -> v.cards.firstOrNull { it.name == c.name && it.size == c.size } }
                ?: inCart.singleOrNull() ?: run { say("${t.whichItem} ${t.whatNext}"); return null }
            else -> pickCard(item, v.cards) ?: return null
        }
        lastCard = card
        if (card.qty == 0) {
            if (!plus) {
                // Out of stock / not in the cart: say what the screen offers instead.
                val wish = card.actions.firstOrNull()
                say("${t.notInCart(card.label)} ${wish?.let { "${t.iSee} ${GuideView.label(it)}." } ?: ""} ${t.whatNext}".replace("  ", " "))
                return null
            }
            return add(card.name, 1, utterance)
        }
        val btn = (if (plus) card.plus else card.minus) ?: run { say("${t.couldNotPress} ${t.whatNext}", GuideTurn.Kind.REFUSED); return null }
        val ctx = CommandContext(command = utterance, quantity = 1)
        return act(Tap(btn.index), if (plus) t.addedOne else t.removedOne, ctx) { fresh -> GuideTargets.refind(btn, card, fresh) }
    }

    private suspend fun pickCard(item: String, cards: List<GuideCard>): GuideCard? {
        if (item.isBlank() || item.lowercase() in GuideGrammar.PRONOUNS) {
            lastCard?.let { c -> cards.firstOrNull { it.name == c.name && it.size == c.size }?.let { return it } }
            cards.singleOrNull()?.let { return it }
            val targets = cards.map { GuideTarget(listOf(it.label, it.price).joinToString(", "), it.name, it.size, it.container, it) }
            return choose(targets)?.card
        }
        val targets = cards.map { GuideTarget(listOf(it.label, it.price).filter { s -> s.isNotEmpty() }.joinToString(", "), it.name, it.size, it.container, it) }
        return when (val r = GuideTargets.resolve(item, targets)) {
            is Resolved.One -> r.target.card
            is Resolved.Many -> choose(r.targets)?.card
            Resolved.None -> { say("${t.cantSee(item, cards.map { it.label }.distinct().take(4))} ${t.whatNext}", GuideTurn.Kind.REFUSED); null }
        }
    }

    private suspend fun search(query: String, utterance: String): Outcome? {
        if (PrivateText.looksPrivate(query)) { say("${t.noSecrets} ${t.whatNext}", GuideTurn.Kind.REFUSED); return null }
        val pkg = target ?: return null
        if (pkg in Apps.ORDER_APPS) return act(Search(query.take(80)), t.searched(query), CommandContext(utterance))
        val s = look(settle = true) ?: return sayCantReturn()
        handover(s)?.let { return it }
        setScreen(s)
        val field = searchField(s)
        if (field == null) {
            val bar = s.elements.firstOrNull { it.clickable && !it.editable && Regex("search|खोज", RegexOption.IGNORE_CASE).containsMatchIn(GuideView.fullLabel(it) + " " + it.rid) }
                ?: run { say("${t.noSearch} ${t.whatNext}", GuideTurn.Kind.REFUSED); return null }
            act(Tap(bar.index), t.tapped(GuideView.label(bar).ifEmpty { "Search" }), CommandContext(utterance), quiet = true) { fresh -> GuideTargets.refind(bar, null, fresh) }?.let { return it }
        }
        return act(TypeText(query.take(500), 0), t.searched(query), CommandContext(utterance)) { fresh -> searchField(fresh) }
    }

    private fun searchField(s: Screen): Element? {
        val edits = s.elements.filter { it.editable && !it.password }
        return edits.firstOrNull { Regex("search|खोज", RegexOption.IGNORE_CASE).containsMatchIn(GuideView.fullLabel(it) + " " + it.rid) } ?: edits.singleOrNull()
    }

    private suspend fun type(text: String, field: String?, utterance: String): Outcome? {
        if (PrivateText.looksPrivate(text) || SECRET_REGEX.matcher(field.orEmpty()).find()) {
            say("${t.noSecrets} ${t.whatNext}", GuideTurn.Kind.REFUSED)
            return null
        }
        val v = view ?: return null
        val fields = v.items.filter { it.field && it.element != null }
        val chosen: Element = when {
            fields.isEmpty() -> { say("${t.noField} ${t.whatNext}", GuideTurn.Kind.REFUSED); return null }
            field != null -> {
                val targets = fields.map { GuideTarget(it.spoken, it.name, "", it.element!!) }
                when (val r = GuideTargets.resolve(field, targets)) {
                    is Resolved.One -> r.target.element
                    is Resolved.Many -> choose(r.targets)?.element ?: return null
                    Resolved.None -> { say("${t.cantSee(field, fields.map { it.spoken })} ${t.whatNext}", GuideTurn.Kind.REFUSED); return null }
                }
            }
            fields.size == 1 -> fields[0].element!!
            else -> {
                val entry = screen?.let { WhatsApp.entry(it) }
                entry ?: choose(fields.map { GuideTarget(it.spoken, it.name, "", it.element!!) })?.element ?: return null
            }
        }
        return act(TypeText(text.take(500), chosen.index), t.typed(text), CommandContext(utterance)) { fresh -> GuideTargets.refind(chosen, null, fresh) }
    }

    // -- acting -------------------------------------------------------------------------------------

    /**
     * Bring the app forward, look again, re-find the target on that fresh screen ([find]; null =
     * it's gone: say so, press nothing), apply the guide's own refusals, then execute through the
     * gate. Afterwards: settle, look, and say what changed. [quiet]: part of a multi-step command,
     * only the last step speaks. Returns an Outcome only when the session ends (hand-over).
     */
    private suspend fun act(
        action: Action,
        doneText: String,
        ctx: CommandContext,
        quiet: Boolean = false,
        find: ((Screen) -> Element?)? = null,
    ): Outcome? {
        val s = look(settle = false) ?: return sayCantReturn()
        handover(s)?.let { return it }
        if (s.packageName == ownPackage) { say("${t.ownScreen} ${t.whatNext}", GuideTurn.Kind.REFUSED); return null }
        val before = GuideView.of(s, label(s.packageName), t)
        val a: Action = when (action) {
            is Tap -> {
                val e = find?.invoke(s) ?: run { setScreen(s); say("${t.screenMoved} ${describe(0)}", GuideTurn.Kind.DESCRIBE); return null }
                if (e.index in before.hidden) { say("${t.noSecrets} ${t.whatNext}", GuideTurn.Kind.REFUSED); return null }
                Tap(e.index)
            }
            is TypeText -> {
                val e = find?.invoke(s) ?: run { setScreen(s); say("${t.screenMoved} ${describe(0)}", GuideTurn.Kind.DESCRIBE); return null }
                TypeText(action.text, e.index)
            }
            else -> action
        }
        guideRefusal(a, s)?.let { (text, handOver) ->
            if (handOver) return endHandOver(text)
            say("$text ${t.whatNext}", GuideTurn.Kind.REFUSED)
            return null
        }
        when (val r = device.execute(a, s, ctx)) {
            is Exec.Ran -> {
                if (!r.ok) { say("${t.couldNotPress} ${t.whatNext}", GuideTurn.Kind.REFUSED); return null }
                if (a is Tap && device.gate.isAddButton(s.elements[a.index], s.packageName)) ctx.itemsAdded++
                if (a is Tap && WhatsApp.isWhatsApp(s) && s.elements[a.index].rid in setOf("send", "send_btn")) sent++
                return after(s, before, doneText, quiet)
            }
            is Exec.Held -> return held(r, s, before, ctx)
            is Exec.Blocked -> {
                val why = if (r.verdict.rule == "R3") t.cartGuard else texts.verdict(r.verdict)
                say("$why ${t.whatNext}", GuideTurn.Kind.REFUSED)
            }
            is Exec.HandOff -> return endHandOver(texts.verdict(r.verdict))
        }
        return null
    }

    /** Settle after an action, look (whatever is in front now), say what changed. */
    private suspend fun after(s: Screen, before: ScreenView, doneText: String, quiet: Boolean): Outcome? {
        device.waitForChange(s.hash, TAP_WAIT_MS)
        var now = device.snapshot() ?: return sayCantReturn()
        repeat(SETTLE_TRIES) {
            if (!device.waitForChange(now.hash, SETTLE_MS)) return@repeat
            now = device.snapshot() ?: return sayCantReturn()
        }
        var tries = 0
        while (GuideView.loading(now) && tries++ < LOAD_TRIES) {
            device.waitForChange(now.hash, LOAD_WAIT_MS)
            now = device.snapshot() ?: return sayCantReturn()
        }
        handover(now)?.let { return it }
        if (now.packageName in Apps.ORDER_APPS && Blinkit.orderPlacedText(now) != null) ordered = true
        setScreen(now)
        if (quiet) return null
        val v = view!!
        val changes = GuideView.changes(before, v, ::label, t)
        val tail = if (GuideView.isNewPlace(before, v)) describe(0) else t.whatNext
        say(listOf(doneText, changes.joinToString(" "), tail).filter { it.isNotEmpty() }.joinToString(" "), GuideTurn.Kind.RESULT)
        return null
    }

    /** The gate held the tap (send / order): Settings first, then the Confirm sheet; only a clear yes runs it. */
    private suspend fun held(r: Exec.Held, s: Screen, before: ScreenView, ctx: CommandContext): Outcome? {
        val refusal: String? = when (r.verdict.rule) {
            "R3" -> {
                val p = policy.grocery
                val total = BlinkitReadback.cartTotal(s)
                val amount = Blinkit.rupees(total)
                when {
                    p.level != GroceryLevel.ASK_FIRST -> t.groceryReadOnly
                    !p.allowOrders -> orderTexts.orderingOff
                    amount == null -> orderTexts.noTotal
                    amount > p.spendingLimit -> orderTexts.overLimit(total, p.spendingLimit)
                    else -> null
                }
            }
            "R2" -> if (policy.whatsapp != AppAccess.ASK_FIRST) t.whatsappReadOnly else null
            "R1" -> t.noPayments
            else -> texts.unsafe
        }
        if (refusal != null) {
            log("guide: hold ${r.verdict.rule} refused by settings")
            say("$refusal ${t.whatNext}", GuideTurn.Kind.REFUSED)
            return null
        }
        val req = when (r.verdict.rule) {
            "R3" -> ConfirmRequest(r.verdict.readback, "R3", "", "", order = OrderSummary.from(s))
            else -> ConfirmRequest(
                readback = r.verdict.readback,
                rule = r.verdict.rule,
                recipient = WhatsApp.chatTitle(s)?.text.orEmpty(),
                message = WhatsApp.entry(s)?.text.orEmpty(),
                afterCancel = AfterCancel.LEAVE_AS_IS,
            )
        }
        io.showSaathi()
        val answer = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { io.confirm(req) } ?: Answer.UNCLEAR
        coroutineContext.ensureActive()
        log("guide: hold ${r.verdict.rule} answered yes=${answer == Answer.YES}")
        if (answer != Answer.YES) {
            val left = if (r.verdict.rule == "R3") t.notOrdered else "${t.notSent} ${t.boxKept}"
            say("${t.cancelledHold} $left ${t.whatNext}", GuideTurn.Kind.RESULT)
            return null
        }
        val pkg = r.packageName
        if (!bringOrOpen(pkg)) return sayCantReturn()
        return when (val done = device.executeConfirmed(r, Answer.YES, ctx)) {
            is Exec.Ran -> {
                if (!done.ok) { say("${t.couldNotPress} ${t.whatNext}", GuideTurn.Kind.REFUSED); return null }
                if (r.verdict.rule == "R2") sent++
                after(s, before, if (r.verdict.rule == "R3") orderTexts.placing else texts.sending, quiet = false)
            }
            is Exec.HandOff -> endHandOver(texts.verdict(done.verdict))
            is Exec.Blocked -> { say("${texts.verdict(done.verdict)} ${t.whatNext}", GuideTurn.Kind.REFUSED); null }
            is Exec.Held -> null
        }
    }

    /** Guide mode's own refusals (stricter than the gate, never instead of it). (text, handOver) or null. */
    private fun guideRefusal(a: Action, s: Screen): Pair<String, Boolean>? {
        refusalFor(s.packageName)?.let { return it to false }
        val protectedApp = s.packageName == Apps.WHATSAPP || s.packageName in Apps.ORDER_APPS || s.packageName in Apps.PAYMENT_APPS
        if (a is TypeText) {
            val e = a.index?.let { s.elements.getOrNull(it) }
            if (PrivateText.looksPrivate(a.text) || (e != null && (e.password || SECRET_REGEX.matcher("${e.text} ${e.desc}").find()))) return t.noSecrets to false
        }
        if (a is Tap) {
            val e = s.elements[a.index]
            if (GuideHooks.choosesPayment(e)) return t.choosePayment to true
            if (s.packageName in Apps.ORDER_APPS && policy.grocery.level != GroceryLevel.ASK_FIRST && device.gate.isAddButton(e, s.packageName)) return t.groceryReadOnly to false
            if (!protectedApp && RISKY_ELSEWHERE.containsMatchIn(GuideView.fullLabel(e))) return t.risky to false
        }
        if (a is OpenApp || a is app.saathi.safety.Home) return t.didntUnderstand to false
        return null
    }

    /** Settings > Apps: Off apps are never operated; Payments (Off by default) never in guide mode. */
    private fun refusalFor(pkg: String): String? = when {
        pkg == Apps.WHATSAPP && policy.whatsapp == AppAccess.OFF -> t.appOff("WhatsApp")
        pkg in Apps.ORDER_APPS && policy.grocery.level == GroceryLevel.OFF -> t.appOff(label(pkg))
        pkg in Apps.PAYMENT_APPS && policy.payments == AppAccess.OFF -> t.appOff(label(pkg))
        else -> null
    }

    /** PIN, CAPTCHA, sign-in: stop and give the phone to the user. */
    private fun handover(s: Screen): Outcome? {
        if (device.gate.isPinScreen(s)) return endHandOverNow(texts.verdict(Verdict(Kind.HANDOFF, "R8", "अपना पिन खुद डालिए, मैं रुक रहा हूँ।")))
        if (isCaptcha(s)) return endHandOverNow(texts.verdict(Verdict(Kind.HANDOFF, "R9", "यह कैप्चा आपको खुद भरना होगा।")))
        val loginField = s.elements.any { it.editable && (it.password || LOGIN_FIELD.containsMatchIn("${it.desc} ${it.rid}")) }
        val loginText = s.elements.any { LOGIN_TEXT.containsMatchIn("${it.text} ${it.desc}") }
        if (loginField && loginText) return endHandOverNow(t.loginScreen)
        return null
    }

    // -- reading the app ----------------------------------------------------------------------------

    /** The target app in front, then its screen. [settle]: wait while it is still loading. */
    private suspend fun look(settle: Boolean): Screen? {
        val pkg = target ?: return device.snapshot()
        if (!bringOrOpen(pkg)) return null
        var s = device.snapshot() ?: return null
        if (settle) {
            var tries = 0
            while (GuideView.loading(s) && tries++ < LOAD_TRIES) {
                device.waitForChange(s.hash, LOAD_WAIT_MS)
                s = device.snapshot() ?: return null
            }
        }
        if (s.packageName == ownPackage) return null
        return s
    }

    /** [pkg] in front: ask the app to show it; for the safety-list apps, a gated OpenApp as a fallback. */
    private suspend fun bringOrOpen(pkg: String): Boolean {
        if (io.showApp(pkg) && device.snapshot()?.packageName == pkg) return true
        val alias = Apps.APP_ALIASES.entries.firstOrNull { it.value == pkg }?.key ?: return false
        val now = device.snapshot() ?: return false
        if (now.packageName == pkg) return true
        if (device.execute(OpenApp(alias), now, CommandContext("")) !is Exec.Ran) return false
        repeat(20) {
            val s = device.snapshot()
            if (s?.packageName == pkg) return true
            device.waitForChange(s?.hash ?: "", 400)
        }
        return false
    }

    // -- ending -------------------------------------------------------------------------------------

    /** Where things are left (never silent changes): the cart, a typed message, the screen. */
    fun leftNote(): String {
        val v = view ?: return ""
        return when {
            ordered -> t.orderPlaced
            v.packageName in Apps.ORDER_APPS -> v.cartCount?.let { t.cartLeft(it) } ?: t.notOrdered
            v.packageName == Apps.WHATSAPP -> {
                val typed = screen?.let { WhatsApp.typedText(it) }.orEmpty()
                listOfNotNull(
                    if (sent > 0) t.sentCount(sent) else null,
                    if (typed.isNotEmpty()) t.messageLeft else if (sent == 0) t.notSent else null,
                ).joinToString(" ")
            }
            else -> t.leftOn(label(v.packageName), v.title)
        }
    }

    private fun end(text: String): Outcome = Outcome.Answered("$text ${leftNote()}".trim(), t.title)

    private fun endHandOver(text: String): Outcome = Outcome.HandedOver("$text ${leftNote()}".trim())

    private fun endHandOverNow(text: String): Outcome = endHandOver(text)
}
