package app.saathi.safety

import app.saathi.safety.Py.B_END
import app.saathi.safety.Py.B_START
import app.saathi.safety.Py.D
import app.saathi.safety.Py.S
import java.util.regex.Pattern

/*
 * Deterministic safety gate, rules R1-R11 (SYSTEM_DESIGN section 6). Never an LLM decision.
 * 1:1 port of saathi/safety.py.
 *
 * check() runs on EVERY action before execution and returns allow / hold / block / handoff.
 *   hold    -> irreversible step (pay, send, order): needs a grounded read-back + explicit yes.
 *   block   -> never execute; tell the user.
 *   handoff -> stop acting and give control back to the user (PIN, CAPTCHA).
 *
 * Also here (pure, no I/O): CommandContext (who / how much the user asked for), the spoken
 * yes/no parser for confirmations, and the black-screenshot (secure screen) detector.
 */

// -- patterns (same text as safety.py; \b \d \s rewritten to Python's Unicode meaning) -------

private val NOT_TOKEN = Py.notSpaceOr(",.:।") // Python [^\s,.:।]

val PAY_REGEX: Pattern = Py.re(
    "${B_START}(pay|send|proceed|confirm|place order)$B_END|भेजें|पे करें|भुगतान|₹",
    ignoreCase = true,
)

// Checkout buttons = the stop-and-confirm point. "Select Payment Method" is Blinkit's checkout
// button when no payment method is saved (seen on device, 12 Oct).
val ORDER_REGEX: Pattern = Py.re(
    "place order|${B_START}pay$B_END|slide to pay|select payment method|ऑर्डर करें",
    ignoreCase = true,
)

// Generic words also used on non-order screens (Blinkit's location sheet has "Proceed"):
// only a checkout when a ₹ amount is on screen.
val ORDER_GENERIC_REGEX: Pattern = Py.re("${B_START}(proceed|checkout|continue)$B_END", ignoreCase = true)

val SECRET_REGEX: Pattern = Py.re(
    "${B_START}(pin|otp|password|passcode|cvv|mpin)$B_END|पिन|ओटीपी|पासवर्ड",
    ignoreCase = true,
)

val PIN_SCREEN_REGEX: Pattern = Py.re(
    "upi pin|enter (your )?pin|enter mpin|पिन (डालें|दर्ज)|यूपीआई पिन",
    ignoreCase = true,
)

val CAPTCHA_REGEX: Pattern = Py.re("captcha|i'?m not a robot|verify you are human", ignoreCase = true)

val RISKY_REGEX: Pattern = Py.re(
    "${B_START}(delete|uninstall|log ?out|sign ?out|clear (chat|history|data)|block|report|" +
        "exit group|reset|remove|voice call|video call|call)$B_END|payments? on whatsapp|send payment|" +
        "हटाएं|डिलीट",
    ignoreCase = true,
)

val AMOUNT_REGEX: Pattern = Py.re("₹$S?([$D,]+(?:\\.$D{1,2})?)")

val UPI_HANDLE_REGEX: Pattern = Py.re("[\\p{L}\\p{N}_.\\-]{2,}@[a-zA-Z]{2,}")

// R10: never grant Android runtime permissions (location, contacts, mic...) on the user's behalf.
val PERMISSION_PACKAGES: Set<String> = setOf(
    "com.google.android.permissioncontroller",
    "com.android.permissioncontroller",
)

val PERMISSION_ALLOW_REGEX: Pattern = Py.re(
    "^(allow|while using the app|only this time|allow all the time|allow only while using)" +
        "|अनुमति दें",
    ignoreCase = true,
)

val WHATSAPP_SEND_RIDS: Set<String> = setOf("send", "send_btn")
val WHATSAPP_SEND_LABELS: Set<String> = setOf("send", "भेजें")
const val PAYEE_MATCH_MIN = 80 // rapidfuzz token_set_ratio (R5)
const val BLACK_PIXEL_MAX = 16 // luminance 0-255 counted as black
const val BLACK_SHARE_MIN = 0.95 // R8: a >= 95% black screenshot is a secure screen

