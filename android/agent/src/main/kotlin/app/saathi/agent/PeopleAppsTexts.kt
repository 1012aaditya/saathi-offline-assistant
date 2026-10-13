package app.saathi.agent

/*
 * Saathi's own words for "message anyone" and "open any app" (same rules as [Texts]: one
 * language at a time, Hindi in Devanagari, anything from the screen quoted verbatim). Kept apart
 * from Texts.kt so parallel work on that file doesn't collide.
 *
 * Nothing said or shown while Saathi waits for a yes may contain a yes word (EchoGuard: a yes
 * that is also in the prompt can't be told from Saathi hearing itself). Tested.
 */

private fun Texts.s(en: String, hi: String): String = if (lang == Lang.HI) hi else en

private fun Texts.orList(names: List<String>): String {
    val q = names.map { "\"$it\"" }
    if (q.size <= 1) return q.joinToString("")
    val or = if (lang == Lang.HI) " या " else " or "
    return q.dropLast(1).joinToString(", ") + or + q.last()
}

// -- message anyone ------------------------------------------------------------------------------

fun Texts.couldntFind(name: String) = s("I couldn't find $name in WhatsApp.", "WhatsApp में $name नहीं मिले।")

fun Texts.notOnWhatsApp(name: String) = s(
    "$name is in your phone's contacts, but I couldn't find them in WhatsApp. They may not use WhatsApp, and I can't use SMS yet.",
    "$name आपके फ़ोन के संपर्कों में हैं, पर WhatsApp में नहीं मिले। शायद वे WhatsApp पर नहीं हैं, और मैं अभी SMS नहीं चला सकता।",
)

fun Texts.sameName(name: String) = s(
    "I found more than one \"$name\" with exactly the same name, so I can't tell them apart. Please open the right chat yourself. Nothing was sent.",
    "\"$name\" नाम के एक से ज़्यादा मिले, मैं उनमें फ़र्क नहीं कर सकता। सही चैट खुद खोलिए। कुछ नहीं भेजा।",
)

/** "Which Rahul: "Rahul Sharma" or "Rahul Verma"?" — the options are verbatim on screen. */
fun Texts.whichContact(spoken: String, names: List<String>) = s(
    "Which $spoken: ${orList(names)}?",
    "कौन से $spoken: ${orList(names)}?",
)

/** Never an ordinal word ("first", "पहला"): a real "first" must not look like an echo of the sheet ([Ordinals]). */
val Texts.contactChoiceHint get() = s(
    "After I finish speaking, say the name on its own, or its place in the list counting from the top. Or tap a name. If I'm not sure, I'll ask again, then stop.",
    "मेरे बोलने के बाद सिर्फ़ नाम बोलिए, या ऊपर से गिनकर उसकी जगह। या नाम दबाइए। समझ न आया तो एक बार फिर पूछूँगा, फिर रुक जाऊँगा।",
)

val Texts.contactNotChosen get() = s(
    "I wasn't sure which one you meant, so I stopped. Nothing was sent.",
    "समझ नहीं आया आप किसकी बात कर रहे हैं, इसलिए रुक गया। कुछ नहीं भेजा।",
)

/** "Searching for your own chat" ("message myself"). */
val Texts.yourOwnChat get() = s("your own chat", "आपकी अपनी चैट")

val Texts.selfChatNotFound get() = s(
    "I couldn't find your own chat in WhatsApp; it shows your name with “(You)”. Say your name as WhatsApp shows it instead. Nothing was sent.",
    "WhatsApp में आपकी अपनी चैट नहीं मिली; उसमें आपके नाम के साथ “(You)” लिखा होता है। WhatsApp में दिखने वाला अपना नाम बोलिए। कुछ नहीं भेजा।",
)

/** WhatsApp opened on a screen whose Back closes WhatsApp, and it has no Up button. */
val Texts.whatsappStuck get() = s(
    "WhatsApp opened on a screen I can't leave safely by myself. Please go to the Chats list in WhatsApp, then ask me again. Nothing was sent.",
    "WhatsApp ऐसी स्क्रीन पर खुला जिससे मैं खुद सुरक्षित नहीं निकल सकता। WhatsApp में Chats सूची खोलिए, फिर दोबारा कहिए। कुछ नहीं भेजा।",
)

