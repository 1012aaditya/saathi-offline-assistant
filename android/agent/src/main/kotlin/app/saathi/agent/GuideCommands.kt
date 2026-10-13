package app.saathi.agent

/**
 * What the user told Saathi to do in guide mode. Parsed offline by keywords (English, Hindi in
 * Devanagari, Hinglish); targets are resolved against the visible screen afterwards
 * ([GuideTargets]). Nothing here can answer a confirmation: "yes", "haan", "send it" are
 * [Unknown] (only the Confirm sheet takes a yes).
 */
sealed interface GuideCommand {
    data class Tap(val target: String, val explicit: Boolean = true) : GuideCommand
    data class Add(val item: String, val count: Int) : GuideCommand
    data class Remove(val item: String) : GuideCommand
    data class Increase(val item: String) : GuideCommand
    data object OpenCart : GuideCommand
    data object Checkout : GuideCommand
    data class Search(val query: String) : GuideCommand
    data class Type(val text: String, val field: String?) : GuideCommand
    data class Scroll(val down: Boolean) : GuideCommand
    data object Back : GuideCommand
    data object Repeat : GuideCommand
    data object More : GuideCommand
    data object WhatsOnScreen : GuideCommand
    data object Cart : GuideCommand
    data object Keep : GuideCommand
    data object Help : GuideCommand
    data object Done : GuideCommand
    data class Unknown(val text: String) : GuideCommand
}

object GuideGrammar {
    private val I = setOf(RegexOption.IGNORE_CASE)
    private const val PLEASE = "(?:please |plz |zara |ज़रा |जरा )?"
    private const val TAIL = "(?: please| plz| na| ji| जी| ना)?"

    private val DONE = Regex("^(done|stop|bas|bas karo|ho gaya|ho gya|that'?s all|finish|exit|band karo|ruko|stop guiding|we'?re done|i'?m done|बस|बस करो|हो गया|रुको|बंद करो)$", I)
    private val REPEAT = Regex("^(repeat|repeat that|say (that|it) again|again|read again|read it again|phir se|phir se bolo|fir se|fir se bolo|dobara|dobara bolo|dubara|फिर से|फिर से बोलो|दोबारा|दोबारा बोलो)$", I)
    private val MORE = Regex("^(read more|more|next|tell me more|go on|continue reading|aur|aur batao|aur bolo|aage padho|aur padho|और|और बताओ|और बोलो|आगे पढ़ो|और पढ़ो)$", I)
    private val SCREEN = Regex("what'?s on (the |my |this )?screen|what is on (the |this )?screen|what do you see|what can you see|describe (the |this )?screen|read (the |this )?screen|screen (pe|par|per) kya|kya dikh|स्क्रीन (पर|पे) क्या|क्या दिख", I)
    private val CART_Q = Regex("^(what'?s|what is) in (my |the )?cart|cart (mein|me|main) kya|कार्ट में क्या|how many (items )?in (my |the )?cart", I)
    private val OPEN_CART = Regex("^(open|view|show|go to|see) (my |the )?(cart|basket)$|^(view|my) cart$|^cart (kholo|dikhao|dekho|khol do)$|^कार्ट (खोलो|दिखाओ|देखो)$", I)
    private val CHECKOUT = Regex("^(checkout|check out|go to checkout|proceed to checkout)$|^checkout (karo|kar do)$|^चेकआउट( करो)?$", I)
    private val DOWN = Regex("^(scroll|scroll down|down|go down|neeche|niche|neeche karo|niche karo|neeche scroll karo|scroll karo|aage|aage karo|नीचे|नीचे करो|आगे|आगे करो)$", I)
    private val UP = Regex("^(scroll up|up|go up|upar|upar karo|oopar|peeche|piche|peeche karo|ऊपर|ऊपर करो|पीछे|पीछे करो)$", I)
    private val BACK = Regex("^(go back|back|press back|wapas|wapas jao|vapas|vapas jao|wapis|wapis jao|वापस|वापस जाओ|पीछे जाओ)$", I)
    private val KEEP = Regex("^(keep it|leave it|keep|leave it as it is|rehne do|rahne do|chhodo|chodo|रहने दो|छोड़ो|छोड़ दो)$", I)
    private val HELP = Regex("^(help|what can i say|madad|मदद)$", I)

