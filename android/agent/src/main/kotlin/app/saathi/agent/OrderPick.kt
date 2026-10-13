package app.saathi.agent

import app.saathi.safety.NO

/**
 * The spoken answer to a [PickRequest] ("Amul Taaza 500 ml for ₹27, or Mother Dairy 500 ml for
 * ₹28?"). Like [ConfirmDialog] it defaults to the safe side: "no / cancel / neither" cancels; an
 * unclear answer (or silence) is asked once more, then cancels. Nothing is ordered from here:
 * the order still needs the read-back and a clear yes.
 *
 * Heard only through the shared [VoiceAnswer] pipeline. 13 Oct, 22:51 on the tablet: this sheet
 * took a 54-character transcript naming one option (no human had answered) as the pick and added
 * it to the cart, because it removed the option names from the echo sources. Now:
 *  - the options are echo sources like every other sheet text (a 3-word run of one = echo);
 *  - an ordinal ("first", "doosra", "दूसरा", "2" alone) picks; ordinals are never in a sheet text;
 *  - a name picks only when said on its own (at most [VoiceAnswers.MAX_NAME_WORDS] words, every word
 *    a word of that one option or a filler: "Mother Dairy", "अमूल वाला"), after the quiet gap;
 *  - anything longer, naming two options, or mixing in other words is unclear.
 */
class PickDialog(private val options: List<String>, maxUnclear: Int = 2) {
    sealed interface Next {
        data class Picked(val index: Int) : Next
        data object Cancel : Next
        data object AskAgain : Next
    }

    private val answers = VoiceAnswer(maxUnclear)

    val unclear: Int get() = answers.unclear

    /** Why the last reply was (not) taken, for the log. */
    val lastWhy: VoiceAnswer.Why get() = answers.lastWhy

    private val rules = object : AnswerRules<Int> {
        override val vocabulary: Set<String> = IGNORE + options.flatMap { words(it) }
        override val decisive: Set<String> = Ordinals.decisive
        override fun read(reply: String): Reading<Int> = reading(reply, options)
    }

    /** [echoSources]: Saathi's recent prompts and EVERY text of the sheet, options included. */
    fun hear(heard: Heard, echoSources: List<String>): Next = when (val r = answers.hear(heard, echoSources + options, rules)) {
        is VoiceAnswer.Result.Act -> Next.Picked(r.value)
        VoiceAnswer.Result.Safe -> Next.Cancel
        VoiceAnswer.Result.AskAgain -> Next.AskAgain
    }

    /** [hear] for a transcript with no timing (tests; Saathi hadn't spoken). */
    fun hear(reply: String, echoSources: List<String> = emptyList()): Next = hear(Heard(reply), echoSources)

    fun silence(): Next = if (answers.silence() == VoiceAnswer.Result.Safe) Next.Cancel else Next.AskAgain

    companion object {
        const val CANCEL = -1

        private val CANCEL_WORDS: Set<String> = (
            NO + setOf("none", "neither", "nope", "dont", "don't", "koi", "कोई", "rehne", "रहने", "chhodo", "छोड़ो", "wait")
            ).flatMap { EchoGuard.tokens(it) }.toSet()

        /** Words that say nothing about which option ("the", "one", "wala"). */
        private val FILLERS: Set<String> = setOf(
            "the", "one", "that", "this", "it", "please", "i", "want", "take", "give", "me", "a", "wala", "wali", "wale",
            "वाला", "वाली", "वाले", "chahiye", "चाहिए", "do", "दो", "de", "दे", "for", "ka", "ki", "ke", "का", "की", "के",
            "rupees", "rupaye", "रुपये", "or", "ya", "या", "option",
        ).flatMap { EchoGuard.tokens(it) }.toSet()

        private val IGNORE = Ordinals.decisive + CANCEL_WORDS + FILLERS

        /** One word in its forms: itself, and a Hindi product word also in English ("अमूल" -> "amul"). */
        private fun forms(w: String): List<String> {
            val en = OrderParser.english(w)?.lowercase()
            return if (en == null || en == w) listOf(w) else listOf(w) + EchoGuard.tokens(en)
        }

        /** Tokens, with Hindi product words also in English so they match the screen. */
        internal fun words(text: String): List<String> = EchoGuard.tokens(text.replace("₹", " ")).flatMap(::forms)

        /** How the reply picks: [Reading.Safe] (cancel), an option (by ordinal or by name), or unclear. */
        internal fun reading(reply: String, options: List<String>): Reading<Int> {
            val said = EchoGuard.tokens(reply.replace("₹", " "))
            if (said.isEmpty() || options.isEmpty()) return Reading.Unclear
            val w = said.flatMap(::forms)
            if (w.any { it in CANCEL_WORDS }) return Reading.Safe
            val optionTokens = options.map { words(it).toSet() }
            // Words only one option has ("mother", "dairy", "28"); words all share don't help.
            val distinct = optionTokens.mapIndexed { i, set -> set - optionTokens.filterIndexed { j, _ -> j != i }.flatten().toSet() - FILLERS }
            val named = distinct.indices.filter { i -> w.any { it in distinct[i] } }
            val ordinal = w.mapNotNull { Ordinals.of(it) }.map { if (it == Ordinals.LAST) options.size - 1 else it }
            // A bare digit is a position ("2"); inside a longer reply it may be a size ("1 litre").
            val digit = said.singleOrNull()?.let { Ordinals.digit(it, options.size) }
            val ords = (ordinal + listOfNotNull(digit)).filter { it in options.indices }.toSet()
            if (named.size > 1 || ords.size > 1) return Reading.Unclear // names two options: what an echo sounds like
            val pick = ords.singleOrNull() ?: named.singleOrNull() ?: return Reading.Unclear
            if (named.isNotEmpty() && named.single() != pick) return Reading.Unclear
            // Every word said must belong to the picked option, be an ordinal, or be a filler.
            val allowed = optionTokens[pick] + FILLERS + Ordinals.decisive
            if (said.any { s -> forms(s).none { it in allowed } }) return Reading.Unclear
            return Reading.Act(pick, byName = ords.isEmpty())
        }

        /** Index of the option the reply names, [CANCEL], or null when unclear (no timing / echo checks). */
        fun classify(reply: String, options: List<String>): Int? = when (val r = reading(reply, options)) {
            Reading.Safe -> CANCEL
            is Reading.Act -> r.value
            Reading.Unclear -> null
        }
    }
}
