package app.saathi.agent

import app.saathi.safety.Answer
import app.saathi.safety.parseYesNo

/**
 * The spoken yes/no before an irreversible step (hard rule 5): defaults to cancel. A clear yes
 * sends; any no cancels; unclear (or silence) -> ask once more -> still unclear -> cancel.
 */
class ConfirmDialog(private val maxUnclear: Int = 2) {
    enum class Next { SEND, CANCEL, ASK_AGAIN }

    var unclear: Int = 0
        private set

    fun hear(reply: String): Next = when (classify(reply)) {
        Answer.YES -> Next.SEND
        Answer.NO -> Next.CANCEL
        Answer.UNCLEAR -> unclearOnce()
    }

    /** Nothing heard / recogniser error: counts as unclear. */
    fun silence(): Next = unclearOnce()

    private fun unclearOnce(): Next = if (++unclear >= maxUnclear) Next.CANCEL else Next.ASK_AGAIN

    companion object {
        private val EXTRA_YES = setOf("yeah", "yep", "yup", "sure", "send", "bhejo", "भेजो", "हाँजी", "haanji")
        private val EXTRA_NO = setOf("nope", "dont", "don't", "nah", "wait", "रुकिए")
        private val WORD = Regex("[^\\p{L}\\p{M}\\p{N}']+")

        /** The safety port's parser first (any "no" wins), plus a few more English / Hindi words. */
        fun classify(reply: String): Answer {
            val base = parseYesNo(reply)
            if (base == Answer.NO) return Answer.NO
            val lower = reply.lowercase()
            val words = lower.split(WORD).filter { it.isNotEmpty() }.toSet()
            if (words.any { it in EXTRA_NO } || "do not" in lower) return Answer.NO
            if (base == Answer.YES || words.any { it in EXTRA_YES }) return Answer.YES
            return Answer.UNCLEAR
        }
    }
}