    private val SEARCH_EN = Regex("^(?:search for|search|find|look for|look up)\\s+(.+)$", I)
    private val SEARCH_HI = Regex("^(.+?)\\s+(?:search karo|search kar do|dhoondo|dhundo|khojo|ढूंढो|ढूँढो|खोजो|सर्च करो)$", I)
    private val SEARCH_HI2 = Regex("^(?:khojo|dhoondo|ढूंढो|खोजो)\\s+(.+)$", I)

    private val TYPE_INTO = Regex("^(?:type|write|enter)\\s+(.+?)\\s+(?:in|into|in the|into the)\\s+(.+?)(?:\\s+(?:field|box))?$", I)
    private val TYPE_EN = Regex("^(?:type|write|enter)\\s+(.+)$", I)
    private val TYPE_HI_FIELD = Regex("^(.+?)\\s+(?:mein|me|main|में)\\s+(.+?)\\s+(?:likho|likh do|type karo|लिखो|लिख दो)$", I)
    private val TYPE_HI = Regex("^(.+?)\\s+(?:likho|likh do|type karo|लिखो|लिख दो)$", I)

    private val ADD_EN = Regex("^(?:add|put|daalo|dalo)\\s+(.+?)(?:\\s+(?:to|in|into)\\s+(?:the |my )?(?:cart|basket))?$", I)
    private val ADD_HI = Regex("^(.+?)\\s+(?:add karo|add kar do|daalo|dalo|daal do|jodo|jod do|जोड़ो|जोड़ दो|डालो|डाल दो|ऐड करो)$", I)
    private val PLUS_EN = Regex("^(?:increase|plus|one more|add one more|ek aur|एक और)(?:\\s+(.+))?$", I)
    private val PLUS_HI = Regex("^(.+?)\\s+(?:badhao|badha do|बढ़ाओ|बढ़ा दो|ek aur|एक और)$", I)
    private val REMOVE_EN = Regex("^(?:remove|minus|decrease|reduce|take out|remove one)\\s+(.+?)(?:\\s+(?:from)\\s+(?:the |my )?cart)?$", I)
    private val REMOVE_HI = Regex("^(.+?)\\s+(?:hatao|hata do|kam karo|kam kar do|nikalo|nikal do|हटाओ|हटा दो|कम करो|कम कर दो|निकालो)$", I)
    private val MINUS_ALONE = Regex("^(minus|remove one|ek kam|एक कम)$", I)

    private val TAP_EN = Regex("^(?:tap|press|click|open|select|choose|pick|hit|touch)\\s+(?:on\\s+)?(?:the\\s+)?(.+?)(?:\\s+button)?$", I)
    private val TAP_HI = Regex("^(.+?)\\s+(?:(?:par|pe|पर|पे)\\s+)?(?:tap karo|tap kar do|dabao|daba do|click karo|kholo|khol do|chuno|select karo|दबाओ|दबा दो|खोलो|खोल दो|चुनो|टैप करो|क्लिक करो)$", I)

