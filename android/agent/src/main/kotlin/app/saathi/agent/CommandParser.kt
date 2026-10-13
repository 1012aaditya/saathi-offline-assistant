package app.saathi.agent

import app.saathi.safety.Apps

/**
 * Keyword command parser: English + Hindi (Devanagari) + Hinglish -> [Command]. Deterministic,
 * offline, no model. Anything it doesn't recognise becomes [Command.Unsupported] so Saathi can
 * say honestly that it can't do that offline yet, instead of guessing.
 *
 * Patterns follow the prototype (saathi/whatsapp.py UNREAD_QUERY, saathi/agent.py summary regex,
 * saathi/session.py contact_in + controls).
 */
object CommandParser {
    private val I = setOf(RegexOption.IGNORE_CASE)

    // -- small words --------------------------------------------------------------------------

    /** Dropped from the edges of a captured name ("WhatsApp par Mummy" -> "Mummy"). */
    private val FILLER = setOf(
        "whatsapp", "व्हाट्सएप", "par", "pe", "per", "mein", "please", "zara", "jara", "ab", "abhi",
        "पर", "पे", "में", "ज़रा", "जरा", "अभी", "the", "my", "from", "to", "read", "check", "play", "show", "get",
        "tell", "me", "what", "is", "was", "on", "hi", "hey", "saathi", "sathi", "साथी", "and", "aur", "और",
    )

    /** Captures that are not people (session.py _NOT_NAMES and friends). */
    private val NOT_NAMES = setOf(
        "whatsapp", "blinkit", "screen", "message", "messages", "msg", "aur", "us", "is", "kis", "kiske",
        "किस", "उस", "इस", "what", "that", "it", "there", "who", "today", "new", "last", "sab", "sabhi", "सब",
        "koi", "कोई", "mera", "meri", "mere", "मेरा", "मेरी", "मेरे", "my", "me", "i", "you", "app", "her", "him",
        "them", "everyone", "anyone", "somebody", "someone", "unread",
        // Hindi verbs that can land where a name is expected ("message padho")
        "padho", "sunao", "dekho", "kholo", "bhejo", "bolo", "likho", "batao", "पढ़ो", "सुनाओ", "देखो", "खोलो",
    )

    // -- control phrases ------------------------------------------------------------------------

    private val STOP = Regex(
        "^(stop|cancel|ruko|ruk jao|bas|bas karo|band karo|never mind|nevermind|nothing|रुको|रुकिए|रुक जाओ|बस|बंद करो|कैंसल)$",
        I,
    )
    private val HELP = Regex(
        "^(help|help me|madad|madad karo|मदद|मदद करो)$|what can you do|kya kar sakte|क्या कर सकते",
        I,
    )

    // F4 "Screen pe kya hai?" (saathi/agent.py _SUMMARY_REGEX + a few more ways to say it).
    private val SCREEN = Regex(
        "screen (pe|par|per) kya|kya (dikh|likha)|what('?s| is) on (the |my |this )?screen|" +
            "describe (the |my |this )?screen|read (the |my |this )?screen|what am i looking at|" +
            "screen (padho|batao|sunao)|स्क्रीन (पे|पर) क्या|स्क्रीन (पढ़ो|बताओ|सुनाओ)|क्या दिख|क्या लिखा",
        I,
    )

    // -- reply ----------------------------------------------------------------------------------

    private val EN_REPLY_TO = Regex("^(?:please\\s+)?(?:reply|respond|answer|write)\\s+(?:back\\s+)?to\\s+(.+)$", I)
    private val EN_TELL = Regex(
        "^(?:please\\s+)?(?:tell|text|message|msg)\\s+(?!me\\b)(?!from\\b)(?!to\\b)(.+)$",
        I,
    )
    private val EN_SEND_TO = Regex(
        "^(?:please\\s+)?send\\s+(?:a\\s+)?(?:(?:whatsapp\\s+)?(?:message|msg|text|reply)\\s+)?to\\s+(.+)$",
        I,
    )
    private val EN_SEND_NAME_A_MESSAGE = Regex(
        "^(?:please\\s+)?send\\s+(?!a\\s)(.+?)\\s+(?:a\\s+)?(?:whatsapp\\s+)?(?:message|msg|text)(?:\\s+(.*))?$",
        I,
    )
    private const val HI_VERBS =
        "kar do|karo|bol do|bolo|likh do|likho|bhej do|bhejo|bata do|batao|keh do|kah do|kaho|" +
            "कर दो|करो|बोल दो|बोलो|लिख दो|लिखो|भेज दो|भेजो|बता दो|बताओ|कह दो|कहो"
    private const val HI_NOUNS = "reply|jawab|jawaab|message|msg|sandesh|मैसेज|मेसेज|संदेश|जवाब|रिप्लाई"
    private val HI_VERB_FIRST = Regex(
        "^(.+?)\\s+(?:ko|को)\\s+(?:(?:$HI_NOUNS)\\s+)?(?:$HI_VERBS)(?:\\s*[:,]\\s*|\\s+|$)(.*)$",
        I,
    )
    private val HI_VERB_LAST = Regex(
        "^(.+?)\\s+(?:ko|को)\\s+(.+?)\\s+(?:bol do|bolo|likh do|likho|bhej do|bhejo|keh do|kah do|kaho|bata do|batao|" +
            "बोल दो|बोलो|लिख दो|लिखो|भेज दो|भेजो|कह दो|कहो|बता दो|बताओ)$",
        I,
    )
    private val NO_NAME_REPLY = Regex(
        "^(?:reply|jawab|jawaab|जवाब|रिप्लाई)(?:\\s+(?:karo|kar do|do|दो|करो|कर दो|back|saying|with))?\\s*[:,]?\\s*(.*)$",
        I,
    )

