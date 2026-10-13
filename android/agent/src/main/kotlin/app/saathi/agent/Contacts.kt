package app.saathi.agent

import app.saathi.safety.CommandContext
import app.saathi.safety.Element
import app.saathi.safety.Screen
import app.saathi.safety.payeeMatches

/*
 * "Message ANY person": finding who the user named, offline.
 *
 *  1. [Recipients]: the spoken name ("Mom", "राहुल", "Didi") is matched against the names saved in
 *     the phone's contacts, when the user allowed READ_CONTACTS (optional: everything works
 *     without it). One clear match = the saved name is searched first ("Mom" -> "Mummy").
 *  2. [ContactSearch]: WhatsApp's search results (chats AND contacts without a chat, under
 *     "Chats" / "Contacts" headers, or no headers at all) are read; message hits are ignored.
 *     Exactly one person -> open; several -> the user picks ([ContactChoiceDialog]); none ->
 *     the honest "I couldn't find Rahul in WhatsApp". Never a guess.
 *
 * Contact names stay in memory; logs carry counts only.
 */

/** Who the user means, worked out before any screen is seen. */
data class Recipient(
    val spoken: String,
    /** The phone contact the spoken name resolved to, exactly as saved; null when none / several. */
    val saved: String?,
    /** The phone's contacts know someone by this name (for an honest "not on WhatsApp"). */
    val inPhone: Boolean,
    /** What to type into WhatsApp's search, in order (at most [Recipients.MAX_QUERIES]). */
    val queries: List<String>,
    /** [saved] is the spoken name / one of its spellings or relation words, not only a fuzzy match. */
    val savedExact: Boolean = true,
) {
    /**
     * Safety context (R5 names): every spelling of the spoken name, plus the saved contact name
     * when it matched exactly ("Mom" -> "Mummy"). A fuzzy-only match ("Rahul" -> "Rahul Sharma")
     * is NOT added: it would count as an exact spelling and skip asking which Rahul.
     */
    fun context(utterance: String): CommandContext {
        val ctx = Names.contextFor(spoken, utterance)
        if (saved != null && savedExact) ctx.names = (ctx.names + saved).distinct()
        return ctx
    }
}

object Recipients {
    /** Searches to try before saying the person isn't in WhatsApp (each one is a typing step). */
    const val MAX_QUERIES = 4

    sealed interface Match {
        /** [exact]: the name or one of its spellings / relation words, not only a fuzzy match. */
        data class One(val name: String, val exact: Boolean = true) : Match
        data class Many(val names: List<String>) : Match
        data object None : Match
    }

    /**
     * The phone contact(s) a spoken name means. Exact spellings first (the name itself, its
     * relation words in both scripts, a Devanagari -> Latin transliteration: "मम्मी" / "Mom" ->
     * "Mummy"), then the safety port's fuzzy matcher (token_set_ratio >= 80: "Rahul" -> "Rahul Sharma").
     */
    fun match(spoken: String, book: List<String>): Match {
        val names = book.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (names.isEmpty() || spoken.isBlank()) return Match.None
        val spellings = Names.spellings(spoken).map { it.lowercase() }
        val exact = names.filter { it.lowercase() in spellings }
        if (exact.isNotEmpty()) return oneOrMany(exact, exact = true)
        val ctx = CommandContext(command = "", names = spellings)
        return oneOrMany(names.filter { payeeMatches(ctx, it) }, exact = false)
    }

    private fun oneOrMany(names: List<String>, exact: Boolean): Match {
        val d = names.distinct()
        return when {
            d.isEmpty() -> Match.None
            d.size == 1 -> Match.One(d.first(), exact)
            else -> Match.Many(d.take(4))
        }
    }