    private val NUMBERS: Map<String, Int> = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5,
        "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पाँच" to 5, "पांच" to 5,
    )

    private fun clean(text: String): String =
        text.trim().trim('.', '!', '?', ',', '।').replace(Regex("\\s+"), " ").replace(Regex("^(?:saathi|sathi|साथी)[, ]+", I), "")

    /** "2 amul taaza", "amul taaza 2", "do amul" -> (item, count). */
    internal fun count(item: String): Pair<String, Int> {
        val w = item.split(" ").filter { it.isNotEmpty() }
        if (w.size >= 2) {
            val first = w.first().lowercase()
            (first.toIntOrNull() ?: NUMBERS[first])?.let { return w.drop(1).joinToString(" ") to it }
            val last = w.last().lowercase()
            last.toIntOrNull()?.let { return w.dropLast(1).joinToString(" ") to it }
        }
        return item to 1
    }

    private fun strip(s: String): String = s.trim().removePrefix("the ").removeSuffix(" button").trim()

    fun parse(text: String): GuideCommand {
        val t = clean(text)
        if (t.isEmpty()) return GuideCommand.Unknown(text)
        val l = t.lowercase().removePrefix("please ").trim().let { it.replace(Regex("$TAIL$", I), "").trim() }
        val body = Regex("^$PLEASE", I).replace(l, "")
        return when {
            DONE.matches(body) -> GuideCommand.Done
            REPEAT.matches(body) -> GuideCommand.Repeat
            MORE.matches(body) -> GuideCommand.More
            HELP.matches(body) -> GuideCommand.Help
            CART_Q.containsMatchIn(body) -> GuideCommand.Cart
            SCREEN.containsMatchIn(body) -> GuideCommand.WhatsOnScreen
            OPEN_CART.matches(body) -> GuideCommand.OpenCart
            CHECKOUT.matches(body) -> GuideCommand.Checkout
            KEEP.matches(body) -> GuideCommand.Keep
            BACK.matches(body) -> GuideCommand.Back
            DOWN.matches(body) -> GuideCommand.Scroll(true)
            UP.matches(body) -> GuideCommand.Scroll(false)
            MINUS_ALONE.matches(body) -> GuideCommand.Remove("")
            // A bare yes / ok / send is never an action here (only the Confirm sheet takes a yes).
            EchoGuard.tokens(body).all { it in ANSWER_WORDS } -> GuideCommand.Unknown(text)
            else -> verbs(t, body) ?: GuideCommand.Tap(strip(t), explicit = false)
        }
    }

    /** Commands with an argument. [orig] keeps the user's casing for typed text. */
    private fun verbs(orig: String, body: String): GuideCommand? {
        fun arg(rx: Regex, group: Int = 1): String? = rx.matchEntire(body)?.let { m ->
            val r = m.groups[group]?.range ?: return@let null
            // Same span in the original (case kept); fall back to the lowercase text.
            val off = orig.lowercase().indexOf(body)
            if (off >= 0 && r.last + off < orig.length) orig.substring(r.first + off, r.last + off + 1) else m.groupValues[group]
        }
        SEARCH_EN.matchEntire(body)?.let { return GuideCommand.Search(arg(SEARCH_EN)!!.trim()) }
        SEARCH_HI2.matchEntire(body)?.let { return GuideCommand.Search(arg(SEARCH_HI2)!!.trim()) }
        SEARCH_HI.matchEntire(body)?.let { return GuideCommand.Search(arg(SEARCH_HI)!!.trim()) }
        TYPE_INTO.matchEntire(body)?.let { return GuideCommand.Type(arg(TYPE_INTO, 1)!!.trim(), arg(TYPE_INTO, 2)!!.trim()) }
        TYPE_EN.matchEntire(body)?.let { return GuideCommand.Type(arg(TYPE_EN)!!.trim(), null) }
        TYPE_HI_FIELD.matchEntire(body)?.let { return GuideCommand.Type(arg(TYPE_HI_FIELD, 2)!!.trim(), arg(TYPE_HI_FIELD, 1)!!.trim()) }
        TYPE_HI.matchEntire(body)?.let { return GuideCommand.Type(arg(TYPE_HI)!!.trim(), null) }
        PLUS_EN.matchEntire(body)?.let { m -> return GuideCommand.Increase(strip(m.groupValues[1])) }
        PLUS_HI.matchEntire(body)?.let { m -> return GuideCommand.Increase(strip(m.groupValues[1])) }
        ADD_EN.matchEntire(body)?.let { m -> val (item, n) = count(strip(m.groupValues[1])); return GuideCommand.Add(item, n) }
        ADD_HI.matchEntire(body)?.let { m -> val (item, n) = count(strip(m.groupValues[1])); return GuideCommand.Add(item, n) }
        if (body == "add" || body == "add karo" || body == "जोड़ो") return GuideCommand.Add("", 1)
        REMOVE_EN.matchEntire(body)?.let { m -> return GuideCommand.Remove(strip(m.groupValues[1])) }
        REMOVE_HI.matchEntire(body)?.let { m -> return GuideCommand.Remove(strip(m.groupValues[1])) }
        TAP_EN.matchEntire(body)?.let { return GuideCommand.Tap(strip(arg(TAP_EN)!!)) }
        TAP_HI.matchEntire(body)?.let { return GuideCommand.Tap(strip(arg(TAP_HI)!!)) }
        return null
    }

    /** Yes / no / ok / send words: never an implicit tap ("yes" must not press a "Yes" button). */
    private val ANSWER_WORDS: Set<String> = (
        ConfirmDialog.YES_WORDS + setOf("ok", "okay", "no", "nahi", "nahin", "send", "bhejo", "bhej", "do", "it", "confirm", "sure", "ji", "जी", "भेजो", "नहीं", "ठीक", "है", "theek", "thik", "hai")
        ).flatMap { EchoGuard.tokens(it) }.toSet()

    /** "it", "this", "isko" …: the item talked about last. */
    val PRONOUNS: Set<String> = setOf("it", "this", "that", "this one", "that one", "isko", "ise", "usko", "use", "yeh", "ye", "इसे", "इसको", "उसे", "उसको", "यह")
}