private val PAYMENT_CONTROL_REGEX = Py.re("${B_START}(pay|send|proceed to pay)$B_END|भेजें|पे करें", ignoreCase = true)
private val EDITABLE_AMOUNT_REGEX = Py.re("[$D,]+(?:\\.$D{1,2})?")
val TOTAL_REGEX: Pattern = Py.re("grand total|to pay|total|bill|कुल", ignoreCase = true)
val ITEM_COUNT_REGEX: Pattern = Py.re("$B_START$D+$S+items?$B_END", ignoreCase = true)
private val QUOTED = Py.re("[\"“”]([^\"“”]{2,})[\"“”]")
val ADD_BUTTON_REGEX: Pattern = Py.re("^(add|\\+|add to cart|increase quantity|जोड़ें)$", ignoreCase = true)

// -- verdicts --------------------------------------------------------------------------------

enum class Kind(val wire: String) { ALLOW("allow"), HOLD("hold"), BLOCK("block"), HANDOFF("handoff") }

data class Verdict(
    val kind: Kind,
    val rule: String = "",
    val message: String = "", // spoken to the user (Hindi, Devanagari) on block / handoff
    val readback: String = "", // spoken before an irreversible step (hold)
) {
    companion object {
        val ALLOW = Verdict(Kind.ALLOW)
    }
}

// -- what the user asked for -------------------------------------------------------------------

private val NUMBER_WORDS: Map<String, Int> = mapOf(
    "ek" to 1, "एक" to 1, "do" to 2, "दो" to 2, "teen" to 3, "तीन" to 3, "char" to 4, "chaar" to 4, "चार" to 4,
    "paanch" to 5, "panch" to 5, "पांच" to 5, "पाँच" to 5, "chhe" to 6, "छह" to 6, "saat" to 7, "सात" to 7,
    "aath" to 8, "आठ" to 8, "nau" to 9, "नौ" to 9, "das" to 10, "दस" to 10,
)
private val NAME_BEFORE_KO = Py.re("($NOT_TOKEN+)$S+(?:ko|को)(?=$S|$)", ignoreCase = true)
private val AMOUNT_SPOKEN = Py.re(
    "(?:₹$S?)?($D+|$NOT_TOKEN+)$S*(?:rupaye?|rupay[ae]?|rupees?|rs\\.?|रुपये|रुपया|रुपए)",
    ignoreCase = true,
)
private val QUANTITY_SPOKEN = Py.re(
    "($NOT_TOKEN+)$S*(?:packets?|packs?|pcs|pieces?|bottles?|पैकेट|बोतल)",
    ignoreCase = true,
)

/**
 * Payee / recipient and amount extracted from the utterance, before any screen is seen.
 * `names` may get extra spellings (e.g. a transliteration) so a Devanagari "राहुल" can match
 * an on-screen "Rahul Sharma".
 */
data class CommandContext(
    val command: String,
    var names: List<String> = emptyList(),
    val amount: Long? = null,
    val quantity: Int = 1, // items to add to a cart ("2 packet"); sizes like "1 litre" don't count
    var itemsAdded: Int = 0, // updated by the agent after each executed ADD / + tap
) {
    companion object {
        fun fromUtterance(command: String): CommandContext {
            val names = mutableListOf<String>()
            Py.search(NAME_BEFORE_KO, command)?.let { names += it.group(1) }
            var amount: Long? = null
            Py.search(AMOUNT_SPOKEN, command)?.let { m ->
                val tok = Py.lower(m.group(1))
                amount = if (Py.isDigit(tok)) {
                    Py.saturatedLong(Py.parseInt(tok))
                } else {
                    NUMBER_WORDS[tok]?.toLong()
                }
            }
            var quantity = 1
            Py.search(QUANTITY_SPOKEN, command)?.let { q ->
                val tok = Py.lower(q.group(1))
                quantity = if (Py.isDigit(tok)) Py.saturatedInt(Py.parseInt(tok)) else NUMBER_WORDS[tok] ?: 1
            }
            return CommandContext(command = command, names = names, amount = amount, quantity = maxOf(1, quantity))
        }
    }
}

/** R5: fuzzy match between any spoken spelling and the on-screen name. No name -> true. */
fun payeeMatches(ctx: CommandContext, onScreen: String): Boolean {
    if (ctx.names.isEmpty()) return true
    val target = Py.lower(onScreen)
    return ctx.names.any { Fuzz.tokenSetRatio(Py.lower(it), target) >= PAYEE_MATCH_MIN }
}

// -- confirmation ----------------------------------------------------------------------------