    /**
     * What to search for in WhatsApp: the saved name, the spoken name, then its other spellings.
     * [savedFirst] false (the saved name only matched fuzzily, e.g. "Rahul" -> "Rahul Sharma"):
     * the spoken name goes first, so every Rahul in WhatsApp shows up and the user is asked.
     */
    fun queries(spoken: String, saved: String? = null, savedFirst: Boolean = true): List<String> {
        val all = buildList {
            if (savedFirst) saved?.let(::add)
            add(spoken)
            if (!savedFirst) saved?.let(::add)
            // Latin spellings before Devanagari ones (most chats are saved in Latin letters).
            val other = Names.spellings(spoken)
            addAll(other.filter { s -> s.none { it in 'ऀ'..'ॿ' } })
            addAll(other.filter { s -> s.any { it in 'ऀ'..'ॿ' } })
        }
        return all.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(MAX_QUERIES)
    }

    /**
     * What to search for the user's own chat. WhatsApp lists it as "<your name> (You)"; Saathi
     * doesn't know the user's name, so it looks for that mark on the chat list, then searches "You".
     */
    val SELF_QUERIES = listOf("You")

    /** [book]: display names from the phone's contacts (empty when not allowed / not asked). */
    fun of(spoken: String, book: List<String> = emptyList()): Recipient {
        if (spoken == Names.SELF) return Recipient(Names.SELF, null, inPhone = false, queries = SELF_QUERIES)
        val m = match(spoken, book)
        val one = m as? Match.One
        val exact = one?.exact != false
        return Recipient(spoken.trim(), one?.name, m != Match.None, queries(spoken, one?.name, savedFirst = exact), savedExact = exact)
    }
}

/**
 * WhatsApp's search results. Newer versions group them under headers ("Chats", "Contacts",
 * "Messages" …; Hindi too); older ones are one plain list. Name rows are the same resource ids
 * as the chat list ([WhatsApp.NAME_RIDS]). Rows under "Messages" are message hits, not people:
 * never picked. A header is a plain (not clickable, not editable) text; tabs and chips are clickable.
 */
object ContactSearch {
    enum class Section { NONE, CHATS, GROUPS, CONTACTS, OTHER, MESSAGES }

    data class Row(val element: Element, val section: Section)

    private val HEADERS: Map<String, Section> = mapOf(
        "chats" to Section.CHATS, "chat" to Section.CHATS, "चैट" to Section.CHATS, "चैट्स" to Section.CHATS,
        "contacts" to Section.CONTACTS, "other contacts" to Section.CONTACTS, "contacts on whatsapp" to Section.CONTACTS,
        "संपर्क" to Section.CONTACTS, "कॉन्टैक्ट" to Section.CONTACTS, "कॉन्टैक्ट्स" to Section.CONTACTS, "अन्य संपर्क" to Section.CONTACTS,
        "groups" to Section.GROUPS, "groups in common" to Section.GROUPS, "ग्रुप" to Section.GROUPS, "ग्रुप्स" to Section.GROUPS,
        "messages" to Section.MESSAGES, "मैसेज" to Section.MESSAGES, "मैसेजेस" to Section.MESSAGES, "संदेश" to Section.MESSAGES,
    )

    fun headerOf(e: Element): Section? {
        if (e.editable || e.clickable || e.rid in WhatsApp.NAME_RIDS) return null
        return HEADERS[e.text.trim().lowercase()]
    }

    /** Name rows, each tagged with the nearest header above it (by position, then order). */
    fun rows(screen: Screen): List<Row> {
        val headers = screen.elements.mapNotNull { e -> headerOf(e)?.let { e to it } }
        return WhatsApp.nameRows(screen).map { row ->
            val above = headers.filter { (h, _) -> h.bounds.y1 < row.bounds.y1 || (h.bounds.y1 == row.bounds.y1 && h.index < row.index) }
            val section = above.maxWithOrNull(compareBy<Pair<Element, Section>> { it.first.bounds.y1 }.thenBy { it.first.index })?.second
            Row(row, section ?: Section.NONE)
        }
    }

    sealed interface Pick {
        data class One(val element: Element) : Pick

        /** Several different people / groups match: the user picks (names verbatim on screen). */
        data class Many(val names: List<String>) : Pick

        /** Two rows with exactly the same name in the same list: can't be told apart by name. */
        data class Same(val name: String) : Pick

