package app.saathi.agent

import app.saathi.safety.PIN_SCREEN_REGEX
import app.saathi.safety.SECRET_REGEX
import java.text.Normalizer as TextNormalizer

/**
 * What to do with text that was ALREADY in a chat's message box (the user's own unsent text)
 * when a reply reaches that chat. KEEP is the default and the answer to anything unclear.
 */
enum class DraftChoice {
    /** Touch nothing; end the task. */
    KEEP,

    /** Remember the user's text, put the new message in its place; on anything but YES put it back. */
    REPLACE,

    /** Add " " + the new message after the user's text; on anything but YES put the original back. */
    ADD,

    /** Type nothing; read the user's own text back and send it only on a clear YES. */
    SEND,
}

/** What a Cancel / No on the send confirmation does to the message box. */
enum class AfterCancel {
    /** Saathi typed into an empty box: remove exactly that text. */
    REMOVE_TYPED,

    /** Saathi replaced / added to the user's text: put the user's original text back. */
    RESTORE_ORIGINAL,

    /** Saathi typed nothing (sending the user's own text): leave the box as it is. */
    LEAVE_AS_IS,
}

/**
 * Asked (voice + buttons) when the chat's box already holds the user's own text.
 * [existing] is verbatim on screen (WhatsApp's message box); [newMessage] is what the user just
 * dictated. [canChange] false (several lines, or the gate wouldn't let Saathi type it back):
 * only KEEP and SEND are offered, because the original couldn't be put back exactly.
 */
data class DraftChoiceRequest(
    val chat: String,
    val existing: String,
    val newMessage: String,
    val canChange: Boolean,
)

/**
 * The spoken answer to [DraftChoiceRequest]. Like [ConfirmDialog] it defaults to the safe side:
 * any "no / don't / cancel" means KEEP; an unclear answer (or silence) is asked once more, then KEEP.
 * English, Hindi (Devanagari) and Hinglish words.
 */
class DraftChoiceDialog(private val maxUnclear: Int = 2) {
    enum class Next { KEEP, REPLACE, ADD, SEND, ASK_AGAIN }

    var unclear: Int = 0
        private set

    fun hear(reply: String, canChange: Boolean = true): Next {
        val c = classify(reply) ?: return unclearOnce()
        if (!canChange && (c == DraftChoice.REPLACE || c == DraftChoice.ADD)) return unclearOnce()
        return when (c) {
            DraftChoice.KEEP -> Next.KEEP
            DraftChoice.REPLACE -> Next.REPLACE
            DraftChoice.ADD -> Next.ADD
            DraftChoice.SEND -> Next.SEND
        }
    }

    /** Nothing heard / recogniser error: counts as unclear. */
    fun silence(): Next = unclearOnce()

    private fun unclearOnce(): Next = if (++unclear >= maxUnclear) Next.KEEP else Next.ASK_AGAIN

    companion object {
        private val WORD = Regex("[^\\p{L}\\p{M}\\p{N}']+")

        private val NEGATIVE = setOf(
            "no", "nope", "not", "don't", "dont", "cancel", "stop", "nahi", "nahin", "nai", "mat",
            "ruko", "नहीं", "नही", "मत", "रुको", "रुकिए",
        )
        private val KEEP = setOf(
            "keep", "leave", "rehne", "rehene", "rahne", "rehnedo", "rakho", "rakh", "chhod", "chhodo", "chod", "chodo",
            "रहने", "रखो", "रख", "छोड", "छोडो", "छोड़", "छोड़ो",
        )
        private val REPLACE = setOf("replace", "change", "overwrite", "badal", "badlo", "badle", "बदल", "बदलो", "बदलें", "बदलिए")
        private val ADD = setOf("add", "append", "jod", "jodo", "jodd", "joddo", "जोड", "जोडो", "जोडें", "जोड़", "जोड़ो", "जोड़ें")
        private val SEND = setOf("send", "bhej", "bhejo", "bhejdo", "bhejiye", "भेज", "भेजो", "भेजें", "भेजिए")

        /** The word lists in the same normal form as what was heard. */
        private object Normed {
            private fun of(set: Set<String>) = set.flatMap { words(it) }.toSet()
            val negative = of(NEGATIVE)
            val keep = of(KEEP)
            val replace = of(REPLACE)
            val add = of(ADD)
            val send = of(SEND)
        }

        /** Lowercase, Devanagari nukta dropped (ड़ typed either way matches), split into words. */
        internal fun words(reply: String): Set<String> {
            val n = TextNormalizer.normalize(reply.lowercase(), TextNormalizer.Form.NFD).replace("़", "")
            return n.split(WORD).filter { it.isNotEmpty() }.toSet()
        }

        /** The choice the words name, or null when unclear (nothing, or two that conflict). */
        fun classify(reply: String): DraftChoice? {
            val w = words(reply)
            if (w.isEmpty()) return null
            fun has(set: Set<String>) = w.any { it in set }
            if (has(Normed.negative)) return DraftChoice.KEEP // "don't send", "mat badlo": the safe side
            val keep = has(Normed.keep)
            val replace = has(Normed.replace)
            val add = has(Normed.add)
            val send = has(Normed.send)
            return when {
                keep -> if (replace || add || send) null else DraftChoice.KEEP
                replace && add -> null
                replace -> DraftChoice.REPLACE // "replace it and send": sending still needs a yes
                add -> DraftChoice.ADD
                send -> DraftChoice.SEND
                else -> null
            }
        }
    }
}

/**
 * Text that looks like a PIN, OTP, password or other code. Such a draft is never read out,
 * typed back or kept (hard rule 2): Saathi leaves it alone. Uses the safety port's secret words
 * (R7) and PIN-screen phrases, plus digit / password shapes. Errs on the side of "private".
 */
object PrivateText {
    private val CODE_WORDS = Regex("(?i)\\b(code|verification|verify|cvv|card)\\b|कोड")
    private val DIGIT_RUN = Regex("(?<!\\d)\\d{4,8}(?!\\d)")
    private val SPACES = Regex("\\s+")

    fun looksPrivate(text: String): Boolean {
        val s = text.trim()
        if (s.isEmpty()) return false
        if (SECRET_REGEX.matcher(s).find() || PIN_SCREEN_REGEX.matcher(s).find()) return true
        val compact = s.replace(Regex("[\\s\\-.]"), "")
        if (compact.length >= 3 && compact.all { it.isDigit() }) return true // a bare number: PIN / OTP / card
        val hasDigit = s.any { it.isDigit() }
        if (hasDigit && CODE_WORDS.containsMatchIn(s)) return true
        val tokens = s.split(SPACES)
        if (tokens.size <= 3 && DIGIT_RUN.containsMatchIn(s)) return true
        // One "word" mixing letters with digits / symbols, e.g. a password.
        if (tokens.size == 1 && s.length >= 6 && s.any { it.isLetter() } && s.any { it.isDigit() || !it.isLetterOrDigit() }) return true
        return false
    }
}