val YES: Set<String> = setOf("haan", "haa", "ha", "han", "yes", "confirm", "हाँ", "हां", "हा", "जी", "ok", "okay")
val YES_PHRASES: Set<String> = setOf("kar do", "bhej do", "theek hai", "thik hai", "ठीक है", "भेज दो", "कर दो")
val NO: Set<String> = setOf(
    "nahi", "nahin", "nai", "na", "no", "cancel", "ruko", "mat", "stop",
    "नहीं", "नही", "ना", "मत", "रुको", "रुकिए", "कैंसल",
)

enum class Answer { YES, NO, UNCLEAR }

private val REPLY_TOKEN = Py.re(Py.notSpaceOr(",.!?।") + "+")

/** Any 'no' word wins (fail safe); otherwise any 'yes' word/phrase; otherwise unclear. */
fun parseYesNo(reply: String): Answer {
    val tokens = Py.findall(REPLY_TOKEN, Py.lower(reply))
    val joined = tokens.joinToString(" ")
    if (tokens.any { it in NO }) return Answer.NO
    if (tokens.any { it in YES } || YES_PHRASES.any { it in joined }) return Answer.YES
    return Answer.UNCLEAR
}

// -- screen helpers --------------------------------------------------------------------------

private fun label(e: Element): String = Py.strip("${e.text} ${e.desc}")

private fun screenTexts(screen: Screen): List<String> =
    screen.elements.flatMap { listOf(it.text, it.desc) }.filter { it.isNotEmpty() }

/**
 * R8: Android renders FLAG_SECURE windows (UPI PIN pads) black in screenshots.
 * Same as the Python: shrink to fit 200x200 (Pillow thumbnail size), then the share of
 * pixels with luminance < 16 must be >= 95%.
 */
fun isSecureScreenshot(shot: Screenshot?): Boolean {
    if (shot == null || shot.pixelCount == 0) return false
    val (w, h) = thumbnailSize(shot.width, shot.height, 200, 200) ?: (shot.width to shot.height)
    var black = 0L
    var total = 0L
    for (ty in 0 until h) {
        val sy0 = (ty.toLong() * shot.height / h).toInt()
        val sy1 = maxOf(sy0 + 1, ((ty + 1).toLong() * shot.height / h).toInt())
        for (tx in 0 until w) {
            val sx0 = (tx.toLong() * shot.width / w).toInt()
            val sx1 = maxOf(sx0 + 1, ((tx + 1).toLong() * shot.width / w).toInt())
            var sum = 0L
            var n = 0L
            for (y in sy0 until sy1) for (x in sx0 until sx1) {
                sum += shot.at(x, y)
                n++
            }
            val v = (sum + n / 2) / n
            if (v < BLACK_PIXEL_MAX) black++
            total++
        }
    }
    return black.toDouble() / maxOf(1L, total) >= BLACK_SHARE_MIN
}

/** Pillow Image.thumbnail's target size (preserve aspect ratio); null if no resize happens. */
internal fun thumbnailSize(width: Int, height: Int, maxW: Int, maxH: Int): Pair<Int, Int>? {
    var x = maxW
    var y = maxH
    if (x >= width && y >= height) return null
    val aspect = width.toDouble() / height
    fun roundAspect(number: Double, key: (Int) -> Double): Int {
        val lo = Math.floor(number).toInt()
        val hi = Math.ceil(number).toInt()
        return maxOf(if (key(hi) < key(lo)) hi else lo, 1) // min() keeps the first on ties
    }
    if (x.toDouble() / y >= aspect) {
        val yy = y
        x = roundAspect(y * aspect) { n -> Math.abs(aspect - n.toDouble() / yy) }
    } else {
        val xx = x
        y = roundAspect(x / aspect) { n -> if (n == 0) 0.0 else Math.abs(aspect - xx.toDouble() / n) }
    }
    return x to y
}

/** R9. */
fun isCaptcha(screen: Screen): Boolean = screenTexts(screen).any { Py.search(CAPTCHA_REGEX, it) != null }

/** R4 for read-outs: text Saathi quotes to the user must be verbatim on screen. */
fun ungroundedQuotes(summary: String, screen: Screen): List<String> {
    val texts = screenTexts(screen)
    return Py.findall(QUOTED, summary).filter { q -> texts.none { Py.strip(q) in it } }
}

/** R4: every read-back string must appear verbatim in the current screen. */
fun grounded(parts: List<String>, screen: Screen): Boolean {
    val texts = screenTexts(screen)
    return parts.all { p -> p.isNotEmpty() && texts.any { p in it } }
}

