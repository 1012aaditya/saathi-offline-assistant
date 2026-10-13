package app.saathi.agent

import app.saathi.safety.Fuzz

/**
 * What the user told Saathi to do in guide mode. Parsed offline by keywords (English, Hindi in
 * Devanagari, Hinglish), after dropping politeness and fillers ("can you", "please", "mujhe",
 * "zara", "na"). Cart words also match what the recogniser often makes of them ("card", "cut",
 * "kart", "khat" — 14 Oct: "can you order diet Coke in my card on"). Targets are resolved
 * against the visible screen afterwards ([GuideTargets]). Nothing here can answer a
 * confirmation: "yes", "haan", "send it" are [Unknown] (only the Confirm sheet takes a yes).
 */
sealed interface GuideCommand {
    data class Tap(val target: String, val explicit: Boolean = true) : GuideCommand
    data class Add(val item: String, val count: Int) : GuideCommand

    /** "I want X" / "mujhe X chahiye": add it in a shopping app, open it elsewhere. */
    data class Want(val item: String) : GuideCommand
    data class Remove(val item: String) : GuideCommand
    data class Increase(val item: String) : GuideCommand
    data class Price(val item: String) : GuideCommand
    data class Available(val item: String) : GuideCommand
    data object Cheaper : GuideCommand
    data object Total : GuideCommand
    data object OpenCart : GuideCommand
    data object Checkout : GuideCommand
    data class Search(val query: String) : GuideCommand
    data class Type(val text: String, val field: String?) : GuideCommand
    data class Scroll(val down: Boolean) : GuideCommand
    data object Back : GuideCommand
    data object Repeat : GuideCommand
    data object More : GuideCommand
    data object WhatsOnScreen : GuideCommand

    /** "read everything" / "what buttons are there": every line, controls included. */
    data object Everything : GuideCommand

    /** WhatsApp: read the newest message in the open chat. */
    data object NewestMessage : GuideCommand
    data object Cart : GuideCommand
    data object Keep : GuideCommand
    data object Help : GuideCommand
    data object Done : GuideCommand
    data class Unknown(val text: String) : GuideCommand
}

object GuideGrammar {
    private val I = setOf(RegexOption.IGNORE_CASE)

    /** Cart, and what the recogniser hears for it. */
    const val CART = "(?:cart|carts|card|cards|cut|caught|kart|karts|khat|court|cot|basket|bag|कार्ट|कार्ड)"
    private const val POSS = "(?:(?:the|my|mera|mere|meri|apna|apne|मेरे|मेरा|मेरी)\\s+)?"
    private const val INTO = "(?:to|in|into|inside|on|onto|mein|me|main|में)"

    /** Politeness and fillers before the command (dropped, repeatedly). */
    private val LEAD = Regex(
        "^(?:please|plz|pls|kindly|hey|so|just|now|can you|could you|would you|will you|can u|could u|" +
            "i want to|i wanna|i would like to|i'?d like to|i need to|let'?s|kya tum|kya aap|kya aap mujhe|tum|aap|zara|ज़रा|जरा|" +
            "कृपया|क्या तुम|क्या आप|bhai|yaar)\\s+",
        I,
    )

    /** Politeness and fillers after the command (dropped, repeatedly). */
    private val TRAIL = Regex(
        "\\s+(?:please|plz|pls|na|ji|jee|now|for me|yaar|bhai|abhi|zara|sakte ho|sakti ho|sakoge|sakogi|" +
            "doge|dogi|dijiye|dena|जी|ना|प्लीज़|सकते हो|सकती हो|दीजिए)$",
        I,
    )

