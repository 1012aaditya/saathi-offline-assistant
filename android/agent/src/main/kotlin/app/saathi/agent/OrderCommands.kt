package app.saathi.agent

import app.saathi.safety.Fuzz
import java.text.Normalizer as TextNormalizer

/**
 * Grocery (Blinkit) commands. Part of the [Command] family (same package), parsed offline by
 * [OrderParser]. Every order still needs the read-back and a clear yes (hard rule 5).
 */
sealed interface GroceryCommand : Command {
    override val isTask: Boolean get() = true

    /**
     * "Order milk from Blinkit", "Blinkit se 2 Amul doodh mangao", "add bread to my cart".
     * [query] is the English search text ("Amul milk", "1 kg atta"); [addOnly]: stop once it is
     * in the cart (never go to checkout). [problem]: why Saathi has to ask before starting.
     */
    data class Order(
        val query: String?,
        val quantity: Int,
        val addOnly: Boolean,
        override val utterance: String,
        val problem: Problem? = null,
    ) : GroceryCommand {
        val item: String? get() = query
    }

    /** "What's in my cart?" — read only. */
    data class ReadCart(override val utterance: String) : GroceryCommand

    /** "Place my order" — checkout of the cart as it is; still needs the read-back yes. */
    data class PlaceOrder(override val utterance: String) : GroceryCommand

    /** "Empty my cart" — only after its own confirmation (Cancel is the default). */
    data class EmptyCart(override val utterance: String) : GroceryCommand

    sealed interface Problem {
        data object NoItem : Problem
        data class Several(val items: List<String>) : Problem
        data class Unknown(val word: String) : Problem
        data class TooMany(val max: Int) : Problem
    }
}

/**
 * Keyword parser for grocery phrases: English, Hinglish and Hindi (Devanagari). Deterministic
 * and offline. Hindi / Hinglish item words are turned into an English search query with a small
 * dictionary; a Devanagari word it doesn't know is never guessed at (Saathi asks instead).
 */
object OrderParser {
    const val MAX_QUANTITY = 10
    private val I = setOf(RegexOption.IGNORE_CASE)

    private const val CART = "(?:blinkit\\s+|ब्लिंकिट\\s+)?(?:cart|basket|कार्ट)"

    private val EMPTY = Regex(
        "\\b(?:empty|clear)\\s+(?:out\\s+)?(?:my\\s+|the\\s+)?$CART\\b|" +
            "\\bremove\\s+(?:everything|all(?:\\s+items)?)\\s+from\\s+(?:my\\s+|the\\s+)?$CART\\b|" +
            "(?:cart|कार्ट)\\s+(?:khali|khaali|saaf|खाली|साफ़|साफ)(?:\\s+(?:karo|kar do|kardo|करो|कर दो))?$",
        I,
    )
    private val READ_CART = Regex(
        "what(?:'?s| is)\\s+in\\s+(?:my|the)\\s+$CART|\\b(?:read|check|show)\\s+(?:me\\s+)?(?:my|the)\\s+$CART\\b|" +
            "^(?:my\\s+)?$CART$|(?:cart|कार्ट)\\s+(?:me|mein|mai|में)\\s+(?:kya|क्या)\\s+(?:hai|है)",
        I,
    )
    private val PLACE = Regex(
        "^(?:please\\s+)?(?:place|confirm|complete|finish)\\s+(?:my\\s+|the\\s+)?(?:blinkit\\s+)?order$|^(?:please\\s+)?checkout$|" +
            "^(?:blinkit\\s+)?(?:order|ऑर्डर)\\s+(?:place\\s+)?(?:karo|kar do|kardo|करो|कर दो)$|^place order$",
        I,
    )
    private val ADD_EN = Regex("^(?:please\\s+)?add\\s+(.+?)\\s+(?:to|in|into)\\s+(?:my\\s+|the\\s+)?$CART$", I)
    private val ADD_HI = Regex(
        "^(.+?)\\s+(?:cart|कार्ट)\\s+(?:me|mein|mai|में)\\s+(?:daalo|dalo|daal do|dal do|jodo|jod do|डालो|डाल दो|जोड़ो|जोड़ दो)$",
        I,
    )
    private val ORDER_EN = Regex(
        "^(?:please\\s+)?(?:can you\\s+)?(?:order|buy|get me|i want to order|i want to buy|i need to order)\\s+(.+)$",
        I,
    )
    private const val HI_VERBS =
        "mangao|mangaao|mangwao|mangwa do|manga do|mangvao|mangva do|mangwa dijiye|order karo|order kar do|order kardo|" +
            "kharido|khareed do|kharid do|मंगाओ|मँगाओ|मंगवाओ|मँगवाओ|मंगवा दो|मंगा दो|मँगा दो|मंगवा दीजिए|ऑर्डर करो|ऑर्डर कर दो|" +
            "खरीदो|ख़रीदो|खरीद दो"
    private val ORDER_HI = Regex("^(.+?)\\s+(?:$HI_VERBS)$", I)