/** Every ₹ amount on screen, plus a bare number typed into an editable amount field. */
fun screenAmounts(screen: Screen): List<Double> {
    val found = mutableListOf<Double>()
    for (e in screen.elements) {
        for (t in listOf(e.text, e.desc)) {
            found += Py.findall(AMOUNT_REGEX, t).map { Py.parseFloat(it.replace(",", "")) }
        }
        if (e.editable && Py.fullmatch(EDITABLE_AMOUNT_REGEX, e.text)) {
            found += Py.parseFloat(e.text.replace(",", ""))
        }
    }
    return found
}

// -- the gate --------------------------------------------------------------------------------

/** The safety gate. Config-dependent rules live here; the pure helpers above are shared. */
class SafetyGate(val config: SafetyConfig = SafetyConfig()) {

    /** A Blinkit-style ADD / + button that puts one more item in the cart. */
    fun isAddButton(e: Element, packageName: String): Boolean =
        packageName in config.orderApps &&
            (Py.match(ADD_BUTTON_REGEX, Py.strip(e.text)) != null || Py.match(ADD_BUTTON_REGEX, Py.strip(e.desc)) != null)

    /** R8: PIN entry screen (by text, or a black secure screenshot) -> stop and hand off. */
    fun isPinScreen(screen: Screen): Boolean =
        screenTexts(screen).any { Py.search(PIN_SCREEN_REGEX, it) != null } ||
            (screen.packageName in config.paymentApps && isSecureScreenshot(screen.screenshot))

    private fun isIrreversibleControl(e: Element, packageName: String): Boolean {
        val label = label(e)
        if (packageName == config.whatsapp) {
            return e.rid in WHATSAPP_SEND_RIDS || Py.lower(e.desc) in WHATSAPP_SEND_LABELS
        }
        if (packageName in config.paymentApps) return Py.search(PAYMENT_CONTROL_REGEX, label) != null
        if (packageName in config.orderApps) return Py.search(ORDER_REGEX, label) != null
        return false
    }

    /**
     * R11: an irreversible control (order / pay / send) other than the target that contains
     * the tap point. Returns (control, enclosesTarget): a fully enclosed target is part of that
     * control (e.g. the "TOTAL" text in the checkout bar), so the tap IS a tap on the control.
     */
    fun coveredByIrreversible(target: Element, screen: Screen): Pair<Element, Boolean>? {
        if (isIrreversibleControl(target, screen.packageName)) {
            return null // tapping the control itself is handled by R1-R3 holds
        }
        val (x, y) = target.center
        val (tx1, ty1, tx2, ty2) = target.bounds
        for (e in screen.elements) {
            if (e === target || !isIrreversibleControl(e, screen.packageName)) continue
            val (x1, y1, x2, y2) = e.bounds
            if (x1 <= x && x <= x2 && y1 <= y && y <= y2) {
                return e to (x1 <= tx1 && y1 <= ty1 && tx2 <= x2 && ty2 <= y2)
            }
        }
        return null
    }

    // -- read-backs ------------------------------------------------------------------------

    /** R2 (+R4, R5): read back recipient + message text, both verbatim from the screen. */
    private fun whatsappSend(screen: Screen, ctx: CommandContext): Verdict {
        val byRid = HashMap<String, Element>()
        for (e in screen.elements) byRid[e.rid] = e // last one wins, like a Python dict literal
        val recipient = byRid["conversation_contact_name"]
        val entry = byRid["entry"]
        if (recipient == null || recipient.text.isEmpty() || entry == null || entry.text.isEmpty()) {
            return Verdict(Kind.BLOCK, "R2", "संदेश या नाम स्क्रीन पर साफ़ नहीं है, मैं रुक रहा हूँ।")
        }
        if (!payeeMatches(ctx, recipient.text)) {
            return Verdict(Kind.BLOCK, "R5", "यह चैट ${recipient.text} की है, आपने किसी और का नाम लिया था।")
        }
        if (!grounded(listOf(recipient.text, entry.text), screen)) {
            return Verdict(Kind.BLOCK, "R4", "पढ़कर सुनाने वाली बात स्क्रीन पर नहीं मिली।")
        }
        return Verdict(Kind.HOLD, "R2", readback = "${recipient.text} को भेज रहे हैं: \"${entry.text}\". भेज दूँ?")
    }