    private val DONE = Regex("^(done|stop|bas|bas karo|ho gaya|ho gya|that'?s all|finish|exit|band karo|ruko|stop guiding|we'?re done|i'?m done|बस|बस करो|हो गया|रुको|बंद करो)$", I)
    private val REPEAT = Regex("^(repeat|repeat that|say (that|it) again|again|read again|read it again|phir se|phir se bolo|fir se|fir se bolo|dobara|dobara bolo|dubara|फिर से|फिर से बोलो|दोबारा|दोबारा बोलो)$", I)
    private val MORE = Regex(
        "^(read more|more|next|show more|tell me more|go on|continue reading|aur|aur batao|aur bolo|aur dikhao|aage padho|aur padho|" +
            "और|और बताओ|और बोलो|और दिखाओ|आगे पढ़ो|और पढ़ो)$",
        I,
    )
    private val EVERYTHING = Regex(
        "^(read everything|read it all|read all|everything|all of it|what buttons are there|which buttons are there|what are the buttons|" +
            "what buttons|read the buttons|list everything|sab padho|sab kuch padho|saare button|sab batao|सब पढ़ो|सब कुछ पढ़ो|सब बताओ|कौन से बटन हैं)$",
        I,
    )
    private val SCREEN = Regex("what'?s on (the |my |this )?screen|what is on (the |this )?screen|what do you see|what can you see|describe (the |this )?screen|read (the |this )?screen|where am i|screen (pe|par|per) kya|kya dikh|स्क्रीन (पर|पे) क्या|क्या दिख", I)
    private val CART_Q = Regex("^(what'?s|what is) in $POSS$CART|$CART (mein|me|main) kya|कार्ट में क्या|how many (items )?(are )?in $POSS$CART|show me what'?s in $POSS$CART", I)
    private val TOTAL_Q = Regex("^(how much is the total|what'?s the total|what is the total|total|the total|how much (is it|will it be|do i pay|to pay) ?(in total|altogether)?|total kitna( hai| hua)?|kitna hua|kul kitna( hai)?|कुल कितना( है)?|कितना हुआ|टोटल कितना( है)?)$", I)
    private val OPEN_CART = Regex("^(open|view|show|show me|go to|see|check) $POSS$CART$|^(view|my) $CART$|^$CART (kholo|dikhao|dekho|khol do)$|^कार्ट (खोलो|दिखाओ|देखो)$", I)
    private val CHECKOUT = Regex(
        "^(checkout|check out|go to checkout|proceed to checkout|place (my |the )?order|order (karo|kar do|now|it)|checkout (karo|kar do)|चेकआउट( करो)?|ऑर्डर (करो|कर दो))$",
        I,
    )
    private val CHEAPER = Regex("^(which( one)? is |which is |what('s| is) |what are )?(the )?(lowest price|lowest priced( one)?|least expensive( one)?)$|^(which( one)? is |which is |what('s| is) )?(show me )?(something |anything )?(cheaper|cheaper ones?|cheaper options?|the cheapest( one)?|cheapest)( ones?| options?| one)?( here)?$|^(sabse )?(sasta|saste|sasti)( kaun sa| kaunsa| konsa)?( hai)?$|^सबसे सस्ता( कौन सा)?( है)?$|^(kuch )?(sasta|saste|sasti)( wala| vala| wale)?( dikhao| batao)?$|^(सस्ता|सस्ते)( वाला)?( दिखाओ| बताओ)?$", I)
    private val NEWEST = Regex("^(read (the )?(new|newest|latest|last)? ?messages?|read (the )?(new|newest|latest|last) one|what did (they|he|she) (say|send)|message padho|messages padho|naya message padho|मैसेज पढ़ो|नया मैसेज पढ़ो)$", I)
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