    /** Grocery command, or null when the phrase isn't about groceries. [t] is already normalised. */
    fun parse(t: String): GroceryCommand? {
        val s = t.trim().trimEnd('.', '?', '!', '।').trim()
        if (s.isEmpty()) return null
        if (EMPTY.containsMatchIn(s)) return GroceryCommand.EmptyCart(t)
        if (READ_CART.containsMatchIn(s)) return GroceryCommand.ReadCart(t)
        if (PLACE.matches(s)) return GroceryCommand.PlaceOrder(t)
        ADD_EN.matchEntire(s)?.let { return order(it.groupValues[1], addOnly = true, t) }
        ADD_HI.matchEntire(s)?.let { return order(it.groupValues[1], addOnly = true, t) }
        ORDER_EN.matchEntire(s)?.let { return order(it.groupValues[1], addOnly = false, t) }
        ORDER_HI.matchEntire(s)?.let { return order(it.groupValues[1], addOnly = false, t) }
        return null
    }

    // -- the item phrase ------------------------------------------------------------------------

    private val BLINKIT_PHRASE = Regex(
        "\\s*\\b(?:from|on|via|at|in)\\s+blinkit\\b\\s*|\\s*\\bblinkit\\s+(?:se|pe|par|per)\\b\\s*|" +
            "\\s*ब्लिंकिट\\s+(?:से|पर|पे)\\s*|\\s*\\bblinkit\\b\\s*|\\s*ब्लिंकिट\\s*",
        I,
    )
    private val SPLIT = Regex("\\s*,\\s*|\\s+(?:and|aur|और|&)\\s+", I)
    private val PACKS = Regex(
        "(?:^|\\s)(\\S+)\\s*(?:packets?|packs?|pcs|pieces?|bottles?|पैकेट|बोतल)(?=\\s|$)",
        I,
    )