    // -- read the last message ------------------------------------------------------------------

    private const val MSG = "message|messages|msg|msgs|text|texts"
    private val EN_FROM = Regex("(?:$MSG)\\s+(?:from|by)\\s+(.+)$", I)
    private val EN_POSSESSIVE = Regex(
        "(?<![\\p{L}\\p{N}'])((?:[\\p{L}\\p{N}.]+\\s+)?[\\p{L}\\p{N}.]+)'s\\s+(?:(?:last|latest|recent|newest|new)\\s+)?(?:whatsapp\\s+)?(?:$MSG|reply)",
        I,
    )
    private val EN_WHAT_DID = Regex("what did\\s+(.+?)\\s+(?:say|send|write|text|message)", I)
    private const val HI_LAST = "last|latest|aakhri|akhri|aakhiri|pichla|pichle|pichhla|pichhle|naya|naye|new|" +
        "आखिरी|आख़िरी|पिछला|पिछले|नया|नए|लास्ट"
    private val HI_KA = Regex(
        "^(.+?)\\s+(?:ka|ki|ke|का|की|के)\\s+(?:(?:$HI_LAST)\\s+)?(?:message|messages|msg|sandesh|मैसेज|मेसेज|संदेश)",
        I,
    )
    private val HI_NE = Regex("^(.+?)\\s+(?:ne|ने)\\s+(?:kya|क्या)\\s+(?:bheja|likha|bola|kaha|भेजा|लिखा|बोला|कहा)", I)
    private val LAST_NO_NAME = Regex(
        "(?:read|padho|पढ़ो|सुनाओ|sunao).*(?:last|latest|aakhri|akhri|आखिरी)\\s+(?:message|msg|मैसेज|संदेश)|" +
            "(?:last|latest|aakhri|akhri|आखिरी)\\s+(?:message|msg|मैसेज|संदेश)\\s*(?:padho|sunao|पढ़ो|सुनाओ|read)?$",
        I,
    )

    // -- unread ---------------------------------------------------------------------------------

    // saathi/whatsapp.py UNREAD_QUERY + English ways to ask.
    private val UNREAD = Regex(
        "kis(ke|ka|ki|se)?\\b.*\\b(message|msg|sandesh)|unread|naye? (message|sandesh)|" +
            "किस(के|का|की|से).*(संदेश|मैसेज)|नए (संदेश|मैसेज)|who (has |have )?(messaged|texted|sent)|" +
            "\\bnew messages?\\b|\\bany (new )?messages?\\b|(read|check|play) (out )?(my |all )?(new )?messages|" +
            "^(my )?messages$|messages? (padho|dekho|sunao)|(मैसेज|संदेश) (पढ़ो|सुनाओ|दिखाओ)",
        I,
    )

    // -- camera / photo description (described on the phone; "open camera" / "open photos" stay app opening) --