    private const val ADD_VERB = "(?:add|put|get|buy|order|daalo|dalo|daal do|dal do|daal|mangao|mangwao|mangwa do|mangva do)"
    private const val ADD_HI_VERB = "(?:add karo|add kar do|add kar|add|daalo|dalo|daal do|dal do|daal|jodo|jod do|mangao|mangwao|mangwa do|mangva do|" +
        "order karo|order kar do|kharido|le aao|le lo|जोड़ो|जोड़ दो|डालो|डाल दो|ऐड करो|मंगाओ|मंगवा दो|मँगाओ|ऑर्डर करो)"
    private val ADD_EN = Regex("^$ADD_VERB\\s+(.+?)(?:\\s+(?:$INTO\\s+)?$POSS$CART(?:\\s+on)?)?$", I)
    private val ADD_HI = Regex("^(.+?)\\s+(?:$POSS$CART\\s+$INTO\\s+)?$ADD_HI_VERB$", I)
    private val ADD_HI_CART_FIRST = Regex("^$POSS$CART\\s+$INTO\\s+(.+?)\\s+$ADD_HI_VERB$", I)
    private val WANT = Regex("^(?:i want|i need|i'?d like|i would like|get me|mujhe|muje|मुझे)\\s+(.+?)(?:\\s+(?:chahiye|chaiye|chahie|चाहिए))?$", I)
    private val WANT_HI = Regex("^(.+?)\\s+(?:chahiye|chaiye|chahie|चाहिए)$", I)
    private val PLUS_EN = Regex("^(?:increase|plus|one more|add one more|ek aur|एक और)(?:\\s+(.+))?$", I)
    private val PLUS_HI = Regex("^(.+?)\\s+(?:badhao|badha do|बढ़ाओ|बढ़ा दो|ek aur|एक और)$", I)
    private val REMOVE_EN = Regex("^(?:remove|minus|decrease|reduce|take out|delete|remove one|hatao|hata do|nikal do|nikalo)\\s+(.+?)(?:\\s+(?:from|out of)\\s+$POSS$CART)?$", I)
    private val REMOVE_HI = Regex("^(.+?)\\s+(?:(?:ko\\s+)?(?:$POSS$CART\\s+(?:se|से)\\s+)?)?(?:hatao|hata do|kam karo|kam kar do|nikalo|nikal do|minus karo|हटाओ|हटा दो|कम करो|कम कर दो|निकालो|निकाल दो)$", I)
    private val MINUS_ALONE = Regex("^(minus|remove one|ek kam|एक कम)$", I)
    private val PRICE_EN = Regex("^(?:how much (?:is|are|does|for)|what'?s the price of|what is the price of|price of|what does)\\s+(?:the |a )?(.+?)(?:\\s+cost)?$", I)
    private val PRICE_HI = Regex("^(.+?)\\s+(?:kitne ka hai|kitne ki hai|kitne ke hai|kitne ka|ka price kya hai|ka price|ki price|ka daam kya hai|ka daam|कितने का है|कितने की है|का दाम क्या है|का दाम)$", I)
    private val AVAIL_EN = Regex("^(?:is|are)\\s+(?:there\\s+)?(?:any\\s+)?(.+?)\\s+(?:available|in stock|there)$|^(?:do you have|do they have|is there)\\s+(?:any\\s+)?(.+)$", I)
    private val AVAIL_HI = Regex("^(.+?)\\s+(?:hai kya|milega|milegi|available hai|mil jayega|है क्या|मिलेगा|मिलेगी)$", I)

    private val TAP_EN = Regex("^(?:tap|press|click|open|select|choose|pick|hit|touch)\\s+(?:on\\s+)?(?:the\\s+)?(.+?)(?:\\s+button)?$", I)
    private val TAP_HI = Regex("^(.+?)\\s+(?:(?:par|pe|पर|पे)\\s+)?(?:tap karo|tap kar do|dabao|daba do|click karo|kholo|khol do|chuno|select karo|दबाओ|दबा दो|खोलो|खोल दो|चुनो|टैप करो|क्लिक करो)$", I)

