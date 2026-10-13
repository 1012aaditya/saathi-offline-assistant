package app.saathi.agent

import app.saathi.safety.Verdict

/**
 * Saathi's own words, one language at a time (the app follows the phone's language). Hindi is in
 * Devanagari (TTS reads it best), short. Anything quoted from the screen is passed in verbatim and
 * never translated or paraphrased (hard rule 4).
 */
class Texts(val lang: Lang) {
    private fun s(en: String, hi: String): String = if (lang == Lang.HI) hi else en

    // -- narration -----------------------------------------------------------------------------
    fun opening(app: String) = s("Opening $app", "$app खोल रहा हूँ")
    val backToChats get() = s("Going back to the chat list", "चैट सूची पर वापस जा रहा हूँ")
    val leavingOtherChat get() = s("This is a different chat. Going back", "यह दूसरी चैट है, वापस जा रहा हूँ")
    fun openingChat(name: String) = s("Opening $name's chat", "$name की चैट खोल रहा हूँ")
    fun searching(name: String) = s("Searching for $name", "$name को खोज रहा हूँ")
    val jumpingToLatest get() = s("Going to the newest messages", "नए संदेशों पर जा रहा हूँ")
    val lookingFurther get() = s("Looking a little further up", "थोड़ा ऊपर देख रहा हूँ")
    val typing get() = s("Typing your message", "आपका संदेश लिख रहा हूँ")
    val readyToSend get() = s("Message typed. I'll check with you before sending", "संदेश लिख दिया, भेजने से पहले पूछूँगा")
    val turningOnUnread get() = s("Showing only unread chats", "सिर्फ़ नए संदेश वाली चैट दिखा रहा हूँ")
    val turningOffUnread get() = s("Putting the chat list back as it was", "चैट सूची पहले जैसी कर रहा हूँ")
    val sending get() = s("Sending", "भेज रहा हूँ")
    val clearingDraft get() = s("Removing the typed message", "लिखा हुआ संदेश हटा रहा हूँ")
    val replacing get() = s("Putting your new message in place of the old text", "पुराने की जगह आपका नया संदेश लिख रहा हूँ")
    val adding get() = s("Adding your message after your text", "आपके लिखे के बाद आपका संदेश जोड़ रहा हूँ")
    val readyToSendExisting get() = s("I'll read it to you before sending", "भेजने से पहले पढ़कर सुनाऊँगा")

    // -- answers -------------------------------------------------------------------------------
    fun lastMessage(name: String, msg: String) =
        s("$name's last text message: \"$msg\"", "$name का आखिरी संदेश: \"$msg\"")
    fun lastMessageTitle(name: String) = s("$name's last message", "$name का आखिरी संदेश")
    fun noMessageFromThem(name: String) = s(
        "I opened \"$name\" but couldn't find a text message from them near the end of the chat.",
        "\"$name\" की चैट खोली, पर उनका कोई हाल का लिखा संदेश नहीं मिला।",
    )
    fun sent(name: String) = s("Sent to \"$name\".", "\"$name\" को भेज दिया।")
    fun sentTitle(name: String) = s("Message to $name", "$name को संदेश")
    fun opened(app: String) = s("$app is open.", "$app खुल गया।")

    fun unreadSummary(rows: List<Pair<String, Int>>, max: Int = 6): String {
        if (rows.isEmpty()) return s("No new messages right now.", "अभी कोई नया संदेश नहीं है।")
        val more = rows.size - max
        return if (lang == Lang.HI) {
            val spoken = rows.take(max).joinToString(", ") { (n, c) -> "$n के $c" }
            "${rows.size} चैट में नए संदेश हैं: $spoken${if (more > 0) ", और $more चैट" else ""}।"
        } else {
            val spoken = rows.take(max).joinToString(", ") { (n, c) -> "$n, $c" }
            "${rows.size} ${if (rows.size == 1) "chat has" else "chats have"} new messages: $spoken${if (more > 0) ", and $more more" else ""}."
        }
    }
    val unreadTitle get() = s("New messages", "नए संदेश")

