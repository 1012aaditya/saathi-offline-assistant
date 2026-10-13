package app.saathi.agent

import app.saathi.safety.NO

/**
 * The spoken answer to a [PickRequest] ("Amul Taaza 500 ml for ₹27, or Mother Dairy 500 ml for
 * ₹28?"). Like [ConfirmDialog] it defaults to the safe side: "no / cancel / neither" cancels; an
 * unclear answer (or silence) is asked once more, then cancels. Nothing is added or ordered from
 * here: the order still needs the read-back and a clear yes.
 *
 * An answer is either an ordinal ("first", "doosra", "दूसरा") or words that belong to exactly one
 * option ("Mother Dairy", "अमूल वाला"). A reply naming two options is unclear (it is what an echo
 * of the question sounds like), and so is a reply that repeats the hint's words ([EchoGuard]).
 */
class PickDialog(private val options: List<String>, private val maxUnclear: Int = 2) {
    sealed interface Next {
        data class Picked(val index: Int) : Next
        data object Cancel : Next
        data object AskAgain : Next
    }

    var unclear: Int = 0
        private set

    /** [echoSources]: Saathi's recent prompts and the sheet's own words other than the options. */
    fun hear(reply: String, echoSources: List<String> = emptyList()): Next {
        // The options themselves are allowed in a reply (that's how one is named): leave them out of the sources.
        val sources = echoSources.map { s -> options.fold(s) { acc, o -> acc.replace(o, " ") } }
        if (EchoGuard.overlap(reply, sources, ignore = IGNORE + optionWords()) != EchoGuard.Reason.NONE) return unclearOnce()
        return when (val c = classify(reply, options)) {
            CANCEL -> Next.Cancel
            null -> unclearOnce()
            else -> Next.Picked(c)
        }
    }

    fun silence(): Next = unclearOnce()

    private fun unclearOnce(): Next = if (++unclear >= maxUnclear) Next.Cancel else Next.AskAgain

    private fun optionWords(): Set<String> = options.flatMap { words(it) }.toSet()

    companion object {
        const val CANCEL = -1

        private val ORDINALS: Map<String, Int> = mapOf(
            "first" to 0, "1st" to 0, "pehla" to 0, "pahla" to 0, "pehli" to 0, "pahli" to 0, "पहला" to 0, "पहली" to 0, "पहले" to 0,
            "second" to 1, "2nd" to 1, "doosra" to 1, "dusra" to 1, "doosri" to 1, "dusri" to 1, "दूसरा" to 1, "दूसरी" to 1, "दूसरे" to 1,
            "third" to 2, "3rd" to 2, "teesra" to 2, "tisra" to 2, "teesri" to 2, "तीसरा" to 2, "तीसरी" to 2, "तीसरे" to 2,
        ).mapKeys { EchoGuard.tokens(it.key).first() }

        private val LAST = setOf("last", "aakhri", "akhri", "आखिरी", "आख़िरी").flatMap { EchoGuard.tokens(it) }.toSet()

        private val CANCEL_WORDS: Set<String> = (
            NO + setOf("none", "neither", "nope", "dont", "don't", "koi", "कोई", "rehne", "रहने", "chhodo", "छोड़ो", "wait")
            ).flatMap { EchoGuard.tokens(it) }.toSet()

        /** Words that say nothing about which option ("the", "one", "wala"). */
        private val FILLERS: Set<String> = setOf(
            "the", "one", "that", "this", "it", "please", "i", "want", "take", "give", "me", "a", "wala", "wali", "wale",
            "वाला", "वाली", "वाले", "chahiye", "चाहिए", "do", "दो", "de", "दे", "for", "ka", "ki", "ke", "का", "की", "के",
            "rupees", "rupaye", "रुपये", "or", "ya", "या", "option",
        ).flatMap { EchoGuard.tokens(it) }.toSet()

        private val IGNORE = ORDINALS.keys + LAST + CANCEL_WORDS + FILLERS

        /** Tokens, with Hindi product words also in English ("अमूल" -> "amul") so they match the screen. */
        internal fun words(text: String): List<String> =
            EchoGuard.tokens(text.replace("₹", " ")).flatMap { w ->
                val en = OrderParser.english(w)?.lowercase()
                if (en == null || en == w) listOf(w) else listOf(w) + EchoGuard.tokens(en)
            }

        /** Index of the option the reply names, [CANCEL], or null when unclear. */
        fun classify(reply: String, options: List<String>): Int? {
            val w = words(reply)
            if (w.isEmpty()) return null
            if (w.any { it in CANCEL_WORDS }) return CANCEL
            val optionTokens = options.map { words(it).toSet() }
            // Words only one option has ("mother", "dairy", "28"); words all share don't help.
            val distinct = optionTokens.mapIndexed { i, set -> set - optionTokens.filterIndexed { j, _ -> j != i }.flatten().toSet() - FILLERS }
            val named = distinct.indices.filter { i -> w.any { it in distinct[i] } }
            val ordinal = w.mapNotNull { ORDINALS[it] }.toSet() + (if (w.any { it in LAST }) setOf(options.size - 1) else emptySet())
            // A bare digit is an ordinal ("2"); inside a longer reply it may be a size ("1 litre").
            val digit = w.singleOrNull()?.toIntOrNull()?.let { it - 1 }?.takeIf { it in options.indices }
            val ords = (ordinal + listOfNotNull(digit)).filter { it in options.indices }.toSet()
            return when {
                named.size > 1 -> null // names two options: unclear (or an echo of the question)
                ords.size > 1 -> null
                named.size == 1 && ords.isNotEmpty() && ords.single() != named.single() -> null
                named.size == 1 -> named.single()
                ords.size == 1 -> ords.single()
                else -> null
            }
        }
    }
}