        data object None : Pick
    }

    /**
     * Who on this screen is the person the user named. [chosen]: the exact name the user already
     * picked (only that row counts). [exactOnly]: the chat list before searching, where only an
     * exact name / spelling is taken (a partial match there could hide a contact further down).
     * Otherwise: the spoken spelling exactly, then any exact spelling ([ctx] names), then fuzzy.
     */
    fun pick(screen: Screen, spoken: String, ctx: CommandContext, chosen: String? = null, exactOnly: Boolean = false): Pick {
        val rows = rows(screen).filter { it.section != Section.MESSAGES }.sortedBy { it.section.ordinal }
        if (chosen != null) return rows.firstOrNull { it.element.text == chosen }?.let { Pick.One(it.element) } ?: Pick.None
        // "Message myself": only the chat WhatsApp marks as the user's own ("Aaditya Goel (You)").
        if (spoken == Names.SELF) return of(rows.filter { WhatsApp.isSelfChat(it.element.text) }) ?: Pick.None
        // The name without WhatsApp's own-chat mark: "Aaditya Goel (You)" is "Aaditya Goel".
        fun name(r: Row) = WhatsApp.personName(r.element.text).lowercase()
        val s = spoken.trim().lowercase()
        of(rows.filter { name(it) == s })?.let { return it }
        val spellings = ctx.names.map { it.trim().lowercase() }.toSet()
        of(rows.filter { name(it) in spellings || it.element.text.trim().lowercase() in spellings })?.let { return it }
        if (exactOnly) return Pick.None
        return of(rows.filter { payeeMatches(ctx, it.element.text) || payeeMatches(ctx, WhatsApp.personName(it.element.text)) }) ?: Pick.None
    }

    private fun of(list: List<Row>): Pick? {
        if (list.isEmpty()) return null
        val names = list.map { it.element.text }.distinct()
        if (names.size > 1) return Pick.Many(names.take(4))
        // The same name twice in one list = two different chats / contacts with one name.
        val twice = list.groupBy { it.section }.values.any { it.size > 1 }
        return if (twice) Pick.Same(names.first()) else Pick.One(list.first().element)
    }
}

/** Asked when several people match: [options] are verbatim on WhatsApp's screen. */
data class ContactChoiceRequest(val spoken: String, val options: List<String>)

/**
 * The spoken answer to "Which Rahul: Rahul Sharma or Rahul Verma?". Defaults to the safe side:
 * no / cancel stops; an unclear answer (or silence) is asked once more, then stops. Picking a
 * person never sends anything: the message is still read back and needs a clear yes.
 *
 * Heard only through the shared [VoiceAnswer] pipeline. Accepted: a position ("the first one",
 * "doosra", "दूसरा"; ordinals are never in a sheet text), or a short reply naming exactly one
 * option ("Sharma", "Rahul Verma", "शर्मा") said on its own, after the quiet gap. The names are
 * in Saathi's question, so a name can't be told from an echo by its words alone.
 */
class ContactChoiceDialog(private val options: List<String>, maxUnclear: Int = 2) {
    sealed interface Next {
        data class Pick(val name: String) : Next
        data object Cancel : Next
        data object AskAgain : Next
    }

    private val answers = VoiceAnswer(maxUnclear)

    val unclear: Int get() = answers.unclear

    /** Why the last reply was (not) taken, for the log. */
    val lastWhy: VoiceAnswer.Why get() = answers.lastWhy

    private val rules = object : AnswerRules<String> {
        override val vocabulary: Set<String> = options.flatMap { words(it) }.toSet() + FILLERS + NO_WORDS + Ordinals.decisive
        override val decisive: Set<String> = Ordinals.decisive
        override fun read(reply: String): Reading<String> = reading(reply, options)
    }