    fun screenSummary(app: String, texts: List<String>, more: Int): String {
        if (texts.isEmpty()) return s("$app is open, but I can't find any text on this screen.", "$app खुला है, पर स्क्रीन पर कोई लिखावट नहीं मिली।")
        val list = texts.joinToString("; ")
        return s(
            "This is $app. On screen: $list${if (more > 0) ", and $more more" else ""}.",
            "यह $app है। स्क्रीन पर: $list${if (more > 0) ", और $more चीज़ें" else ""}।",
        )
    }
    fun screenTitle(app: String) = s("On screen in $app", "$app की स्क्रीन पर")
    val noCapturedScreen get() = s(
        "I can only describe another app's screen. Open that app, then hold Volume-down and ask again.",
        "मैं दूसरे ऐप की स्क्रीन बता सकता हूँ। वह ऐप खोलिए, फिर Volume-down दबाकर पूछिए।",
    )
    val pinScreenSummary get() = s(
        "This is a PIN or password screen. I won't read it. Please enter it yourself.",
        "यह पिन या पासवर्ड की स्क्रीन है। मैं इसे नहीं पढ़ूँगा, आप खुद डालिए।",
    )

    // -- asks ----------------------------------------------------------------------------------
    val whoseMessage get() = s("Whose message should I read? For example, say “Read Mom's last message”.", "किसका संदेश पढ़ूँ? जैसे, “मम्मी का आखिरी संदेश पढ़ो”।")
    val replyToWhom get() = s("Who should I reply to? For example, say “Reply to Mom, I'll be home by 7”.", "किसे जवाब भेजूँ? जैसे, “मम्मी को बोलो मैं सात बजे आऊँगा”।")
    fun whatToSay(name: String) = s("What should I say to $name?", "$name को क्या लिखूँ?")
    fun whichOne(names: List<String>) = s(
        "I found more than one: ${names.joinToString(", ") { "\"$it\"" }}. Please say the full name.",
        "एक से ज़्यादा मिले: ${names.joinToString(", ") { "\"$it\"" }}। पूरा नाम बताइए।",
    )

    // -- failures --------------------------------------------------------------------------------
    fun notFound(name: String) = s("I couldn't find a chat called $name in WhatsApp.", "WhatsApp में $name नाम की चैट नहीं मिली।")
    val cantTypeHere get() = s("I can't find the message box in this chat, so I didn't type anything.", "इस चैट में संदेश लिखने की जगह नहीं मिली, मैंने कुछ नहीं लिखा।")
    val draftExists get() = s("This chat already has an unsent draft. I won't overwrite it.", "इस चैट में पहले से कुछ लिखा है, मैं उसे नहीं मिटाऊँगा।")
    val typedMismatch get() = s("The typed text doesn't match what you said, so I won't send it.", "लिखा हुआ संदेश आपकी बात से अलग है, मैं नहीं भेजूँगा।")
    val noSendButton get() = s("I can't find WhatsApp's send button, so nothing was sent.", "भेजने का बटन नहीं मिला, कुछ नहीं भेजा।")
    val notSureSent get() = s("I pressed send but I'm not sure it went. Please check the chat.", "भेजें दबाया, पर पक्का नहीं कि गया। चैट देख लीजिए।")
    val stuck get() = s("This step isn't working. I've stopped.", "यह स्टेप नहीं हो पा रहा। मैं रुक रहा हूँ।")
    val tooManySteps get() = s("That took too many steps. I've stopped.", "बहुत स्टेप हो गए, मैं रुक रहा हूँ।")
    val unsafe get() = s("I can't do this safely, so I've stopped.", "यह काम सुरक्षित तरीके से नहीं हो पा रहा, मैं रुक रहा हूँ।")
    val cantRead get() = s("I can't read the screen right now.", "अभी स्क्रीन पढ़ नहीं पा रहा।")
    val notExact get() = s("I couldn't read it exactly, so I won't guess.", "ठीक से पढ़ नहीं पाया, अंदाज़ा नहीं लगाऊँगा।")
    val readOnly get() = s("I only read this screen; I won't change anything.", "मैं सिर्फ़ पढ़ता हूँ, कुछ नहीं बदलूँगा।")
    val ownScreen get() = s("I won't tap on Saathi's own screen.", "मैं साथी की अपनी स्क्रीन पर नहीं दबाऊँगा।")
    val couldNotOpen get() = s("I couldn't open that app.", "वह ऐप नहीं खुल पाया।")
    val notSentChanged get() = s("The screen changed after I read it to you, so I didn't send.", "पढ़कर सुनाने के बाद स्क्रीन बदल गई, इसलिए नहीं भेजा।")
    val draftRemoved get() = s("I removed the message I had typed.", "मैंने लिखा हुआ संदेश हटा दिया।")
    val stopped get() = s("Stopped. You can carry on yourself.", "रुक गया। आप आगे खुद कर सकते हैं।")
    val stoppedServiceLost get() = s(
        "Stopped: Saathi's Accessibility service was switched off or restarted.",
        "रुक गया: साथी की Accessibility सेवा बंद हुई या फिर से शुरू हुई।",
    )
    fun draftLeft(chat: String) = s(
        "I couldn't remove the text I typed for $chat; it was NOT sent. I'll try again when I can.",
        "$chat के लिए लिखा संदेश हटा नहीं पाया; वह भेजा नहीं गया। मैं फिर कोशिश करूँगा।",
    )
    fun draftLeftGiveUp(chat: String) = s(
        "I still couldn't remove the text I typed for $chat. It was NOT sent. Please check that chat's message box.",
        "$chat के लिए लिखा संदेश अब भी नहीं हटा। वह भेजा नहीं गया। उस चैट का संदेश बॉक्स देख लीजिए।",
    )
    fun draftChanged(chat: String) = s(
        "The message box for $chat has other text now, so I left it. Nothing was sent.",
        "$chat के संदेश बॉक्स में अब कुछ और लिखा है, मैंने नहीं छुआ। कुछ नहीं भेजा।",
    )
    fun clearingEarlierDraft(chat: String) = s(
        "First, removing the text I typed earlier for $chat",
        "पहले $chat के लिए पहले लिखा संदेश हटा रहा हूँ",
    )
    fun earlierDraftRemoved(chat: String) = s(
        "I removed the text I had typed earlier for $chat. It was not sent.",
        "$chat के लिए पहले लिखा संदेश हटा दिया। वह भेजा नहीं गया था।",
    )
    fun cancelled(cleared: Boolean) = s(
        if (cleared) "Cancelled. Nothing was sent, and I removed the typed message." else "Cancelled. Nothing was sent.",
        if (cleared) "कैंसल कर दिया। कुछ नहीं भेजा, लिखा हुआ संदेश हटा दिया।" else "कैंसल कर दिया। कुछ नहीं भेजा।",
    )

    // -- help / honest limits -----------------------------------------------------------------
    val helpTitle get() = s("What I can do", "मैं क्या कर सकता हूँ")
    val help get() = s(
        "Offline, I can read a contact's last WhatsApp message, tell you who has new messages, message anyone on WhatsApp " +
            "after you confirm, open apps on your phone (I ask the first time), and describe the screen of the app you were in. " +
            "Hold Volume-down in any app to talk to me.",
        "बिना इंटरनेट मैं WhatsApp पर किसी का आखिरी संदेश पढ़ सकता हूँ, बता सकता हूँ किसके नए संदेश हैं, आपकी पुष्टि के बाद किसी को भी संदेश भेज सकता हूँ, " +
            "फ़ोन के ऐप खोल सकता हूँ (पहली बार पूछकर), और जिस ऐप में आप थे उसकी स्क्रीन बता सकता हूँ। किसी भी ऐप में Volume-down दबाकर बोलिए।",
    )
    val unsupportedTitle get() = s("I can't do that offline yet", "यह अभी बिना इंटरनेट नहीं कर सकता")
    val unsupported get() = s(
        "I can't do that offline yet, so I didn't try. Say “Help” to hear what I can do.",
        "यह मैं अभी बिना इंटरनेट नहीं कर सकता, इसलिए कोशिश नहीं की। “मदद” बोलिए।",
    )
    fun appOff(app: String) = s(
        "$app is turned off for Saathi in Settings, so I didn't open it.",
        "Settings में $app साथी के लिए बंद है, इसलिए नहीं खोला।",
    )
    fun readOnlyApp(app: String) = s(
        "$app is set to Read only in Settings, so I won't send messages there.",
        "Settings में $app सिर्फ़ पढ़ने के लिए है, मैं संदेश नहीं भेजूँगा।",
    )
    val serviceOff get() = s(
        "Saathi's Accessibility service is off, so I can't use your apps yet.",
        "साथी की Accessibility सेवा बंद है, इसलिए अभी ऐप नहीं चला सकता।",
    )

    // -- the user's own unsent text in a chat ---------------------------------------------------
    val unsentTextHere get() = s("There's unsent text in this chat.", "इस चैट में कुछ बिना भेजा लिखा है।")

    /** Asked when a reply finds the user's own text in the box. [existing] is verbatim on screen. */
    fun draftQuestion(chat: String, existing: String, canChange: Boolean) = if (canChange) {
        s(
            "$chat's chat already has unsent text: \"$existing\". Keep it, replace it with your message, add your message after it, or send it as it is?",
            "$chat की चैट में पहले से लिखा है: \"$existing\"। रहने दूँ, बदल दूँ, जोड़ दूँ, या यही भेज दूँ?",
        )
    } else {
        s(
            "$chat's chat already has unsent text: \"$existing\". It has more than one line, so I can keep it or send it as it is. Which one?",
            "$chat की चैट में पहले से लिखा है: \"$existing\"। यह कई लाइन का है, इसलिए रहने दूँ या यही भेज दूँ?",
        )
    }
    val draftCantChange get() = s(
        "It has more than one line, so I can't put it back exactly. I can keep it or send it as it is.",
        "यह कई लाइन का है, मैं इसे ठीक वैसा वापस नहीं रख पाऊँगा। रहने दूँ या यही भेज दूँ।",
    )
    val draftPrivate get() = s(
        "There's unsent text in this chat that looks private. I didn't read it or change it, and nothing was sent. If you clear it yourself, I can send your message.",
        "इस चैट में कुछ बिना भेजा लिखा है जो निजी लगता है। मैंने उसे न पढ़ा, न बदला, कुछ नहीं भेजा। आप उसे हटा दें तो मैं आपका संदेश भेज सकता हूँ।",
    )
    val draftKept get() = s("I left your text as it was. Nothing was sent.", "आपका लिखा जैसा था वैसा छोड़ दिया। कुछ नहीं भेजा।")
    val draftChangedMeanwhile get() = s(
        "The text in the message box changed, so I left it as it is. Nothing was sent.",
        "संदेश बॉक्स में लिखा बदल गया, मैंने उसे वैसा ही छोड़ दिया। कुछ नहीं भेजा।",
    )
    val cancelledRestored get() = s(
        "Cancelled. Nothing was sent, and I put your earlier text back.",
        "कैंसल कर दिया। कुछ नहीं भेजा, आपका पहले का लिखा वापस रख दिया।",
    )
    val cancelledLeftAsIs get() = s(
        "Cancelled. Nothing was sent. Your text is still there as it was.",
        "कैंसल कर दिया। कुछ नहीं भेजा। आपका लिखा वैसा ही है।",
    )
    val originalRestored get() = s("I put your earlier text back.", "आपका पहले का लिखा वापस रख दिया।")
    fun earlierRestored(chat: String) = s(
        "I put your earlier text back in $chat's chat. Nothing was sent.",
        "$chat की चैट में आपका पहले का लिखा वापस रख दिया। कुछ नहीं भेजा।",
    )
    fun restoringEarlierDraft(chat: String) = s(
        "First, putting your earlier text back in $chat's chat",
        "पहले $chat की चैट में आपका पहले का लिखा वापस रख रहा हूँ",
    )
    /** The user's own earlier text, read to them so it isn't lost (it was verbatim on screen). */
    fun yourEarlierText(original: String) = s("Your earlier text was: \"$original\".", "आपका पहले का लिखा था: \"$original\"।")
    fun restoreFailed(chat: String, original: String) = s(
        "I couldn't put your earlier text back in $chat's chat yet; nothing was sent. Your earlier text was: \"$original\". I'll try again when I can.",
        "$chat की चैट में आपका पहले का लिखा अभी वापस नहीं रख पाया; कुछ नहीं भेजा। आपका लिखा था: \"$original\"। मैं फिर कोशिश करूँगा।",
    )
    fun restoreGaveUp(chat: String, original: String) = s(
        "I still couldn't put your earlier text back in $chat's chat. Nothing was sent. Your earlier text was: \"$original\". Please check that chat's message box.",
        "$chat की चैट में आपका पहले का लिखा अब भी वापस नहीं रख पाया। कुछ नहीं भेजा। आपका लिखा था: \"$original\"। उस चैट का संदेश बॉक्स देख लीजिए।",
    )
    fun originalLost(chat: String) = s(
        "I removed the message I typed for $chat. Your earlier unsent text there couldn't be put back because Saathi restarted, so please type it again. Nothing was sent.",
        "$chat के लिए मेरा लिखा संदेश हटा दिया। Saathi फिर से शुरू हुआ, इसलिए आपका पहले का लिखा वापस नहीं रख पाया, उसे फिर से लिख लीजिए। कुछ नहीं भेजा।",
    )

    // -- confirmation --------------------------------------------------------------------------
    /**
     * Never put a yes word ("yes", "haan", "हाँ" …) in this question or anything else shown or
     * said while Saathi waits for the answer: a yes that is also in the prompt can't be told
     * from Saathi hearing itself, so [ConfirmDialog] treats it as unclear.
     */
    fun confirmQuestion(recipient: String, message: String) = s(
        "Send to $recipient: \"$message\"? Should I send it?",
        "$recipient को भेजूँ: \"$message\"? भेज दूँ?",
    )

    /** The hint under the Confirm buttons (no yes word in it, see [confirmQuestion]). */
    val confirmHint get() = s(
        "Answer out loud after I finish speaking, or tap a button. If I'm not sure, I'll ask again, then cancel.",
        "मेरे बोलने के बाद जवाब दीजिए, या बटन दबाइए। समझ न आया तो एक बार फिर पूछूँगा, फिर कैंसल।",
    )

    /** Spoken text for a gate verdict. The gate's own words are Hindi; English maps by rule. */
    fun verdict(v: Verdict): String {
        if (lang == Lang.HI && v.message.isNotEmpty()) return v.message
        return when (v.rule) {
            "R8" -> "Please enter your PIN yourself. I've stopped."
            "R9" -> "You'll need to fill in this CAPTCHA yourself."
            "R7" -> "I won't type PINs, OTPs or passwords."
            "R2" -> "The message or name isn't clear on screen, so I've stopped."
            "R5" -> "This chat belongs to someone else, not the person you named."
            "R4" -> "What I'd read back isn't on the screen, so I've stopped."
            "R10" -> "That's not part of what you asked, so I won't do it."
            "R11" -> "That button is covered by another button, so I won't press it."
            "app" -> "That app isn't on my list."
            "index" -> "I couldn't find that button on screen."
            "confirm" -> "I didn't get a yes, so I won't send it."
            else -> if (v.rule.startsWith("changed")) notSentChanged else "I've stopped to be safe."
        }
    }
}
