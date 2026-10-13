package app.saathi.agent

/**
 * THE voice-answer pipeline. Every sheet that waits for the user's spoken answer (send / order
 * confirm, empty-cart confirm, "Open Camera?", the unsent-text choice, "Which Rahul?", the
 * grocery "which one?") hears its answer only through [VoiceAnswer.hear]; the sheet's own dialog
 * class ([ConfirmDialog], [DraftChoiceDialog], [ContactChoiceDialog], [PickDialog]) only supplies
 * its [AnswerRules]. Nothing else may turn a transcript into an answer.
 *
 * Why (13 Oct, twice on the owner's tablet): a WhatsApp reply was confirmed, and later a Blinkit
 * item was added to the cart, with no human answer. The second time the item-pick sheet took a
 * 54-character transcript naming one option (Saathi's own question, or TalkBack reading the
 * sheet) as the user's pick: that sheet had its own, looser checks, and it removed the option
 * names from the echo sources because "naming an option is how you answer".
 *
 * The rules, in order (any failure = UNCLEAR: ask once more, then the sheet's safe default):
 *  1. Heard at least [EchoGuard.MIN_HEARD_AFTER_SPEECH_MS] after Saathi's speech ended
 *     ([Heard.msAfterSpeech]); the Voice interlock already never opens the mic while Saathi
 *     speaks, nor sooner than [EchoGuard.LISTEN_AFTER_SPEECH_MS] after.
 *  2. Short: at most [MAX_ANSWER_WORDS] words. A long transcript is what an echo sounds like.
 *  3. Not an echo ([EchoGuard.overlap]) of Saathi's recent speech + EVERYTHING the sheet shows or
 *     says (title, question, options, buttons, content descriptions, hints). Option names stay in
 *     the sources: a 3-word run of an option is an echo too.
 *  4. The deciding word ([AnswerRules.decisive]: the yes words, the ordinals "first / doosra / 2")
 *     must not be anywhere in those sources. That's why no sheet text contains a yes word or an
 *     ordinal (tested).
 *  5. An answer decided by a word the sheet itself shows (an option's name, "replace") can't be
 *     told from an echo by its words, so it must be a name said on its own: at most
 *     [MAX_NAME_WORDS] words (and, as everything, heard after the quiet gap of rule 1).
 *     "first" / "doosra" / "2" (rule 4) is the reliable way to pick.
 *  6. No / cancel / keep / don't open (the safe default) is taken as it is.
 */
object VoiceAnswers {
    /** Any answer: at most this many words. */
    const val MAX_ANSWER_WORDS = 4

    /** An answer decided by a word the sheet shows (option name, "replace"): at most this many words. */
    const val MAX_NAME_WORDS = 3
}

/**
 * A transcript and how long after the end of Saathi's last speech it was heard (ms).
 * [LONG_AFTER]: Saathi hasn't spoken (nothing of its own to echo). The app fills this from the
 * Voice interlock; tests pass it explicitly.
 */
data class Heard(val text: String, val msAfterSpeech: Long = LONG_AFTER) {
    companion object {
        const val LONG_AFTER = Long.MAX_VALUE
    }
}

/** How a sheet's own rules read a reply (before / without the shared checks). */
sealed interface Reading<out T> {
    /** No / cancel / keep / don't open: the sheet's safe default. */
    data object Safe : Reading<Nothing>

    /**
     * An answer that does something. [byName]: decided by a word the sheet itself shows or says
     * (an option's name, "replace"), so rule 5 applies.
     */
    data class Act<out T>(val value: T, val byName: Boolean) : Reading<T>

    data object Unclear : Reading<Nothing>
}

/** What a sheet's dialog supplies to [VoiceAnswer]. Tokens are [EchoGuard.tokens] form. */
interface AnswerRules<out T> {
    /** The answer vocabulary and fillers: left out of the echo word ratio. */
    val vocabulary: Set<String>

    /** Words that decide an [Reading.Act] by themselves; one of them in the sources = echo. */
    val decisive: Set<String>

    fun read(reply: String): Reading<T>
}

/**
 * One sheet's spoken answers: counts unclear ones; unclear [maxUnclear] times = the safe default.
 */
class VoiceAnswer(private val maxUnclear: Int = 2) {
    sealed interface Result<out T> {
        data class Act<out T>(val value: T) : Result<T>

        /** The sheet's safe default (Cancel / Keep / Don't open / no pick). */
        data object Safe : Result<Nothing>

        data object AskAgain : Result<Nothing>
    }

