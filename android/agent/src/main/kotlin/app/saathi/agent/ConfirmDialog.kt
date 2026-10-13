package app.saathi.agent

import app.saathi.safety.Answer
import app.saathi.safety.NO

/**
 * The spoken yes/no before an irreversible step (hard rule 5): defaults to cancel. Heard only
 * through the shared [VoiceAnswer] pipeline (timing, length, echo, decisive words). Only a short,
 * explicit yes from the user sends; any no cancels; anything else (or silence) -> ask once more
 * -> still unclear -> cancel.
 *
 * Strict on purpose (13 Oct: a send was confirmed with no human answer while Saathi's own
 * question was being spoken):
 *  - YES needs an explicit yes word (yes / yeah / haan / हाँ …) in at most [MAX_YES_WORDS] words,
 *    and every other word must be a harmless filler ("send it", "bhej do", "please", "ji").
 *    "send", "bhejo" or "भेज दो" alone is NOT a yes.
 *  - yes and no together -> unclear.
 *  - An echo of what Saathi said / showed ([EchoGuard]) -> unclear. In particular, if the yes
 *    word itself appears in the prompt or on the sheet, it can't be told from an echo, so the
 *    prompts never contain a yes word (see [Texts.confirmQuestion]).
 */
class ConfirmDialog(
    maxUnclear: Int = 2,
    /** More harmless words allowed next to a yes, e.g. [ORDER_FILLERS] on the order sheet ("yes order it"). */
    private val extraFillers: Set<String> = emptySet(),
) {
    enum class Next { SEND, CANCEL, ASK_AGAIN }

    /** The shared answer pipeline (timing, length, echo, decisive words): every sheet goes through it. */
    private val answers = VoiceAnswer(maxUnclear)

    private val rules = object : AnswerRules<Unit> {
        override val vocabulary: Set<String> = IGNORE + extraFillers
        override val decisive: Set<String> = YES_WORDS
        override fun read(reply: String): Reading<Unit> = when (classify(reply, extraFillers)) {
            Answer.YES -> Reading.Act(Unit, byName = false) // the yes word is decisive: never in the prompt
            Answer.NO -> Reading.Safe
            Answer.UNCLEAR -> Reading.Unclear
        }
    }

    val unclear: Int get() = answers.unclear

    /** Why the last reply was (not) taken, for the log. */
    val lastWhy: VoiceAnswer.Why get() = answers.lastWhy

    /**
     * [echoSources]: what Saathi said lately and the sheet's own words (title, message, buttons,
     * hints). A reply that overlaps them is treated as unclear, never as yes.
     */
    fun hear(heard: Heard, echoSources: List<String>): Next = next(answers.hear(heard, echoSources, rules))

    /** [hear] for a transcript with no timing (tests; Saathi hadn't spoken). */
    fun hear(reply: String, echoSources: List<String> = emptyList()): Next = hear(Heard(reply), echoSources)

    /** Nothing heard / recogniser error / discarded echo: counts as unclear. */
    fun silence(): Next = next(answers.silence())

    private fun next(r: VoiceAnswer.Result<Unit>): Next = when (r) {
        is VoiceAnswer.Result.Act -> Next.SEND
        VoiceAnswer.Result.Safe -> Next.CANCEL
        VoiceAnswer.Result.AskAgain -> Next.ASK_AGAIN
    }

    companion object {
        const val MAX_YES_WORDS = 4

        /** Explicit yes words. One of these must be in a YES reply, said by the user. */
        val YES_WORDS: Set<String> = setOf(
            "yes", "yeah", "yep", "yup", "haan", "haa", "ha", "han", "haanji", "hanji",
            "हाँ", "हां", "हा", "हाँजी", "हांजी",
        ).flatMap { EchoGuard.tokens(it) }.toSet()

        /** Words allowed next to a yes word ("yes send it", "haan bhej do", "ji haan"). Nothing else. */
        private val YES_FILLERS: Set<String> = setOf(
            "send", "it", "please", "now", "go", "ahead", "do", "ji", "jee", "bhej", "bhejo", "bhejdo", "de", "dijiye",
            "dena", "kar", "karo", "kardo", "जी", "भेज", "भेजो", "दो", "दीजिए", "दे", "कर", "करो",
        ).flatMap { EchoGuard.tokens(it) }.toSet()

        private val NO_WORDS: Set<String> = (
            NO + setOf("nope", "nah", "dont", "don't", "not", "wait", "nahin", "रुकिए", "मत", "नहीं", "नही")
            ).flatMap { EchoGuard.tokens(it) }.toSet()

        /** Words to leave out of the overlap ratio: the answer vocabulary and fillers. */
        private val IGNORE: Set<String> = YES_WORDS + YES_FILLERS + NO_WORDS

        /**
         * Grocery sheets (order / empty the cart): "yes order it", "haan order karo", "yes remove them".
         * Still no yes without an explicit yes word; these words alone are never a yes.
         */
        val ORDER_FILLERS: Set<String> = setOf(
            "order", "place", "the", "my", "them", "all", "ऑर्डर", "मंगाओ", "mangao", "mangwao", "remove", "empty", "clear",
            "khali", "khaali", "hatao", "hata", "हटाओ", "हटा", "खाली", "cart", "कार्ट",
        ).flatMap { EchoGuard.tokens(it) }.toSet()

        fun classify(reply: String, extra: Set<String> = emptySet()): Answer {
            val w = EchoGuard.tokens(reply)
            if (w.isEmpty()) return Answer.UNCLEAR
            val lower = reply.lowercase()
            val no = w.any { it in NO_WORDS } || "do not" in lower
            val yes = w.any { it in YES_WORDS }
            return when {
                no && yes -> Answer.UNCLEAR // "yes no", "haan nahi": ask again
                no -> Answer.NO
                yes && w.size <= MAX_YES_WORDS && w.all { it in YES_WORDS || it in YES_FILLERS || it in extra } -> Answer.YES
                else -> Answer.UNCLEAR
            }
        }

        /** True if [text] contains a word that [classify] would take as yes (prompts must not). */
        fun containsYesWord(text: String): Boolean = EchoGuard.tokens(text).any { it in YES_WORDS }
    }
}