    private val NUMBER_WORDS: Map<String, Int> = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5,
        "panch" to 5, "chhe" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10, "एक" to 1, "दो" to 2,
        "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5, "छह" to 6, "छः" to 6, "सात" to 7, "आठ" to 8, "नौ" to 9, "दस" to 10,
    )

    private val UNITS: Map<String, String> = mapOf(
        "kg" to "kg", "kgs" to "kg", "kilo" to "kg", "kilos" to "kg", "किलो" to "kg", "g" to "g", "gm" to "g",
        "gms" to "g", "gram" to "g", "grams" to "g", "ग्राम" to "g", "l" to "litre", "ltr" to "litre",
        "litre" to "litre", "litres" to "litre", "liter" to "litre", "liters" to "litre", "लीटर" to "litre",
        "ml" to "ml", "मिली" to "ml", "मिलीलीटर" to "ml",
    )

    /** Multi-word phrases first (Hindi / Hinglish -> English). */
    private val PHRASES: List<Pair<String, String>> = listOf(
        "aadha kilo" to "500 g", "आधा किलो" to "500 g", "aadha litre" to "500 ml", "आधा लीटर" to "500 ml",
        "double roti" to "bread", "डबल रोटी" to "bread", "chai patti" to "tea", "चाय पत्ती" to "tea",
        "चायपत्ती" to "tea", "sarson ka tel" to "mustard oil", "सरसों का तेल" to "mustard oil",
        "mother dairy" to "Mother Dairy", "मदर डेयरी" to "Mother Dairy", "hari mirch" to "green chilli",
        "हरी मिर्च" to "green chilli", "cold drink" to "cold drink", "कोल्ड ड्रिंक" to "cold drink",
        "ice cream" to "ice cream", "आइस क्रीम" to "ice cream",
    )

    /** One word (Hinglish or Devanagari, lowercase, nukta dropped) -> English search word. */
    private val WORDS: Map<String, String> = mapOf(
        "doodh" to "milk", "dudh" to "milk", "dood" to "milk", "दूध" to "milk",
        "ब्रेड" to "bread", "pav" to "pav", "पाव" to "pav",
        "aata" to "atta", "atta" to "atta", "आटा" to "atta",
        "chawal" to "rice", "chaawal" to "rice", "चावल" to "rice",
        "cheeni" to "sugar", "chini" to "sugar", "चीनी" to "sugar", "shakkar" to "sugar", "शक्कर" to "sugar",
        "namak" to "salt", "नमक" to "salt",
        "anda" to "eggs", "ande" to "eggs", "अंडा" to "eggs", "अंडे" to "eggs",
        "dahi" to "curd", "दही" to "curd",
        "makhan" to "butter", "makkhan" to "butter", "मक्खन" to "butter", "मखन" to "butter", "बटर" to "butter",
        "paneer" to "paneer", "पनीर" to "paneer",
        "chai" to "tea", "चाय" to "tea", "कॉफी" to "coffee", "कॉफ़ी" to "coffee", "काफी" to "coffee",
        "tel" to "oil", "तेल" to "oil", "ghee" to "ghee", "घी" to "ghee",
        "dal" to "dal", "daal" to "dal", "दाल" to "dal",
        "biskut" to "biscuits", "बिस्कुट" to "biscuits", "नमकीन" to "namkeen",
        "sabun" to "soap", "साबुन" to "soap",
        "pyaz" to "onion", "pyaaz" to "onion", "प्याज" to "onion",
        "aloo" to "potato", "alu" to "potato", "आलू" to "potato",
        "tamatar" to "tomato", "टमाटर" to "tomato",
        "kela" to "banana", "kele" to "banana", "केला" to "banana", "केले" to "banana",
        "seb" to "apple", "सेब" to "apple",
        "pani" to "water", "paani" to "water", "पानी" to "water",
        "मैगी" to "Maggi", "नूडल्स" to "noodles", "चिप्स" to "chips", "जूस" to "juice", "चीज" to "cheese",
        "dhaniya" to "coriander", "धनिया" to "coriander", "nimbu" to "lemon", "नींबू" to "lemon", "निम्बू" to "lemon",
        "टूथपेस्ट" to "toothpaste", "शैम्पू" to "shampoo",
        "अमूल" to "Amul", "ब्रिटानिया" to "Britannia", "आशीर्वाद" to "Aashirvaad", "टाटा" to "Tata",
        "पारले" to "Parle", "नंदिनी" to "Nandini", "फॉर्च्यून" to "Fortune", "हल्दीराम" to "Haldiram's",
    ).mapKeys { norm(it.key) }

    /** Dropped from the item phrase. */
    private val FILLERS = setOf(
        "please", "mujhe", "मुझे", "zara", "ज़रा", "जरा", "some", "a", "an", "the", "kuch", "कुछ", "thoda", "थोड़ा",
        "ka", "ki", "ke", "का", "की", "के", "wala", "wali", "wale", "वाला", "वाली", "वाले", "se", "से", "me", "my",
        "packet", "packets", "पैकेट",
    ).map { norm(it) }.toSet()

    private fun norm(s: String): String =
        TextNormalizer.normalize(s.lowercase(), TextNormalizer.Form.NFC).replace("़", "").trim()

    private fun isDevanagari(s: String) = s.any { it in 'ऀ'..'ॿ' }

    private fun order(raw: String, addOnly: Boolean, utterance: String): GroceryCommand.Order {
        fun problem(p: GroceryCommand.Problem) = GroceryCommand.Order(null, 1, addOnly, utterance, p)
        var phrase = BLINKIT_PHRASE.replace(" $raw ", " ").trim()
        // A spoken list: "बेड, ब्रेड" is the recogniser correcting itself (keep the last); two different
        // things are two orders (ask which first).
        val parts = SPLIT.split(phrase).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return problem(GroceryCommand.Problem.NoItem)
        if (parts.size > 1) {
            val last = parts.last()
            val selfCorrection = parts.dropLast(1).all { Fuzz.tokenSetRatio(norm(it), norm(last)) >= 60.0 }
            if (!selfCorrection) return problem(GroceryCommand.Problem.Several(parts.take(4)))
            phrase = last
        } else {
            phrase = parts[0]
        }

        // "2 packet doodh" / "do bottle" -> quantity.
        var quantity: Int? = null
        PACKS.find(phrase)?.let { m ->
            numberOf(m.groupValues[1])?.let { n ->
                quantity = n
                phrase = phrase.removeRange(m.range).trim()
            }
        }
        for ((from, to) in PHRASES) phrase = phrase.replace(Regex("(?<![\\p{L}\\p{M}])${Regex.escape(from)}(?![\\p{L}\\p{M}])", I), to)

        val tokens = phrase.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        // Leading count, unless it is a size ("1 kg atta", "एक किलो आटा").
        if (quantity == null && tokens.size >= 2) {
            val n = numberOf(tokens[0])
            if (n != null && norm(tokens[1]) !in UNITS) {
                quantity = n
                tokens.removeAt(0)
            }
        }
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            val key = norm(tok)
            val next = tokens.getOrNull(i + 1)?.let(::norm)
            val n = numberOf(tok)
            when {
                n != null && next != null && next in UNITS -> { out += "$n ${UNITS.getValue(next)}"; i += 2; continue }
                Regex("^(\\d+(?:\\.\\d+)?)(kg|g|gm|ml|l|ltr)$", I).matches(key) -> out += key
                key in UNITS && out.isNotEmpty() -> out += UNITS.getValue(key)
                key in FILLERS -> Unit
                key in WORDS -> out += WORDS.getValue(key)
                isDevanagari(tok) -> return problem(GroceryCommand.Problem.Unknown(tok))
                else -> out += tok
            }
            i++
        }
        val query = out.joinToString(" ").trim()
        if (query.isEmpty() || query.all { it.isDigit() || it.isWhitespace() }) return problem(GroceryCommand.Problem.NoItem)
        val q = quantity ?: 1
        if (q < 1 || q > MAX_QUANTITY) return problem(GroceryCommand.Problem.TooMany(MAX_QUANTITY))
        return GroceryCommand.Order(query.take(80), q, addOnly, utterance)
    }

    /** English for a Hindi / Hinglish grocery word ("अमूल" -> "Amul"), or null. */
    fun english(word: String): String? = WORDS[norm(word)]

    private fun numberOf(tok: String): Int? {
        val k = norm(tok)
        if (k.isNotEmpty() && k.all { it.isDigit() }) return k.toIntOrNull()
        return NUMBER_WORDS[k]
    }
}
