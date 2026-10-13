package app.saathi.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Guide mode's smart understanding: when the offline rule parser ([GuideGrammar]) has no exact
 * command for what the user said, ask the on-device model ([Brain]) what they meant, check its
 * answer, and turn it into a normal [GuideCommand] that the guide runs through the safety gate.
 *
 * Order of trust (each step can only narrow, never widen, what happens):
 *  1. An exact rule command (or a name that resolves on screen) runs as before; the model isn't asked.
 *  2. The model's JSON ([BrainParser]); invalid -> one retry with a shorter prompt; forbidden
 *     intent (yes, send, place_order, pay …) or timeout -> no model answer.
 *  3. Which item / button: first from the user's OWN words, resolved on the screen ([GuideTargets]);
 *     only if that fails, the model's target. A target from the model is a guess: the user picks it
 *     on the "which one?" sheet (VoiceAnswer pipeline, Cancel is the default). Never added on a guess.
 *  4. What Saathi says: facts (prices, cart) come from the screen by code; the model's own reply is
 *     only used for small talk / a question, and only if [BrainParser.grounded]; else a template.
 *  5. No model answer: the rule parser's command; if that was nothing, "I heard '…'. Did you mean …?"
 */
class GuideBrain(
    private val brain: Brain,
    private val lang: Lang,
    private val onThinking: (Boolean) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val timeoutMs: Long = TIMEOUT_MS,
    private val retryTimeoutMs: Long = RETRY_TIMEOUT_MS,
    /** May this app's screen go to the model? False: rules only (e.g. WhatsApp when the model runs on the laptop). */
    private val mayRead: (packageName: String) -> Boolean = { true },
) {
    companion object {
        const val TIMEOUT_MS = 6_000L
        const val RETRY_TIMEOUT_MS = 4_000L
        const val MEMORY_TURNS = 4
        const val MAX_CHOICES = 3

        /** Words that say what to do, not which thing: left out when matching the user's words to the screen. */
        private val STOP: Set<String> = setOf(
            "add", "order", "put", "get", "buy", "want", "need", "remove", "delete", "minus", "plus", "increase", "decrease", "reduce",
            "more", "another", "one", "ones", "please", "plz", "can", "could", "would", "will", "you", "i", "me", "my", "mine", "the", "a",
            "an", "in", "into", "to", "from", "of", "for", "on", "at", "and", "also", "some", "any", "give", "it", "this", "that", "these",
            "those", "cart", "card", "kart", "carts", "basket", "bag", "is", "are", "am", "be", "was", "do", "does", "did", "have", "has",
            "what", "which", "how", "much", "many", "total", "cheaper", "cheapest", "price", "prices", "cost", "tap", "press", "click",
            "open", "select", "choose", "pick", "show", "see", "find", "search", "look", "go", "there", "here", "now", "just", "like",
            "let's", "lets", "us", "we", "ok", "okay", "hey", "hi", "hello", "saathi", "sathi", "wala", "wali", "wale", "mujhe", "muje",
            "chahiye", "chaiye", "chahie", "chaahiye", "dena", "dedo", "de", "daal", "daalo", "dalo", "daaldo", "karo", "kar", "kardo",
            "hatao", "hata", "nikalo", "nikal", "ek", "aur", "ko", "ka", "ki", "ke", "mein", "me", "main", "se", "hai", "hain", "bhi",
            "aap", "tum", "kya", "kitna", "kitne", "hua", "sasta", "kaunsa", "konsa", "isko", "usko", "ise", "use", "yeh", "ye", "woh",
            "wo", "zara", "jara", "na", "ji", "mangao", "mangwao", "lao", "le", "lo", "lena", "chahta", "chahti", "hoon", "hu", "hun",
            "मुझे", "चाहिए", "डालो", "डाल", "दो", "दे", "हटाओ", "हटा", "निकालो", "कार्ट", "में", "का", "की", "के", "को", "है", "हैं", "एक",
            "और", "भी", "क्या", "कितना", "हुआ", "सस्ता", "वाला", "वाली", "वाले", "इसको", "इसे", "उसको", "उसे", "यह", "वह", "करो", "कर",
            "जोड़ो", "जोड़", "लाओ", "मंगाओ",
        ).flatMap { EchoGuard.tokens(it) }.toSet()

        private val NUMBER_WORDS: Map<String, Int> = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
            "ten" to 10, "ek" to 1, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5, "chhe" to 6,
            "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10, "एक" to 1, "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5,
            "छह" to 6, "सात" to 7, "आठ" to 8, "नौ" to 9, "दस" to 10,
        ).mapKeys { EchoGuard.tokens(it.key).single() }

        /** Replies that only answer a question (yes / no / ok / send): never sent to the model. */
        private val ANSWER_ONLY: Set<String> = (
            ConfirmDialog.YES_WORDS + app.saathi.safety.YES + app.saathi.safety.NO +
                setOf("ok", "okay", "send", "bhejo", "bhej", "do", "it", "sure", "ji", "theek", "thik", "hai", "please", "confirm", "भेजो", "ठीक", "है")
            ).flatMap { EchoGuard.tokens(it) }.toSet()

        private val TOTAL_CUE = Regex("total|kitna|kitne|how much|कितना|कितने|टोटल", RegexOption.IGNORE_CASE)
        private val CHECKOUT_CUE = Regex("check ?out|place|order|pay|buy|kharid|चेकआउट|ऑर्डर|खरीद", RegexOption.IGNORE_CASE)
        private val DONE_CUE = Regex("\\b(done|stop|finish|finished|enough|bye|that'?s all|that is all|bas|ho gaya|ho gya|hogaya|khatam|band)\\b|बस|हो गया|खत्म|ख़त्म|बंद", RegexOption.IGNORE_CASE)
    }

    private val t = GuideTexts(lang)
    private val bt = BrainTexts(lang)
    private val memory = ArrayDeque<BrainTurn>()
    private var pendingUser: String? = null

    /** The conversation memory (last [MEMORY_TURNS] exchanges of this session). */
    val history: List<BrainTurn> get() = memory.toList()

    /** Debug/test: what the model was last asked for and how it ended ("rules", "model:add_item", "fallback" …). */
    var lastRoute: String = ""
        private set

    // -- when to ask --------------------------------------------------------------------------

    /** Ask the model only when the rules have no exact command (or the name they caught isn't on screen). */
    fun wants(parsed: GuideCommand, utterance: String, view: ScreenView?): Boolean {
        if (view == null) return false
        val words = EchoGuard.tokens(utterance)
        if (words.isEmpty() || words.all { it in ANSWER_ONLY }) return false
        fun unresolvedTarget(q: String, min: Double) =
            q.isNotBlank() && q.lowercase() !in GuideGrammar.PRONOUNS && GuideTargets.resolve(q, GuideTargets.all(view), min) == Resolved.None
        fun unresolvedItem(q: String) =
            view.cards.isNotEmpty() && q.isNotBlank() && q.lowercase() !in GuideGrammar.PRONOUNS && resolveCards(q, view.cards).isEmpty()
        return when (parsed) {
            is GuideCommand.Unknown -> true
            is GuideCommand.Tap -> unresolvedTarget(parsed.target, if (parsed.explicit) GuideTargets.MIN_SCORE else GuideTargets.MIN_BARE)
            is GuideCommand.Add -> unresolvedItem(parsed.item)
            is GuideCommand.Want -> unresolvedItem(parsed.item)
            is GuideCommand.Remove -> unresolvedItem(parsed.item)
            is GuideCommand.Increase -> unresolvedItem(parsed.item)
            else -> false
        }
    }

    // -- the turn -------------------------------------------------------------------------------

    /**
     * What to run for [utterance]. Returns [parsed] when the rules decide (or the model is off /
     * still loading), another [GuideCommand] from the model's answer, or null when this turn is
     * already answered ([say] was called: small talk, a price comparison, a question, a cancelled
     * pick). [lastSaid]: what Saathi said last (the memory's answer to the previous utterance).
     */
    suspend fun understand(
        utterance: String,
        parsed: GuideCommand,
        view: ScreenView?,
        appLabel: String,
        lastSaid: String,
        say: suspend (String) -> Unit,
        pick: suspend (PickRequest) -> Int?,
    ): GuideCommand? {
        remember(utterance, lastSaid)
        if (view == null || !wants(parsed, utterance, view)) { lastRoute = "rules"; return parsed }
        if (!mayRead(view.packageName)) { lastRoute = "rules (app kept on the phone)"; return parsed }
        if (!brain.ready) { lastRoute = "rules (model not ready)"; return parsed }
        val summary = ScreenSummary.of(view, appLabel)
        val input = BrainInput(utterance, summary, memory.toList().takeLast(MEMORY_TURNS), lang)
        val d = ask(input)
        if (d == null) {
            lastRoute = "fallback"
            return fallback(parsed, utterance, view, say)
        }
        lastRoute = "model:${d.intent.wire}"
        log("brain: intent ${d.intent.wire}") // never the words
        return run(d, utterance, view, summary, say, pick)
    }

    private fun remember(utterance: String, lastSaid: String) {
        pendingUser?.let { prev ->
            memory.addLast(BrainTurn(prev.take(100), lastSaid.take(160)))
            while (memory.size > MEMORY_TURNS) memory.removeFirst()
        }
        pendingUser = utterance
    }

    /** The model's checked answer, or null (no answer in time, invalid twice, or forbidden). */
    private suspend fun ask(input: BrainInput): BrainDecision? {
        onThinking(true)
        try {
            val first = complete(BrainPrompt.full(input), timeoutMs) ?: run { log("brain: no answer in time"); return null }
            when (val p = BrainParser.parse(first)) {
                is BrainParse.Ok -> return p.decision
                is BrainParse.Forbidden -> { log("brain: forbidden intent rejected"); return null }
                is BrainParse.Invalid -> log("brain: invalid answer (${p.why}), retrying shorter")
            }
            val second = complete(BrainPrompt.short(input), retryTimeoutMs) ?: return null
            return when (val p = BrainParser.parse(second)) {
                is BrainParse.Ok -> p.decision
                is BrainParse.Forbidden -> { log("brain: forbidden intent rejected"); null }
                is BrainParse.Invalid -> { log("brain: invalid again (${p.why})"); null }
            }
        } finally {
            onThinking(false)
        }
    }

    private suspend fun complete(prompt: String, ms: Long): String? = try {
        withTimeoutOrNull(ms + 500) { brain.complete(prompt, ms) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("brain: error ${e::class.simpleName}")
        null
    }

    // -- no model answer -------------------------------------------------------------------------

    private suspend fun fallback(parsed: GuideCommand, utterance: String, view: ScreenView, say: suspend (String) -> Unit): GuideCommand? {
        val vague = parsed is GuideCommand.Unknown || (parsed is GuideCommand.Tap && !parsed.explicit)
        if (!vague) return parsed
        say(bt.heardDidYouMean(utterance, suggestions(utterance, view), t.didntUnderstand))
        return null
    }

    /** Up to two things on screen closest to the user's words, as commands they can say. */
    private fun suggestions(utterance: String, view: ScreenView): List<String> {
        val q = content(utterance)
        if (q.isBlank()) return emptyList()
        val cards = view.cards.map { c -> c to GuideTargets.score(q, c.label) }.filter { it.second >= 50 }.sortedByDescending { it.second }
        if (cards.isNotEmpty()) return cards.take(2).map { bt.addCommand(it.first.label) }
        return GuideTargets.all(view).filter { it.card == null }
            .map { it to GuideTargets.score(q, it.label) }.filter { it.second >= 50 }.sortedByDescending { it.second }
            .take(2).map { bt.tapCommand(it.first.label) }
    }

    // -- the model's answer -> a guide command -----------------------------------------------------

    private suspend fun run(
        d: BrainDecision,
        utterance: String,
        view: ScreenView,
        summary: ScreenSummary,
        say: suspend (String) -> Unit,
        pick: suspend (PickRequest) -> Int?,
    ): GuideCommand? = when (d.intent) {
        BrainIntent.ADD_ITEM, BrainIntent.REMOVE_ITEM, BrainIntent.INCREASE -> item(d, utterance, view, say, pick)
        BrainIntent.TAP -> tap(d, utterance, view, say, pick)
        BrainIntent.OPEN_CART -> GuideCommand.OpenCart
        // Checkout only when the user's own words ask for it (it is still gated: the order needs the Confirm sheet).
        BrainIntent.CHECKOUT -> if (CHECKOUT_CUE.containsMatchIn(utterance)) GuideCommand.Checkout else GuideCommand.OpenCart
        BrainIntent.SEARCH -> search(d, utterance, summary, say)
        BrainIntent.SCROLL_DOWN -> GuideCommand.Scroll(true)
        BrainIntent.SCROLL_UP -> GuideCommand.Scroll(false)
        BrainIntent.BACK -> GuideCommand.Back
        BrainIntent.READ_MORE -> GuideCommand.More
        BrainIntent.WHAT_ON_SCREEN -> GuideCommand.WhatsOnScreen
        // Facts about the cart and prices are read from the screen by the guide's own code, never by the model.
        BrainIntent.CART_SUMMARY -> if (TOTAL_CUE.containsMatchIn(utterance)) GuideCommand.Total else GuideCommand.Cart
        BrainIntent.DONE -> if (DONE_CUE.containsMatchIn(utterance)) GuideCommand.Done else { say(chat(d, utterance, summary)); null }
        BrainIntent.COMPARE_PRICES -> GuideCommand.Cheaper
        BrainIntent.ASK_CLARIFY -> { say(clarify(d, utterance, view, summary)); null }
        BrainIntent.CHIT_CHAT -> { say(chat(d, utterance, summary)); null }
    }

    private suspend fun item(d: BrainDecision, utterance: String, view: ScreenView, say: suspend (String) -> Unit, pick: suspend (PickRequest) -> Int?): GuideCommand? {
        val cards = view.cards
        if (cards.isEmpty()) {
            if (d.intent == BrainIntent.ADD_ITEM) searchWords(utterance, d, null)?.let { return GuideCommand.Search(it) }
            say("${bt.noProducts} ${t.whatNext}")
            return null
        }
        val pool = if (d.intent != BrainIntent.ADD_ITEM && cards.any { it.qty > 0 }) cards.filter { it.qty > 0 } else cards
        val qty = quantity(d, utterance)
        fun command(c: GuideCard): GuideCommand = when (d.intent) {
            BrainIntent.REMOVE_ITEM -> GuideCommand.Remove(c.label)
            BrainIntent.INCREASE -> GuideCommand.Increase(c.label)
            else -> GuideCommand.Add(c.label, qty)
        }
        // 1) The user's own words (or "the first one"), resolved on screen: not a guess.
        val own = ordinalCard(utterance, pool)?.let { listOf(it) } ?: resolveCards(content(utterance), pool)
        if (own.size == 1) return command(own[0])
        if (own.size > 1) return choose(own, t.whichOne(own.take(MAX_CHOICES).map(::option)), say, pick, ::option)?.let(::command)
        // 2) The model's target: a guess. The user picks it on the sheet (Cancel is the default).
        val guess = d.target?.let { resolveCards(it, pool) }.orEmpty()
        if (guess.isEmpty()) {
            say("${t.cantSee(d.target ?: content(utterance).ifBlank { utterance.take(40) }, pool.map { it.label }.distinct().take(4))} ${t.whatNext}")
            return null
        }
        return choose(guess, bt.didYouMean(d.intent, guess.take(MAX_CHOICES).map(::option)), say, pick, ::option)?.let(::command)
    }

    private suspend fun tap(d: BrainDecision, utterance: String, view: ScreenView, say: suspend (String) -> Unit, pick: suspend (PickRequest) -> Int?): GuideCommand? {
        val targets = GuideTargets.all(view)
        fun command(g: GuideTarget) = GuideCommand.Tap(if (g.context.isEmpty()) g.label else "${g.label} ${g.context}", explicit = true)
        fun found(q: String): List<GuideTarget> = if (q.isBlank()) emptyList() else when (val r = GuideTargets.resolve(q, targets, GuideTargets.MIN_SCORE)) {
            is Resolved.One -> listOf(r.target)
            is Resolved.Many -> r.targets
            Resolved.None -> emptyList()
        }
        val own = found(content(utterance))
        if (own.size == 1) return command(own[0])
        if (own.size > 1) {
            val shown = own.take(MAX_CHOICES)
            return choose(shown, t.whichOne(shown.map { it.option }), say, pick, label = { it.option })?.let(::command)
        }
        val guess = d.target?.let(::found).orEmpty().take(MAX_CHOICES)
        if (guess.isEmpty()) {
            val seen = view.items.filter { it.element != null && it.name.isNotEmpty() }.map { it.card?.label ?: it.name }.distinct().take(4)
            say("${t.cantSee(d.target ?: content(utterance).ifBlank { utterance.take(40) }, seen)} ${t.whatNext}")
            return null
        }
        return choose(guess, bt.didYouMean(BrainIntent.TAP, guess.map { it.option }), say, pick, label = { it.option })?.let(::command)
    }

    private suspend fun search(d: BrainDecision, utterance: String, summary: ScreenSummary, say: suspend (String) -> Unit): GuideCommand? {
        val q = searchWords(utterance, d, summary)
        if (q == null) { say("${bt.whatToSearch} ${t.whatNext}"); return null }
        return GuideCommand.Search(q)
    }

    /** Search words the user actually said (Hindi grocery words in English), or the model's, if every word of it was said / is on screen. */
    private fun searchWords(utterance: String, d: BrainDecision, summary: ScreenSummary?): String? {
        val heard = PickDialog.words(utterance).toSet()
        val screen = summary?.let { EchoGuard.tokens(it.quotable.replace("₹", " ")).toSet() }.orEmpty()
        for (candidate in listOfNotNull(d.query, d.target)) {
            val w = PickDialog.words(candidate)
            if (w.isNotEmpty() && w.all { it in heard || it in screen } && !PrivateText.looksPrivate(candidate)) return candidate.trim().take(60)
        }
        return content(utterance).ifBlank { null }?.take(60)
    }

    /** A pick sheet over [options] (verbatim labels). The chosen one, or null (said: nothing pressed). */
    private suspend fun <T> choose(
        options: List<T>,
        question: String,
        say: suspend (String) -> Unit,
        pick: suspend (PickRequest) -> Int?,
        label: (T) -> String,
    ): T? {
        val shown = options.take(MAX_CHOICES)
        val texts = shown.map(label)
        if (texts.distinct().size != texts.size) {
            say("${t.whichOne(texts.distinct())} ${t.whatNext}")
            return null
        }
        val i = pick(PickRequest(question, texts, t.pickHint))
        if (i == null || i !in shown.indices) {
            say("${t.pickCancelled} ${t.whatNext}")
            return null
        }
        return shown[i]
    }

    private fun option(c: GuideCard): String = listOf(c.label, c.price).filter { it.isNotEmpty() }.joinToString(", ")

    /** The quantity the user said (digits or a number word, 1..10), else the model's only if said too, else 1. */
    private fun quantity(d: BrainDecision, utterance: String): Int {
        val said = EchoGuard.tokens(utterance).mapNotNull { w -> w.toIntOrNull() ?: NUMBER_WORDS[w] }.filter { it in 1..BrainParser.MAX_QTY }
        val m = d.quantity
        return when {
            m != null && m in said -> m
            said.size == 1 -> said[0]
            else -> 1
        }
    }

    // -- what Saathi says itself ----------------------------------------------------------------

    private fun clarify(d: BrainDecision, utterance: String, view: ScreenView, summary: ScreenSummary): String {
        val reply = BrainParser.twoSentences(d.reply)
        if (BrainParser.grounded(reply, summary, utterance)) return reply
        val seen = view.items.filter { it.element != null && it.name.isNotEmpty() }.map { it.card?.label ?: it.name }.distinct().take(4)
        return "${bt.notSure} ${if (seen.isEmpty()) "" else "${t.iSee} ${seen.joinToString("; ")}."} ${t.whatNext}".replace("  ", " ")
    }

    private fun chat(d: BrainDecision, utterance: String, summary: ScreenSummary): String {
        val reply = BrainParser.twoSentences(d.reply)
        val said = if (BrainParser.grounded(reply, summary, utterance)) reply else bt.here
        return if (said.trimEnd().endsWith("?")) said else "$said ${t.whatNext}"
    }

    // -- matching the user's words to the screen -------------------------------------------------

    /** The user's words that name a thing: command words, fillers and quantities dropped, Hindi grocery words in English. */
    internal fun content(utterance: String): String =
        EchoGuard.tokens(utterance.replace("₹", " "))
            .filter { it !in STOP && it !in NUMBER_WORDS && Ordinals.of(it) == null && !(it.toIntOrNull()?.let { n -> n <= BrainParser.MAX_QTY } ?: false) }
            .map { OrderParser.english(it)?.lowercase() ?: it }
            .joinToString(" ")

    /** "the first one", "doosra wala", "last": a card by its place in what was read, when nothing else is named. */
    private fun ordinalCard(utterance: String, cards: List<GuideCard>): GuideCard? {
        if (content(utterance).isNotBlank()) return null
        val i = EchoGuard.tokens(utterance).firstNotNullOfOrNull { Ordinals.of(it) } ?: return null
        return if (i == Ordinals.LAST) cards.lastOrNull() else cards.getOrNull(i)
    }

    /** Cards [query] names (deterministic fuzzy match, as the guide itself resolves). */
    private fun resolveCards(query: String, cards: List<GuideCard>): List<GuideCard> {
        if (query.isBlank() || cards.isEmpty()) return emptyList()
        val targets = cards.map { GuideTarget(option(it), it.name, it.size, it.container, it) }
        return when (val r = GuideTargets.resolve(query, targets)) {
            is Resolved.One -> listOfNotNull(r.target.card)
            is Resolved.Many -> r.targets.mapNotNull { it.card }
            Resolved.None -> emptyList()
        }
    }
}