    /** R1 (+R4, R5, R6): payee + UPI handle + amount, each taken verbatim from the screen. */
    private fun payment(screen: Screen, ctx: CommandContext, cap: Int): Verdict {
        val amounts = screenAmounts(screen)
        if (amounts.isEmpty()) {
            return Verdict(Kind.BLOCK, "R6", "स्क्रीन पर रकम नहीं दिखी, मैं रुक रहा हूँ।")
        }
        if (amounts.max() > cap) {
            return Verdict(Kind.BLOCK, "R6", "रकम $cap रुपये की सीमा से ज़्यादा है, मैं नहीं भेजूँगा।")
        }
        val spoken = ctx.amount
        if (spoken != null && spoken.toDouble() !in amounts) {
            return Verdict(Kind.BLOCK, "R6", "आपने $spoken रुपये बोले थे, स्क्रीन पर रकम अलग है।")
        }
        val texts = screenTexts(screen)
        val payee = texts.firstOrNull { ctx.names.isNotEmpty() && payeeMatches(ctx, it) }
        if (ctx.names.isNotEmpty() && payee == null) {
            return Verdict(Kind.BLOCK, "R5", "स्क्रीन पर वही नाम नहीं मिला जो आपने बोला था।")
        }
        val handle = texts.firstNotNullOfOrNull { Py.search(UPI_HANDLE_REGEX, it)?.group() } ?: ""
        val amountText = texts.firstOrNull { Py.search(AMOUNT_REGEX, it) != null } ?: ""
        val parts = listOfNotNull(payee, handle, amountText).filter { it.isNotEmpty() }
        if (amountText.isEmpty() || !grounded(parts, screen)) {
            return Verdict(Kind.BLOCK, "R4", "पढ़कर सुनाने वाली बात स्क्रीन पर नहीं मिली।")
        }
        val who = listOfNotNull(payee, handle).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "इस व्यक्ति" }
        return Verdict(Kind.HOLD, "R1", readback = "$amountText $who को भेज रहे हैं। पक्का?")
    }

    /** R3: prefer the itemised Blinkit cart read-back; fall back to the on-screen total. */
    private fun order(screen: Screen): Verdict {
        val cart = BlinkitReadback.orderReadback(screen)
        if (cart != null) {
            val (text, parts) = cart
            if (!grounded(parts, screen)) {
                return Verdict(Kind.BLOCK, "R4", "पढ़कर सुनाने वाली बात स्क्रीन पर नहीं मिली।")
            }
            return Verdict(Kind.HOLD, "R3", readback = text)
        }
        return orderTotal(screen)
    }

    /**
     * R3 (+R4): read back the order total, verbatim from the screen. Prefers a text labelled
     * total / to pay; otherwise the largest ₹ amount on screen (never a single item price).
     */
    private fun orderTotal(screen: Screen): Verdict {
        val texts = screenTexts(screen).filter { Py.search(AMOUNT_REGEX, it) != null }
        val labelled = texts.filter { Py.search(TOTAL_REGEX, it) != null }

        fun value(t: String): Double = Py.findall(AMOUNT_REGEX, t).maxOf { Py.parseFloat(it.replace(",", "")) }

        // Python: (labelled or sorted(texts, key=value, reverse=True) or [""])[0]; both sorts are stable.
        val total = labelled.ifEmpty { texts.sortedByDescending { value(it) } }.firstOrNull() ?: ""
        if (total.isEmpty()) {
            return Verdict(Kind.BLOCK, "R3", "कुल रकम स्क्रीन पर नहीं दिखी, मैं रुक रहा हूँ।")
        }
        val count = screenTexts(screen).firstNotNullOfOrNull { Py.search(ITEM_COUNT_REGEX, it)?.group() } ?: ""
        val what = if (count.isNotEmpty() && count !in total) "$count, $total" else total
        return Verdict(Kind.HOLD, "R3", readback = "ऑर्डर: $what. ऑर्डर कर दूँ?")
    }

    // -- check -----------------------------------------------------------------------------

    fun check(
        action: Action,
        screen: Screen,
        ctx: CommandContext? = null,
        cap: Int = config.maxAmountInr,
    ): Verdict {
        val c = ctx ?: CommandContext(command = "")
        if (isPinScreen(screen)) {
            return Verdict(Kind.HANDOFF, "R8", "अपना पिन खुद डालिए, मैं रुक रहा हूँ।")
        }
        if (isCaptcha(screen)) {
            return Verdict(Kind.HANDOFF, "R9", "यह कैप्चा आपको खुद भरना होगा।")
        }

        var target: Element? = null
        val index = when (action) {
            is Tap -> action.index
            is TypeText -> action.index
            else -> null
        }
        if (index != null) {
            if (index >= screen.elements.size) {
                return Verdict(Kind.BLOCK, "index", "यह बटन स्क्रीन पर नहीं मिला।")
            }
            target = screen.elements[index]
        }

        if (action is Search) {
            return Verdict.ALLOW // only opens search results; nothing is bought or sent
        }
        if (action is OpenApp && Py.strip(Py.lower(action.app)) !in config.appAliases) {
            return Verdict(Kind.BLOCK, "app", "यह ऐप मेरी सूची में नहीं है।")
        }

        if (action is TypeText) {
            // R7: never type into secret fields (also covers "type" with no index on a password form).
            if (target != null && (target.password || Py.search(SECRET_REGEX, label(target)) != null)) {
                return Verdict(Kind.BLOCK, "R7", "पिन, ओटीपी या पासवर्ड मैं नहीं डालूँगा।")
            }
            if (target == null && screen.elements.any { it.editable && it.password }) {
                return Verdict(Kind.BLOCK, "R7", "पिन, ओटीपी या पासवर्ड मैं नहीं डालूँगा।")
            }
            // R2: a newline can send the message when WhatsApp's "Enter is send" is on.
            if (screen.packageName == config.whatsapp && "\n" in action.text) {
                return Verdict(Kind.BLOCK, "R2", "संदेश भेजने से पहले आपसे पूछूँगा।")
            }
            return Verdict.ALLOW
        }

        if (action is Tap) {
            val t = checkNotNull(target)
            val label = label(t)
            val covering = coveredByIrreversible(t, screen)
            if (covering != null && covering.second) {
                // Part of the control: same verdict as tapping the control (hold + read-back).
                return check(Tap(index = covering.first.index), screen, c, cap)
            }
            if (covering != null) {
                // R11: taps land on whatever is on top at those coordinates. A floating
                // "Place Order" / "Pay" / "Send" bar over the target would turn a harmless tap
                // into an irreversible one (seen on Blinkit's checkout in landscape, 12 Oct).
                return Verdict(Kind.BLOCK, "R11", "यह बटन किसी दूसरे बटन के नीचे दबा है, मैं नहीं दबाऊँगा।")
            }
            if (screen.packageName == config.whatsapp && (
                    t.rid in WHATSAPP_SEND_RIDS ||
                        Py.lower(t.desc) in WHATSAPP_SEND_LABELS ||
                        Py.lower(t.text) in WHATSAPP_SEND_LABELS
                    )
            ) {
                return whatsappSend(screen, c) // R2
            }
            if (screen.packageName in config.paymentApps && Py.search(PAY_REGEX, label) != null) {
                return payment(screen, c, cap) // R1
            }
            if (screen.packageName in config.orderApps && (
                    Py.search(ORDER_REGEX, label) != null ||
                        (Py.search(ORDER_GENERIC_REGEX, label) != null && screenAmounts(screen).isNotEmpty())
                    )
            ) {
                return order(screen) // R3
            }
            // R3: never put more in the cart than the user asked for.
            if (isAddButton(t, screen.packageName) && c.itemsAdded >= c.quantity) {
                return Verdict(Kind.BLOCK, "R3", "${c.itemsAdded} आइटम जोड़ दिया है, अब कार्ट खोल रहा हूँ।")
            }
            // R10: permissions
            if ((screen.packageName in PERMISSION_PACKAGES || t.rid.startsWith("permission_allow")) &&
                Py.search(PERMISSION_ALLOW_REGEX, label) != null
            ) {
                return Verdict(Kind.BLOCK, "R10", "मैं आपकी तरफ़ से कोई अनुमति नहीं दूँगा।")
            }
            // R10: risky misc
            if (Py.search(RISKY_REGEX, label) != null) {
                return Verdict(Kind.BLOCK, "R10", "यह काम आपकी बात का हिस्सा नहीं है, मैं नहीं करूँगा।")
            }
        }
        return Verdict.ALLOW
    }
}