fun Texts.openingPicked(name: String) = s("Opening $name's chat", "$name की चैट खोल रहा हूँ")

/** Said once, just before Android's own contacts permission dialog (the user taps Allow themselves). */
val Texts.contactsWhy get() = s(
    "To find people by the names you use, Saathi can look at the names in your phone's contacts. They stay on this phone. Android will ask now: tap Allow if you agree, or Don't allow to skip.",
    "आपके बोले नाम से लोगों को ढूँढने के लिए साथी आपके फ़ोन के संपर्कों के नाम देख सकता है। वे इसी फ़ोन पर रहते हैं। Android अब पूछेगा: ठीक लगे तो Allow दबाइए, नहीं तो Don't allow।",
)

// -- open any app --------------------------------------------------------------------------------

/** The first-time question. No yes word in it (see the file note). */
fun Texts.appApprovalQuestion(label: String) = s(
    "Saathi hasn't opened $label before. Open it now? Answer after I finish speaking, or tap Open.",
    "साथी ने पहले कभी $label नहीं खोला। अभी खोलूँ? मेरे बोलने के बाद जवाब दीजिए, या “खोलें” दबाइए।",
)

val Texts.appApprovalHint get() = s(
    "Answer out loud after I finish speaking, or tap a button. If I'm not sure, I'll ask again, then I won't open it.",
    "मेरे बोलने के बाद जवाब दीजिए, या बटन दबाइए। समझ न आया तो एक बार फिर पूछूँगा, फिर नहीं खोलूँगा।",
)

fun Texts.appOpenTitle(label: String) = s("Open $label", "$label खोलें")

fun Texts.appOpened(label: String) = s(
    "Opening $label. I can't use $label for you offline yet. To hear what's on its screen, hold Volume-down and ask “What's on screen?”.",
    "$label खोल रहा हूँ। अभी बिना इंटरनेट मैं $label नहीं चला सकता। स्क्रीन सुनने के लिए Volume-down दबाकर पूछिए “स्क्रीन पर क्या है?”।",
)

fun Texts.appNotOpened(label: String) = s("I didn't open $label. Nothing changed.", "$label नहीं खोला। कुछ नहीं बदला।")

fun Texts.appNotSure(label: String) = s(
    "I wasn't sure, so I didn't open $label. Nothing changed.",
    "समझ नहीं आया, इसलिए $label नहीं खोला। कुछ नहीं बदला।",
)

fun Texts.appNotFound(name: String) = s(
    "I couldn't find an app called $name on this phone.",
    "इस फ़ोन पर $name नाम का ऐप नहीं मिला।",
)

fun Texts.appWhich(labels: List<String>) = s(
    "I found more than one app: ${orList(labels)}. Please say the full name.",
    "एक से ज़्यादा ऐप मिले: ${orList(labels)}। पूरा नाम बताइए।",
)

fun Texts.appNotAllowed(label: String) = s(
    "$label is set to Not allowed in Saathi's Settings, so I didn't open it.",
    "Settings में $label के लिए “अनुमति नहीं” है, इसलिए नहीं खोला।",
)

fun Texts.appPaymentsOff(label: String) = s(
    "$label is a payments app and Payments is turned off for Saathi in Settings, so I didn't open it.",
    "$label पेमेंट का ऐप है और Settings में Payments बंद है, इसलिए नहीं खोला।",
)

val Texts.appIsSaathi get() = s("That's Saathi itself. You're already here.", "यह साथी ही है, आप यहीं हैं।")

fun Texts.appCouldNotOpen(label: String) = s("I couldn't open $label.", "$label नहीं खुल पाया।")

/** Why Saathi refused to open an app (Settings levels / approvals). */
fun Texts.refused(reason: RefuseReason, label: String): String = when (reason) {
    RefuseReason.SAATHI_ITSELF -> appIsSaathi
    RefuseReason.NOT_ALLOWED -> appNotAllowed(label)
    RefuseReason.PAYMENTS_OFF -> appPaymentsOff(label)
    RefuseReason.GROCERY_OFF -> appOff(label)
}