    private val NUMBERS: Map<String, Int> = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5,
        "एक" to 1, "दो" to 2, "तीन" to 3, "चार" to 4, "पाँच" to 5, "पांच" to 5,
    )

    private fun clean(text: String): String =
        text.trim().trim('.', '!', '?', ',', '।').replace(Regex("\\s+"), " ").replace(Regex("^(?:saathi|sathi|साथी)[, ]+", I), "")

    /** Drop politeness and fillers around the command ("can you … please", "zara … na"). */
    internal fun polite(text: String): String {
        var s = text.trim()
        while (true) {
            val next = s.replace(LEAD, "").replace(TRAIL, "").trim()
            if (next == s || next.isEmpty()) return s
            s = next
        }
    }

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

    private fun strip(s: String): String = s.trim().removePrefix("the ").removePrefix("a ").removeSuffix(" button").trim()

    fun parse(text: String): GuideCommand {
        val t = clean(text)
        if (t.isEmpty()) return GuideCommand.Unknown(text)
        val body = polite(t.lowercase())
        if (body.isEmpty()) return GuideCommand.Unknown(text)
        exact(body)?.let { return it }
        // A bare yes / ok / send is never an action here (only the Confirm sheet takes a yes).
        if (EchoGuard.tokens(body).all { it in ANSWER_WORDS }) return GuideCommand.Unknown(text)
        return verbs(t, body) ?: GuideCommand.Tap(strip(t), explicit = false)
    }

    private fun exact(body: String): GuideCommand? = when {
        DONE.matches(body) -> GuideCommand.Done
        REPEAT.matches(body) -> GuideCommand.Repeat
        MORE.matches(body) -> GuideCommand.More
        EVERYTHING.matches(body) -> GuideCommand.Everything
        HELP.matches(body) -> GuideCommand.Help
        TOTAL_Q.matches(body) -> GuideCommand.Total
        CART_Q.containsMatchIn(body) -> GuideCommand.Cart
        SCREEN.containsMatchIn(body) -> GuideCommand.WhatsOnScreen
        OPEN_CART.matches(body) -> GuideCommand.OpenCart
        CHECKOUT.matches(body) -> GuideCommand.Checkout
        CHEAPER.matches(body) -> GuideCommand.Cheaper
        NEWEST.matches(body) -> GuideCommand.NewestMessage
        KEEP.matches(body) -> GuideCommand.Keep
        BACK.matches(body) -> GuideCommand.Back
        DOWN.matches(body) -> GuideCommand.Scroll(true)
        UP.matches(body) -> GuideCommand.Scroll(false)
        MINUS_ALONE.matches(body) -> GuideCommand.Remove("")
        else -> null
    }

    /** Commands with an argument. [orig] keeps the user's casing for typed text. */
    private fun verbs(orig: String, body: String): GuideCommand? {
        fun arg(rx: Regex, group: Int = 1): String? = rx.matchEntire(body)?.let { m ->
            val r = m.groups[group]?.range ?: return@let null
            // Same span in the original (case kept); fall back to the lowercase text.
            val off = orig.lowercase().indexOf(body)
            if (off >= 0 && r.last + off < orig.length) orig.substring(r.first + off, r.last + off + 1) else m.groupValues[group]
        }
        fun add(item: String): GuideCommand { val (i, n) = count(strip(item.removePrefix("me "))); return GuideCommand.Add(i, n) }
        SEARCH_EN.matchEntire(body)?.let { return GuideCommand.Search(arg(SEARCH_EN)!!.trim()) }
        SEARCH_HI2.matchEntire(body)?.let { return GuideCommand.Search(arg(SEARCH_HI2)!!.trim()) }
        SEARCH_HI.matchEntire(body)?.let { return GuideCommand.Search(arg(SEARCH_HI)!!.trim()) }
        TYPE_INTO.matchEntire(body)?.let { return GuideCommand.Type(arg(TYPE_INTO, 1)!!.trim(), arg(TYPE_INTO, 2)!!.trim()) }
        TYPE_EN.matchEntire(body)?.let { return GuideCommand.Type(arg(TYPE_EN)!!.trim(), null) }
        TYPE_HI_FIELD.matchEntire(body)?.let { return GuideCommand.Type(arg(TYPE_HI_FIELD, 2)!!.trim(), arg(TYPE_HI_FIELD, 1)!!.trim()) }
        TYPE_HI.matchEntire(body)?.let { return GuideCommand.Type(arg(TYPE_HI)!!.trim(), null) }
        PRICE_EN.matchEntire(body)?.let { m -> return GuideCommand.Price(strip(m.groupValues[1])) }
        PRICE_HI.matchEntire(body)?.let { m -> return GuideCommand.Price(strip(m.groupValues[1])) }
        AVAIL_EN.matchEntire(body)?.let { m -> return GuideCommand.Available(strip(m.groupValues[1].ifEmpty { m.groupValues[2] })) }
        AVAIL_HI.matchEntire(body)?.let { m -> return GuideCommand.Available(strip(m.groupValues[1])) }
        PLUS_EN.matchEntire(body)?.let { m -> return GuideCommand.Increase(strip(m.groupValues[1])) }
        PLUS_HI.matchEntire(body)?.let { m -> return GuideCommand.Increase(strip(m.groupValues[1])) }
        REMOVE_EN.matchEntire(body)?.let { m -> return GuideCommand.Remove(strip(m.groupValues[1])) }
        REMOVE_HI.matchEntire(body)?.let { m -> return GuideCommand.Remove(strip(m.groupValues[1])) }
        ADD_HI_CART_FIRST.matchEntire(body)?.let { m -> return add(m.groupValues[1]) }
        ADD_EN.matchEntire(body)?.let { m -> return add(m.groupValues[1]) }
        ADD_HI.matchEntire(body)?.let { m -> return add(m.groupValues[1]) }
        if (body == "add" || body == "add karo" || body == "जोड़ो") return GuideCommand.Add("", 1)
        TAP_EN.matchEntire(body)?.let { return GuideCommand.Tap(strip(arg(TAP_EN)!!)) }
        TAP_HI.matchEntire(body)?.let { return GuideCommand.Tap(strip(arg(TAP_HI)!!)) }
        (WANT.matchEntire(body) ?: WANT_HI.matchEntire(body))?.let { m ->
            val rest = m.groupValues[1].trim().removePrefix("to ").trim()
            // "I want to go back", "mujhe cart dikhao": the rest is itself a command.
            val inner = parse(rest)
            return if (inner is GuideCommand.Tap && !inner.explicit || inner is GuideCommand.Unknown) GuideCommand.Want(strip(rest)) else inner
        }
        return null
    }

    /** Yes / no / ok / send words: never an implicit tap ("yes" must not press a "Yes" button). */
    private val ANSWER_WORDS: Set<String> = (
        ConfirmDialog.YES_WORDS + setOf("ok", "okay", "no", "nahi", "nahin", "send", "bhejo", "bhej", "do", "it", "confirm", "sure", "ji", "जी", "भेजो", "नहीं", "ठीक", "है", "theek", "thik", "hai")
        ).flatMap { EchoGuard.tokens(it) }.toSet()

    /** "it", "this", "isko" …: the item talked about last. */
    val PRONOUNS: Set<String> = setOf("it", "this", "that", "this one", "that one", "isko", "ise", "usko", "use", "yeh", "ye", "इसे", "इसको", "उसे", "उसको", "यह")
}