/**
 * Smart understanding's own words. Like [GuideTexts]: no yes word and no position word, so a real
 * "yes" / "first" on the sheet that follows can't be mistaken for an echo of these lines.
 */
class BrainTexts(val lang: Lang) {
    private fun s(en: String, hi: String): String = if (lang == Lang.HI) hi else en

    /** The "which one?" sheet's question for a target the model guessed. The sheet's hint says how to answer. */
    fun didYouMean(intent: BrainIntent, options: List<String>): String {
        val what = options.joinToString(s(", or ", ", या "))
        return when (intent) {
            BrainIntent.REMOVE_ITEM -> s("To take one out, I think you mean: $what.", "एक निकालने के लिए, शायद आप यह कह रहे हैं: $what.")
            BrainIntent.INCREASE -> s("To put in one more, I think you mean: $what.", "एक और डालने के लिए, शायद आप यह कह रहे हैं: $what.")
            BrainIntent.TAP -> s("To press, I think you mean: $what.", "दबाने के लिए, शायद आप यह कह रहे हैं: $what.")
            else -> s("To put in the cart, I think you mean: $what.", "कार्ट में डालने के लिए, शायद आप यह कह रहे हैं: $what.")
        }
    }

    fun heardDidYouMean(heard: String, suggestions: List<String>, help: String): String {
        val h = ScreenSummary.clean(heard, 60)
        return if (suggestions.isEmpty()) {
            s("I heard \"$h\", but I'm not sure what to do. $help", "मैंने सुना \"$h\", पर समझ नहीं आया क्या करूँ। $help")
        } else {
            s("I heard \"$h\". Did you mean: ${suggestions.joinToString(", or ")}?", "मैंने सुना \"$h\"। क्या आप कहना चाहते थे: ${suggestions.joinToString(", या ")}?")
        }
    }

    fun addCommand(label: String) = s("add $label", "$label डालो")
    fun tapCommand(label: String) = s("tap $label", "$label दबाओ")
    val noProducts get() = s("I can't see any products on this screen. You can say: search and a name.", "इस स्क्रीन पर कोई सामान नहीं दिख रहा। आप बोल सकते हैं: कुछ खोजो।")
    val whatToSearch get() = s("What should I search for?", "क्या खोजूँ?")
    val notSure get() = s("I'm not sure what you mean.", "मुझे पक्का समझ नहीं आया।")
    val here get() = s("I'm here with you.", "मैं आपके साथ हूँ।")
}