    /** [echoSources]: Saathi's recent prompts and every text of the sheet (the options are added here). */
    fun hear(heard: Heard, echoSources: List<String>): Next = when (val r = answers.hear(heard, echoSources + options, rules)) {
        is VoiceAnswer.Result.Act -> Next.Pick(r.value)
        VoiceAnswer.Result.Safe -> Next.Cancel
        VoiceAnswer.Result.AskAgain -> Next.AskAgain
    }

    /** [hear] for a transcript with no timing (tests; Saathi hadn't spoken). */
    fun hear(reply: String, echoSources: List<String> = emptyList()): Next = hear(Heard(reply), echoSources)

    fun silence(): Next = if (answers.silence() == VoiceAnswer.Result.Safe) Next.Cancel else Next.AskAgain

    companion object {
        /** Returned by [classify] for a no / cancel. */
        const val CANCEL = "\u0000cancel"

        const val MAX_WORDS = 5

        private val NO_WORDS: Set<String> = setOf(
            "no", "nope", "cancel", "stop", "none", "neither", "nahi", "nahin", "nai", "mat", "ruko", "rehne",
            "नहीं", "नही", "मत", "रुको", "रुकिए", "कैंसल", "रहने",
        ).flatMap { EchoGuard.tokens(it) }.toSet()

        private val FILLERS: Set<String> = setOf(
            "the", "one", "a", "to", "please", "ji", "jee", "wala", "wali", "wale", "vala", "vali", "ko", "is", "it", "its",
            "that", "this", "person", "contact", "chat", "message", "my", "mean", "i", "वाला", "वाली", "वाले", "को", "जी",
        ).flatMap { EchoGuard.tokens(it) }.toSet()

        /** Lowercase words; Devanagari ones also in Latin letters (so "शर्मा" matches "Sharma"). */
        private fun words(text: String): List<String> = EchoGuard.tokens(text)

        private fun latin(word: String): String = if (word.any { it in 'ऀ'..'ॿ' }) Names.transliterate(word) else word

        private fun same(a: String, b: String): Boolean {
            if (a == b) return true
            val la = latin(a)
            val lb = latin(b)
            if (la == lb) return true
            // Small recogniser slips in longer words ("sharmaa", "verna"): the safety port's matcher.
            return la.length >= 4 && lb.length >= 4 && payeeMatches(CommandContext(command = "", names = listOf(la)), lb)
        }

        /** How the reply picks: [Reading.Safe] (cancel), an option (by position or by name), or unclear. */
        internal fun reading(reply: String, options: List<String>): Reading<String> {
            val w = words(reply)
            if (w.isEmpty() || options.isEmpty()) return Reading.Unclear
            if (w.any { it in NO_WORDS }) return Reading.Safe
            if (w.size > MAX_WORDS) return Reading.Unclear
            // Whole name, exactly.
            options.firstOrNull { it.trim().lowercase() == reply.trim().lowercase() }?.let { return Reading.Act(it, byName = true) }
            // A bare number said alone is a position ("2").
            w.singleOrNull()?.let { Ordinals.digit(it, options.size) }?.let { return Reading.Act(options[it], byName = false) }
            val ordinals = w.mapNotNull { Ordinals.of(it) }.distinct()
            val content = w.filter { it !in FILLERS && Ordinals.of(it) == null }
            if (ordinals.size == 1 && content.isEmpty()) {
                val i = ordinals.first().let { if (it == Ordinals.LAST) options.size - 1 else it }
                return options.getOrNull(i)?.let { Reading.Act(it, byName = false) } ?: Reading.Unclear
            }
            if (ordinals.isNotEmpty() || content.isEmpty()) return Reading.Unclear
            val hits = options.filter { opt ->
                val ow = words(opt)
                content.all { c -> ow.any { same(c, it) } }
            }
            return hits.singleOrNull()?.let { Reading.Act(it, byName = true) } ?: Reading.Unclear
        }

        /** The option [reply] names, [CANCEL], or null when unclear (no timing / echo checks). */
        fun classify(reply: String, options: List<String>): String? = when (val r = reading(reply, options)) {
            Reading.Safe -> CANCEL
            is Reading.Act -> r.value
            Reading.Unclear -> null
        }
    }
}