    /** Why the last hearing was (not) taken; logged by the sheets (never the transcript). */
    enum class Why { OK, EMPTY, TOO_SOON, TOO_LONG, ECHO, NAME_TOO_LONG, UNCLEAR, SILENCE }

    var unclear: Int = 0
        private set

    var lastWhy: Why = Why.OK
        private set

    /** [echoSources]: what Saathi said lately + every text the sheet shows or says. */
    fun <T> hear(heard: Heard, echoSources: List<String>, rules: AnswerRules<T>): Result<T> {
        val (why, reading) = judge(heard, echoSources, rules)
        lastWhy = why
        if (why != Why.OK) return unclearOnce()
        return when (reading) {
            Reading.Safe -> Result.Safe
            is Reading.Act -> Result.Act(reading.value)
            Reading.Unclear -> unclearOnce()
        }
    }

    /** Nothing heard / recognizer error / a result Voice discarded: counts as unclear. */
    fun silence(): Result<Nothing> {
        lastWhy = Why.SILENCE
        return unclearOnce()
    }

    private fun unclearOnce(): Result<Nothing> = if (++unclear >= maxUnclear) Result.Safe else Result.AskAgain

    companion object {
        /** The shared checks (rules 1–6 above). Pure. */
        fun <T> judge(heard: Heard, echoSources: List<String>, rules: AnswerRules<T>): Pair<Why, Reading<T>> {
            val words = EchoGuard.tokens(heard.text)
            if (words.isEmpty()) return Why.EMPTY to Reading.Unclear
            if (heard.msAfterSpeech < EchoGuard.MIN_HEARD_AFTER_SPEECH_MS) return Why.TOO_SOON to Reading.Unclear
            if (words.size > VoiceAnswers.MAX_ANSWER_WORDS) return Why.TOO_LONG to Reading.Unclear
            val echo = EchoGuard.overlap(heard.text, echoSources, ignore = rules.vocabulary, decisive = rules.decisive)
            if (echo != EchoGuard.Reason.NONE) return Why.ECHO to Reading.Unclear
            val r = rules.read(heard.text)
            if (r is Reading.Act && r.byName && words.size > VoiceAnswers.MAX_NAME_WORDS) return Why.NAME_TOO_LONG to Reading.Unclear
            if (r == Reading.Unclear) return Why.UNCLEAR to r
            return Why.OK to r
        }
    }
}

/**
 * Position words for "which one?" sheets (English, Hinglish, Devanagari). [LAST] = the last one.
 * They decide a pick by themselves, so no sheet text may contain them ([containsOrdinal]).
 */
object Ordinals {
    const val LAST = -1

    private val WORDS: Map<String, Int> = mapOf(
        "first" to 0, "1st" to 0, "pehla" to 0, "pahla" to 0, "pehli" to 0, "pahli" to 0, "pehle" to 0, "pahle" to 0,
        "पहला" to 0, "पहली" to 0, "पहले" to 0,
        "second" to 1, "2nd" to 1, "doosra" to 1, "dusra" to 1, "doosri" to 1, "dusri" to 1, "doosre" to 1, "dusre" to 1,
        "दूसरा" to 1, "दूसरी" to 1, "दूसरे" to 1, "दुसरा" to 1, "दुसरी" to 1,
        "third" to 2, "3rd" to 2, "teesra" to 2, "tisra" to 2, "teesri" to 2, "tisri" to 2, "teesre" to 2,
        "तीसरा" to 2, "तीसरी" to 2, "तीसरे" to 2,
        "fourth" to 3, "4th" to 3, "chautha" to 3, "chauthi" to 3, "चौथा" to 3, "चौथी" to 3,
        "last" to LAST, "aakhri" to LAST, "akhri" to LAST, "aakhiri" to LAST, "आखिरी" to LAST, "आख़िरी" to LAST,
    ).mapKeys { EchoGuard.tokens(it.key).single() }

    /** Index for an ordinal word (already [EchoGuard.tokens] form), [LAST], or null. */
    fun of(token: String): Int? = WORDS[token]

    /** A bare number said alone ("2"): 1-based position -> index. */
    fun digit(token: String, count: Int): Int? = token.toIntOrNull()?.let { it - 1 }?.takeIf { it in 0 until count }

    /** Every ordinal word, plus "1" … "4" (as said alone). */
    val decisive: Set<String> = WORDS.keys + (1..4).map { it.toString() }

    /** True if [text] has an ordinal word (sheet texts must not: it would make a real "first" look like an echo). */
    fun containsOrdinal(text: String): Boolean = EchoGuard.tokens(text).any { it in WORDS }
}
