package app.saathi.agent

/** What came back from the model, after the strict checks (hard rule 10: validate every model output). */
sealed interface BrainParse {
    data class Ok(val decision: BrainDecision) : BrainParse

    /** Not usable (no JSON, bad types, unknown keys, out-of-range quantity): retry once, then the rules. */
    data class Invalid(val why: String) : BrainParse

    /** The model asked for something only the user may do (yes, send, place_order, pay …): never retried. */
    data class Forbidden(val intent: String) : BrainParse
}

/**
 * Strict reading of the model's answer. Accepts exactly one flat JSON object with the keys
 * intent (required, one of [BrainIntent]), target / query (string or null), quantity (whole
 * number 1..10 or null) and reply (non-empty string). Anything else is [BrainParse.Invalid];
 * an intent that answers for the user or acts irreversibly is [BrainParse.Forbidden].
 */
object BrainParser {
    const val MAX_TARGET = 80
    const val MAX_QUERY = 80
    const val MAX_REPLY = 300
    const val MIN_QTY = 1
    const val MAX_QTY = 10

    private val KEYS = setOf("intent", "target", "quantity", "query", "reply")

    fun parse(raw: String?): BrainParse {
        if (raw.isNullOrBlank()) return BrainParse.Invalid("empty")
        val t = raw.trim()
        // The engine returns what follows the prefill {"intent":" ; a model may also restart the object.
        val obj = (if (t.startsWith("{")) Json.firstObject(t) else Json.firstObject(BrainPrompt.PREFILL + t) ?: Json.firstObject(t))
            ?: return BrainParse.Invalid("no json object")
        val extra = obj.keys - KEYS
        if (extra.isNotEmpty()) return BrainParse.Invalid("unknown keys")
        val intentWire = obj["intent"] as? String ?: return BrainParse.Invalid("intent missing")
        val intent = BrainIntent.of(intentWire)
        if (intent == null) {
            return if (BrainIntent.FORBIDDEN.containsMatchIn(intentWire)) BrainParse.Forbidden(intentWire.take(30))
            else BrainParse.Invalid("unknown intent")
        }
        fun str(key: String, max: Int): Result<String?> = when (val v = obj[key]) {
            null -> Result.success(null)
            is String -> if (v.length > max) Result.failure(IllegalArgumentException(key)) else Result.success(v.trim().ifEmpty { null })
            else -> Result.failure(IllegalArgumentException(key))
        }
        val target = str("target", MAX_TARGET).getOrElse { return BrainParse.Invalid("target") }
        val query = str("query", MAX_QUERY).getOrElse { return BrainParse.Invalid("query") }
        val quantity: Int? = when (val q = obj["quantity"]) {
            null -> null
            is Long -> if (q in MIN_QTY..MAX_QTY) q.toInt() else return BrainParse.Invalid("quantity out of range")
            is Double -> if (q == Math.floor(q) && q >= MIN_QTY && q <= MAX_QTY) q.toInt() else return BrainParse.Invalid("quantity out of range")
            else -> return BrainParse.Invalid("quantity")
        }
        val reply = (obj["reply"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_REPLY } ?: return BrainParse.Invalid("reply")
        return BrainParse.Ok(BrainDecision(intent, target, quantity, query, reply))
    }

    /** True when [partial] model output already holds a whole JSON object (the engine can stop early). */
    fun looksComplete(partial: String): Boolean = Json.complete(partial)

    // -- grounding ------------------------------------------------------------------------------

    /** Prices: "₹27", "₹ 1,299", "27 rupees", "27 rs", "27 रुपये". */
    private val PRICE = Regex("₹\\s?\\d[\\d,.]*|\\b\\d[\\d,.]*\\s*(?:rs\\.?|rupees?|rupaye|rupay|रुपये|रुपए|रु\\.?)", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("\\d+")

    /** A reply must never claim, offer or ask for an irreversible step: that is the Confirm sheet's job (12 Oct). */
    private val CLAIMS = Regex(
        "\\b(order|ordered|ordering|placed|place|pay|paid|payment|send|sent|sending|confirm|confirmed|checkout|check out|upi|pin|otp|password|bought|purchase)\\b|" +
            "भेज|भुगतान|ऑर्डर|पेमेंट|पिन|ओटीपी|पासवर्ड",
        RegexOption.IGNORE_CASE,
    )

    /** Common grocery / brand words: one in the reply must be on the screen or in the user's words. */
    private val PRODUCT_WORDS: Set<String> = setOf(
        "milk", "bread", "eggs", "egg", "butter", "curd", "paneer", "cheese", "atta", "rice", "dal", "sugar", "salt", "oil", "ghee",
        "tea", "coffee", "biscuits", "biscuit", "chips", "namkeen", "juice", "water", "soda", "coke", "cola", "pepsi", "sprite",
        "fanta", "thums", "limca", "maggi", "noodles", "chocolate", "icecream", "soap", "shampoo", "onion", "potato", "tomato",
        "banana", "apple", "mango", "amul", "nestle", "britannia", "parle", "haldiram", "tata", "aashirvaad", "fortune",
        "dairy", "lays", "kurkure", "cadbury", "dettol", "colgate", "surf", "ariel", "harpic", "redbull", "bisleri", "kinley",
        "दूध", "ब्रेड", "अंडे", "मक्खन", "दही", "पनीर", "आटा", "चावल", "दाल", "चीनी", "नमक", "तेल", "घी", "चाय", "कॉफी",
    )

    /** Capitalised Latin words a reply may use without them being on screen. */
    private val FREE_CAPS = setOf("i", "i'm", "i'll", "i've", "ok", "okay", "saathi", "sure", "sorry", "hi", "hello", "thanks", "thank", "you")

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").replace(" ", "")

    /**
     * True when [reply] only states things the screen shows: every price and number in it is in the
     * screen summary (or the user's own words for plain numbers), every product word is on screen or
     * was said by the user, every capitalised name (not at a sentence start) is on screen or said, and
     * it claims / offers no send, payment or order. Otherwise the caller uses a template.
     */
    fun grounded(reply: String, summary: ScreenSummary, utterance: String): Boolean {
        if (reply.isBlank() || reply.length > MAX_REPLY) return false
        if (CLAIMS.containsMatchIn(reply)) return false
        val screen = summary.quotable
        val screenNorm = norm(screen)
        for (m in PRICE.findAll(reply)) {
            val digits = NUMBER.findAll(m.value).joinToString("") { it.value }
            if (digits.isEmpty()) return false
            val inScreen = Regex("₹\\s?([\\d,.]+)").findAll(screen).any { it.groupValues[1].filter(Char::isDigit) == digits }
            if (!inScreen) return false
        }
        // The user's words, with Hindi grocery words also in English ("doodh" -> milk).
        val heard = PickDialog.words(utterance).toSet()
        val screenTokens = EchoGuard.tokens(screen.replace("₹", " ")).toSet()
        for (n in NUMBER.findAll(reply)) {
            if (n.value !in screenTokens && n.value !in heard) return false
        }
        val words = EchoGuard.tokens(reply)
        for (w in words) {
            if (w in PRODUCT_WORDS && w !in screenTokens && w !in heard && !screenNorm.contains(w)) return false
        }
        // Capitalised words not at a sentence start: names (brands, products, people) must be on screen or said.
        val starts = Regex("(?:^|[.!?।]\\s+)(\\p{Lu})").findAll(reply).mapNotNull { it.groups[1]?.range?.first }.toSet()
        for (m in Regex("(?<![\\p{L}'-])\\p{Lu}[\\p{L}'-]*").findAll(reply)) {
            if (m.range.first in starts) continue
            val l = m.value.lowercase()
            if (l in FREE_CAPS) continue
            if (l !in screenTokens && l !in heard && !screenNorm.contains(norm(l))) return false
        }
        return true
    }

    /** At most two sentences, as said by the guide. */
    fun twoSentences(reply: String): String {
        val parts = Regex("(?<=[.!?।])\\s+").split(reply.trim()).filter { it.isNotBlank() }
        return parts.take(2).joinToString(" ")
    }

    // -- a minimal, strict JSON reader (flat objects only) ----------------------------------------

    internal object Json {
        /** The first balanced {...} in [text], parsed; null if none or not a flat object of scalars. */
        fun firstObject(text: String): Map<String, Any?>? {
            val start = text.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inStr = false
            var esc = false
            for (i in start until text.length) {
                val c = text[i]
                if (inStr) {
                    when {
                        esc -> esc = false
                        c == '\\' -> esc = true
                        c == '"' -> inStr = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return runCatching { Reader(text.substring(start, i + 1)).obj() }.getOrNull()
                    }
                }
            }
            return null
        }

        /** True when [text] already holds a complete top-level object (the engine stops early on it). */
        fun complete(text: String): Boolean {
            val t = text.trimStart()
            return if (t.startsWith("{")) firstObject(t) != null else firstObject(BrainPrompt.PREFILL + t) != null || firstObject(t) != null
        }

        private class Reader(val s: String) {
            var i = 0

            fun obj(): Map<String, Any?> {
                ws(); expect('{')
                val out = LinkedHashMap<String, Any?>()
                ws()
                if (peek() == '}') { i++; end(); return out }
                while (true) {
                    ws()
                    val k = string()
                    require(k !in out) { "duplicate key" }
                    ws(); expect(':'); ws()
                    out[k] = value()
                    ws()
                    when (next()) {
                        ',' -> continue
                        '}' -> { end(); return out }
                        else -> throw IllegalArgumentException("bad object")
                    }
                }
            }

            private fun end() { ws(); require(i == s.length) { "trailing" } }

            private fun value(): Any? = when (val c = peek()) {
                '"' -> string()
                'n' -> { lit("null"); null }
                't' -> { lit("true"); true }
                'f' -> { lit("false"); false }
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("not a scalar")
            }

            private fun number(): Any {
                val st = i
                if (peek() == '-') i++
                while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
                val t = s.substring(st, i)
                return t.toLongOrNull() ?: t.toDouble()
            }

            private fun string(): String {
                expect('"')
                val b = StringBuilder()
                while (true) {
                    require(i < s.length) { "unterminated" }
                    val c = s[i++]
                    when {
                        c == '"' -> return b.toString()
                        c == '\\' -> {
                            when (val e = s[i++]) {
                                '"', '\\', '/' -> b.append(e)
                                'n' -> b.append(' ')
                                't' -> b.append(' ')
                                'r' -> b.append(' ')
                                'b', 'f' -> Unit
                                'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                                else -> throw IllegalArgumentException("bad escape")
                            }
                        }
                        c < ' ' -> b.append(' ')
                        else -> b.append(c)
                    }
                }
            }

            private fun lit(w: String) { require(s.startsWith(w, i)) { "bad literal" }; i += w.length }
            private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
            private fun peek(): Char = s.getOrElse(i) { '\u0000' }
            private fun next(): Char = s.getOrElse(i++) { '\u0000' }
            private fun expect(c: Char) { require(next() == c) { "expected $c" } }
        }
    }
}
