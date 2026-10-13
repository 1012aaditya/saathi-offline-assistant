package app.saathi.agent

import app.saathi.safety.CommandContext

/**
 * Offline name spellings, so a spoken "मम्मी" or "Mummy" can match an on-screen "Mom" and a
 * spoken "राहुल" can match "Rahul Sharma". Replaces the prototype's network transliteration.
 * Matching itself is the safety port's matcher (payeeMatches, token_set_ratio >= 80).
 */
object Names {
    /**
     * The user's own chat ("Message yourself"): "message myself …", "send it to me", "mujhe khud
     * ko bolo …". WhatsApp shows that chat as "<your name> (You)" (or "You"); see [WhatsApp.isSelfChat].
     */
    const val SELF = "Myself"

    private val SELF_PHRASES: Set<String> = setOf(
        "myself", "me", "my self", "self", "my own chat", "my own number", "yourself",
        "khud", "khud ko", "mujhe khud", "mujhe khud ko", "apne aap", "apne aap ko", "apne aapko", "apne ko", "khud ke liye",
        "खुद", "ख़ुद", "खुद को", "मुझे खुद", "मुझे खुद को", "अपने आप", "अपने आप को", "अपने आपको",
    )

    /** True when [phrase] (a captured recipient) means the user themself. */
    fun isSelf(phrase: String): Boolean {
        val p = phrase.trim().lowercase().replace(Regex("\\s+"), " ").trim(',', '.', ':')
        return p in SELF_PHRASES || p == SELF.lowercase()
    }

    /** Relation words people save contacts under, in both scripts. One group = same person. */
    private val RELATIONS: List<Set<String>> = listOf(
        setOf("mom", "mommy", "mummy", "mumma", "mamma", "maa", "mother", "mum", "amma", "ammi", "मम्मी", "माँ", "मां", "मम्मा", "अम्मा", "अम्मी"),
        setOf("dad", "daddy", "papa", "pappa", "father", "pitaji", "abba", "पापा", "पिताजी", "डैडी", "अब्बा"),
        setOf("bhai", "bhaiya", "bhaiyya", "brother", "भाई", "भैया"),
        setOf("didi", "sister", "behen", "dii", "दीदी", "बहन"),
        setOf("nani", "नानी"),
        setOf("nana", "नाना"),
        setOf("dadi", "दादी"),
        setOf("dada", "दादा"),
        setOf("chacha", "चाचा"),
        setOf("chachi", "चाची"),
        setOf("mama", "मामा"),
        setOf("mami", "मामी"),
        setOf("wife", "biwi", "patni", "बीवी", "पत्नी"),
        setOf("husband", "pati", "पति"),
    )

    /** Every spelling to try for a spoken name: itself, its relation group, a Latin transliteration. */
    fun spellings(name: String): List<String> {
        val clean = name.trim()
        if (clean.isEmpty()) return emptyList()
        val out = linkedSetOf(clean)
        val lower = clean.lowercase()
        RELATIONS.firstOrNull { lower in it }?.let { out += it }
        if (clean.any { it in 'ऀ'..'ॿ' }) {
            val latin = transliterate(clean)
            if (latin.isNotBlank()) out += latin
        }
        return out.toList()
    }

    /** Safety context for a contact: R5 then checks the chat title against these spellings. */
    fun contextFor(contact: String?, utterance: String): CommandContext {
        val ctx = CommandContext(command = utterance)
        if (contact != null) ctx.names = spellings(contact)
        return ctx
    }

    private val CONSONANTS: Map<Char, String> = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        // precomposed nukta letters क़ ख़ ग़ ज़ ड़ ढ़ फ़
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "g", '\u095B' to "z", '\u095C' to "r", '\u095D' to "rh", '\u095E' to "f",
    )
    private val NUKTA_FORMS: Map<Char, String> = mapOf('ज' to "z", 'फ' to "f", 'ड' to "r", 'ढ' to "rh", 'क' to "q", 'ग' to "g", 'ख' to "kh")
    private val MATRAS: Map<Char, String> = mapOf(
        'ा' to "a", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "u", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॅ' to "e", 'ॉ' to "o",
    )
    private val VOWELS: Map<Char, String> = mapOf(
        'अ' to "a", 'आ' to "a", 'इ' to "i", 'ई' to "i", 'उ' to "u", 'ऊ' to "u", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o",
    )
    private const val VIRAMA = '्'
    private const val NUKTA = '़'

    /**
     * Simple Devanagari -> Latin for names: consonants carry an inherent "a" except before a
     * matra / virama and at the end of a word (राहुल -> rahul, प्रिया -> priya). Lowercase.
     * Non-Devanagari characters pass through.
     */
    fun transliterate(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            var cons = CONSONANTS[ch]
            if (cons != null) {
                var j = i + 1
                if (j < text.length && text[j] == NUKTA) {
                    cons = NUKTA_FORMS[ch] ?: cons
                    j++
                }
                sb.append(cons)
                val next = text.getOrNull(j)
                when {
                    next == VIRAMA -> j++
                    next != null && next in MATRAS -> {
                        sb.append(MATRAS[next])
                        j++
                    }
                    next == null || !isDevanagariLetter(next) -> Unit // word end: schwa dropped
                    else -> sb.append('a')
                }
                i = j
                continue
            }
            when {
                ch in VOWELS -> sb.append(VOWELS[ch])
                ch == 'ं' || ch == 'ँ' -> sb.append('n')
                ch == 'ः' -> sb.append('h')
                ch in MATRAS -> sb.append(MATRAS[ch])
                ch == NUKTA || ch == VIRAMA -> Unit
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString().lowercase()
    }

    private fun isDevanagariLetter(c: Char): Boolean = c in CONSONANTS || c in VOWELS || c in MATRAS ||
        c == 'ं' || c == 'ँ' || c == 'ः' || c == NUKTA
}