/**
 * Speech-recogniser slips ("diet Google" for "diet Coke"): a loose, sound-alike match of what was
 * heard against names on the screen or seen earlier in the session. Only ever used to ASK ("Did
 * you mean …?", the "which one?" sheet) — never to act on its own.
 */
object GuideGuess {
    private val CART_WORD = Regex("^${GuideGrammar.CART}$", RegexOption.IGNORE_CASE)
    private val ADDISH = setOf("add", "ad", "at", "are", "and", "odd", "aid", "had", "put", "order", "buy", "get", "daal", "dal", "daalo", "dalo", "mangao", "डालो", "डाल")

    /** Words that carry no product meaning in a messy request. */
    private val FILLER: Set<String> = setOf(
        "add", "ad", "at", "are", "and", "odd", "aid", "had", "put", "order", "buy", "get", "to", "in", "into", "on", "my", "the", "a", "an",
        "me", "mein", "main", "mera", "mere", "meri", "please", "can", "could", "you", "i", "want", "some", "of", "for", "is", "it", "do",
        "daal", "dal", "daalo", "dalo", "mangao", "karo", "kar", "na", "ji", "में", "मेरे", "डालो", "डाल", "दो",
    )

    /** A rough sound key: c/k/q/g -> k, b/p -> p, d/t -> t, s/z -> s, v/w -> v; vowels dropped after the first letter. */
    internal fun sound(word: String): String {
        val w = word.lowercase().replace("ph", "f").replace("ck", "k")
        val out = StringBuilder()
        w.forEachIndexed { i, ch ->
            val c = when (ch) {
                'c', 'k', 'q', 'g' -> 'k'
                'b', 'p' -> 'p'
                'd', 't' -> 't'
                's', 'z' -> 's'
                'v', 'w' -> 'v'
                'a', 'e', 'i', 'o', 'u', 'y', 'h' -> if (i == 0) ch else null
                else -> if (ch.isLetter()) ch else null
            }
            if (c != null && (out.isEmpty() || out.last() != c)) out.append(c)
        }
        return out.toString()
    }