    private const val PHOTO_NOUN = "photo|photos|picture|pic|pics|image|snap|tasveer|tasvir|foto|फ़ोटो|फोटो|तस्वीर|तसवीर|पिक्चर|इमेज"
    private val LAST_PHOTO = Regex(
        "(?:last|latest|newest|most recent|recent|previous|aakhri|akhri|aakhiri|pichli|pichhli|आखिरी|आख़िरी|पिछली|लास्ट|सबसे नई)\\s+(?:$PHOTO_NOUN)|" +
            "(?:photo|picture|pic|image)\\s+(?:that\\s+)?i\\s+(?:just\\s+)?took|" +
            "(?:describe|what'?s in|what is in)\\s+my\\s+(?:photo|picture|pic|image)|" +
            "(?:meri|मेरी)\\s+(?:$PHOTO_NOUN)\\s+(?:mein|me|main|में)\\s+(?:kya|क्या)",
        I,
    )
    private val LIVE_PHOTO = Regex(
        "\\b(?:take|click|snap|capture)\\s+(?:a\\s+|one\\s+|my\\s+|another\\s+|the\\s+)?(?:photo|picture|pic|snap|image)|" +
            "\\bwhat(?:'s|s| is| are)\\s+(?:there\\s+)?(?:in front|ahead|around)|" +
            "\\bdescribe\\s+(?:this|that|it|what'?s in front|what is in front|what you see|my surroundings|the surroundings|" +
            "surroundings|the scene|the room|around me)(?!\\s+(?:screen|photo|picture|message|chat))|" +
            "\\bwhat (?:do|can) you see|" +
            "(?:saamne|samne|saamane|aage|aas ?paas)\\s+(?:kya|kaun|kon)|(?:सामने|आगे|आस ?पास)\\s+(?:क्या|कौन)|" +
            "(?:camera|कैमरा|कैमरे)\\s+(?:se|से)\\s+(?:dekh|bata|देख|बता)|" +
            "(?:$PHOTO_NOUN)\\s+(?:khincho|kheencho|khicho|kheecho|khich|kheech|khinch|khinchiye|khichiye|kheech do|khinch do|" +
            "lo|le lo|lijiye|le lijiye|click karo|click kar do|खींचो|खीचो|खींच दो|खिंचो|खींचिए|लो|ले लो|लीजिए|क्लिक करो)(?=\\s|$)",
        I,
    )

    // -- apps and Saathi's own screens ----------------------------------------------------------

    private val OPEN_VERB = Regex("\\b(open|launch|start|go to)\\b|kholo|khol do|chalu karo|खोलो|खोल दो|चालू करो|ओपन", I)
    private val PHONE_SETTINGS = Regex("(phone|android|system|device) settings|settings app|फ़ोन (की )?सेटिंग|फोन (की )?सेटिंग", I)

    private val NAV: List<Pair<Destination, Regex>> = listOf(
        Destination.HOME to Regex("^(go |take me )?home$|^(go )?back home$|^ghar$|^होम$|home (pe|par) (jao|chalo)", I),
        Destination.WALK to Regex("\\bwalk\\b|^chalo$|चलो|वॉक", I),
        Destination.ACTIVITY to Regex("\\bactivity\\b|\\bhistory\\b|gatividhi|गतिविधि|इतिहास", I),
        Destination.SETTINGS to Regex("\\bsettings?\\b|सेटिंग", I),
        Destination.SAFETY to Regex("\\bsafety\\b|suraksha|सुरक्षा", I),
    )

    // ---------------------------------------------------------------------------------------------

    /**
     * @param knownNames contact names the user saved in Saathi (e.g. "Rahul Sharma"), preferred
     *   when a spoken name could be one or several words.
     */
    fun parse(text: String, session: Session? = null, knownNames: List<String> = emptyList()): Command {
        val raw = text
        val t = normalize(text)
        if (t.isEmpty()) return Command.Unsupported(raw)
        val lower = t.lowercase()

        session?.pendingReplyTo?.let { to ->
            session.pendingReplyTo = null
            if (STOP.matches(lower)) return Command.Stop(raw)
            return Command.Reply(to, cleanText(t), raw)
        }

        if (STOP.matches(lower)) return Command.Stop(raw)
        if (wordCount(t) <= 6 && HELP.containsMatchIn(lower)) return Command.Help(raw)
        photo(t)?.let { return Command.Photo(it, raw) } // before SCREEN: "saamne kya dikh raha hai" is the camera
        if (SCREEN.containsMatchIn(t)) return Command.ScreenSummary(raw)

        reply(t, session, knownNames)?.let { return it.copy(utterance = raw) }
        readLast(t, session, knownNames)?.let { return it.copy(utterance = raw) }
        OrderParser.parse(t)?.let { return it } // Blinkit: order / add to cart / cart / place order (OrderCommands.kt)
        if (UNREAD.containsMatchIn(t)) return Command.UnreadSummary(raw)
        openApp(t)?.let { return it.copy(utterance = raw) }
        launchApp(t)?.let { return it.copy(utterance = raw) }
        if (wordCount(t) <= 5) {
            NAV.firstOrNull { (_, rx) -> rx.containsMatchIn(t) }?.let { return Command.Navigate(it.first, raw) }
        }
        return Command.Unsupported(raw)
    }

    private fun reply(t: String, session: Session?, known: List<String>): Command.Reply? {
        EN_REPLY_TO.matchEntire(t)?.let { m -> return splitNameText(m.groupValues[1], known) }
        EN_SEND_TO.matchEntire(t)?.let { m -> return splitNameText(m.groupValues[1], known) }
        EN_SEND_NAME_A_MESSAGE.matchEntire(t)?.let { m ->
            val name = cleanName(m.groupValues[1], known) ?: return null
            return Command.Reply(name, cleanText(m.groupValues[2]), "")
        }
        EN_TELL.matchEntire(t)?.let { m -> return splitNameText(m.groupValues[1], known) }
        HI_VERB_FIRST.matchEntire(t)?.let { m ->
            val name = cleanName(m.groupValues[1], known)
            if (name != null) return Command.Reply(name, cleanText(m.groupValues[2]), "")
        }
        HI_VERB_LAST.matchEntire(t)?.let { m ->
            val name = cleanName(m.groupValues[1], known)
            if (name != null) return Command.Reply(name, cleanText(m.groupValues[2]), "")
        }
        NO_NAME_REPLY.matchEntire(t)?.let { m ->
            return Command.Reply(session?.lastContact, cleanText(m.groupValues[1]), "")
        }
        return null
    }

    private fun readLast(t: String, session: Session?, known: List<String>): Command.ReadLast? {
        for (rx in listOf(EN_FROM, EN_WHAT_DID, EN_POSSESSIVE, HI_KA, HI_NE)) {
            val m = rx.find(t) ?: continue
            val name = cleanName(m.groupValues[1].replace(Regex("\\s+on whatsapp$", RegexOption.IGNORE_CASE), ""), known)
            if (name != null) return Command.ReadLast(name, "")
        }
        if (LAST_NO_NAME.containsMatchIn(t)) return Command.ReadLast(session?.lastContact, "")
        return null
    }

    /** "Describe my last photo" → LAST_PHOTO; "take a photo" / "what's in front of me" → CAMERA; else null. */
    private fun photo(t: String): PhotoSource? = when {
        LAST_PHOTO.containsMatchIn(t) -> PhotoSource.LAST_PHOTO
        LIVE_PHOTO.containsMatchIn(t) -> PhotoSource.CAMERA
        else -> null
    }

    private fun openApp(t: String): Command.OpenApp? {
        val lower = t.lowercase()
        if (PHONE_SETTINGS.containsMatchIn(lower)) {
            return Command.OpenApp("settings", Apps.APP_ALIASES.getValue("settings"), "")
        }
        val hasVerb = OPEN_VERB.containsMatchIn(lower)
        // Longest alias first: "google pay" before "gpay".
        val alias = Apps.APP_ALIASES.keys
            .filter { it != "settings" }
            .sortedByDescending { it.length }
            .firstOrNull { containsWord(lower, it) } ?: return null
        val bare = lower.trim() == alias
        if (!hasVerb && !bare) return null
        return Command.OpenApp(alias, Apps.APP_ALIASES.getValue(alias), "")
    }

    // -- any other installed app ("open camera", "Instagram kholo", "कैमरा खोलो") ------------------

    private val EN_OPEN_ANY = Regex("^(?:please\\s+)?(?:open|launch|start|run|go to)\\s+(?:up\\s+)?(.+)$", I)
    private val HI_OPEN_ANY = Regex(
        "^(.+?)\\s+(?:kholo|khol do|kholiye|khol dijiye|khol de|open karo|open kar do|open kijiye|chalu karo|chalu kar do|" +
            "chalao|chala do|खोलो|खोल दो|खोलिए|खोल दीजिए|खोल दे|ओपन करो|ओपन कर दो|ओपन कीजिए|चालू करो|चालू कर दो|चलाओ|चला दो)$",
        I,
    )
    private val APP_FILLER = setOf(
        "the", "my", "app", "apps", "application", "please", "up", "zara", "jara", "ज़रा", "जरा", "ab", "abhi", "अभी",
        "saathi", "sathi", "साथी", "ऐप", "एप", "एप्प", "ko", "को", "wala", "वाला", "mera", "मेरा",
    )

    /** Saathi's own screens and WhatsApp words: never an app to launch ("start walk mode", "open Rahul's chat"). */
    private val NOT_AN_APP = Regex(
        "\\b(walk|braille|home|activity|history|safety|settings?|help|chat|chats)\\b|'s\\b|" +
            "ब्रेल|वॉक|होम|गतिविधि|इतिहास|सुरक्षा|सेटिंग|मदद|चैट",
        I,
    )