    /** Does the heard word plausibly name the screen word (same word, close spelling, or sounds alike)? */
    fun sameWord(heard: String, label: String): Boolean {
        val a = heard.lowercase()
        val b = label.lowercase()
        if (a == b) return true
        if (a.length >= 4 && b.length >= 4 && Fuzz.tokenSetRatio(a, b) >= 75.0) return true
        val x = sound(a)
        val y = sound(b)
        if (x.isEmpty() || y.isEmpty() || x[0] != y[0]) return false
        return x == y || ((x.startsWith(y) || y.startsWith(x)) && Math.abs(x.length - y.length) <= 1 && minOf(a.length, b.length) >= 4)
    }

    private fun quality(heard: String, label: String): Int = when {
        heard.equals(label, ignoreCase = true) -> 3
        heard.length >= 4 && Fuzz.tokenSetRatio(heard.lowercase(), label.lowercase()) >= 75.0 -> 2
        else -> 1
    }

    /** The product words of a messy request ("are diet Google my card" -> [diet, google]). */
    fun productWords(text: String): List<String> =
        EchoGuard.tokens(text).filter { it !in FILLER && !CART_WORD.matches(it) && it.toIntOrNull() == null }

    /** The request talks about the cart ("my card", "kart"). */
    fun mentionsCart(text: String): Boolean = EchoGuard.tokens(text).any { CART_WORD.matches(it) }

    /** It starts like an add ("are diet …", "add …", "put …"). */
    fun soundsLikeAdd(text: String): Boolean = EchoGuard.tokens(text).firstOrNull()?.let { it in ADDISH } == true

    /**
     * The words of [names] (screen names, verbatim) that the heard product words best match, in
     * the name's own spelling and order ("Diet Coke" from "Coca-Cola Diet Coke Soft Drink"), with
     * the name. Null unless every heard word matches and at least one matches exactly / closely.
     */
    fun match(words: List<String>, names: Collection<String>): Pair<String, String>? {
        if (words.isEmpty()) return null
        var best: Triple<String, String, Int>? = null
        for (name in names) {
            val nameWords = name.split(Regex("[\\s,()]+")).filter { it.isNotEmpty() }
            val hit = mutableListOf<Int>()
            var strong = 0
            for (w in words) {
                val candidates = nameWords.indices.filter { it !in hit && sameWord(w, nameWords[it]) }
                if (candidates.isEmpty()) break
                // Next to the word matched before ("diet" then "Coke"), else the closest one.
                val prev = hit.lastOrNull()
                val i = candidates.firstOrNull { prev != null && it == prev + 1 } ?: candidates.maxBy { quality(w, nameWords[it]) }
                hit += i
                if (quality(w, nameWords[i]) >= 2) strong++
            }
            if (hit.size != words.size || strong == 0) continue
            val phrase = hit.sorted().joinToString(" ") { nameWords[it] }
            val score = strong * 10 - nameWords.size
            if (best == null || score > best.third) best = Triple(phrase, name, score)
        }
        return best?.let { it.first to it.second }
    }
}