    private fun launchApp(t: String): Command.LaunchApp? {
        val m = EN_OPEN_ANY.matchEntire(t) ?: HI_OPEN_ANY.matchEntire(t) ?: return null
        val words = m.groupValues[1].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        while (words.isNotEmpty() && words.first().lowercase() in APP_FILLER) words.removeAt(0)
        while (words.isNotEmpty() && words.last().lowercase() in APP_FILLER) words.removeAt(words.size - 1)
        if (words.isEmpty() || words.size > 4) return null
        val name = words.joinToString(" ").trim('"', '“', '”', ',', '.')
        if (name.isEmpty() || name.length > 40 || NOT_AN_APP.containsMatchIn(name)) return null
        return Command.LaunchApp(name, "")
    }

    // -- helpers --------------------------------------------------------------------------------

    /** "reply to Rahul Sharma: on my way" -> ("Rahul Sharma", "on my way"). */
    private fun splitNameText(rest0: String, known: List<String>): Command.Reply? {
        val rest = rest0.trim()
        if (rest.isEmpty()) return null
        val lower = rest.lowercase()
        for (k in known.sortedByDescending { it.length }) {
            val kl = k.lowercase().trim()
            if (kl.isEmpty() || !lower.startsWith(kl)) continue
            val after = rest.substring(kl.length)
            if (after.isEmpty() || !after[0].isLetterOrDigit()) {
                return Command.Reply(k.trim(), cleanText(after), "")
            }
        }
        val sep = Regex("^(.{1,40}?)\\s*(?::|,|\\s-\\s|\\s+(?:that|saying|ki|कि)\\s+)(.*)$", RegexOption.IGNORE_CASE).matchEntire(rest)
        if (sep != null && wordCount(sep.groupValues[1]) <= 3) {
            val name = cleanName(sep.groupValues[1], known) ?: return null
            return Command.Reply(name, cleanText(sep.groupValues[2]), "")
        }
        val words = rest.split(Regex("\\s+"))
        val name = cleanName(words.first(), known) ?: return null
        return Command.Reply(name, cleanText(words.drop(1).joinToString(" ")), "")
    }

    /** Strip filler words; reject non-names. Prefers a saved contact name if it ends the capture. */
    internal fun cleanName(raw: String, known: List<String>): String? {
        var s = raw.trim().trim(',', ':', '.', '"', '“', '”').replace(Regex("'s$"), "")
        s = s.replace(Regex("\\s+(?:a\\s+)?(?:whatsapp\\s+)?(?:message|msg|text|reply)$", RegexOption.IGNORE_CASE), "")
        val lower = s.lowercase()
        known.sortedByDescending { it.length }.firstOrNull { k ->
            val kl = k.lowercase().trim()
            kl.isNotEmpty() && (lower == kl || lower.endsWith(" $kl"))
        }?.let { return it.trim() }
        val words = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        while (words.isNotEmpty() && words.first().lowercase() in FILLER) words.removeAt(0)
        while (words.isNotEmpty() && words.last().lowercase() in FILLER) words.removeAt(words.size - 1)
        if (words.isEmpty() || words.size > 3) return null
        if (words.any { it.lowercase() in NOT_NAMES }) return null
        // "group" alone isn't a name, but group chats are ("Family Group ko bolo …").
        if (words.size == 1 && words[0].lowercase() in setOf("group", "groups", "ग्रुप")) return null
        return words.joinToString(" ")
    }

    /** Message text as said, minus the joining word ("that", "ki", ":"). Null when empty or too long. */
    private fun cleanText(raw: String): String? {
        var s = raw.trim()
        s = s.replace(Regex("^(?:a\\s+)?(?:whatsapp\\s+)?(?:message|msg|text)\\s+(?=(?:saying|that)\\b)", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("^(?:[:,\\-]\\s*|(?:that|saying|ki|कि|ke|के)\\s+)", RegexOption.IGNORE_CASE), "")
        s = s.trim().trim('"', '“', '”').trim()
        if (s.isEmpty()) return null
        if (s.codePointCount(0, s.length) > 500) return null // TypeText limit: never cut a message short
        return s
    }

    private fun normalize(text: String): String =
        text.replace('’', '\'').replace('‘', '\'')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', '?', '!', '।', ' ')

    private fun wordCount(s: String): Int = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    private fun containsWord(haystack: String, word: String): Boolean {
        var from = 0
        while (true) {
            val i = haystack.indexOf(word, from)
            if (i < 0) return false
            val before = haystack.getOrNull(i - 1)
            val after = haystack.getOrNull(i + word.length)
            if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return true
            from = i + 1
        }
    }
}
