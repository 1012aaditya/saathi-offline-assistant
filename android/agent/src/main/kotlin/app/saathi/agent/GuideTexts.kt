package app.saathi.agent

/**
 * Saathi's own words in guide mode ("let's do it together"). Anything quoted from the screen is
 * passed in verbatim (hard rule 4). No text here contains a yes word or a position word, so a
 * real "yes" / "first" on a sheet that follows can't be mistaken for an echo of these lines.
 */
class GuideTexts(val lang: Lang) {
    private fun s(en: String, hi: String): String = if (lang == Lang.HI) hi else en

    // -- the conversation ----------------------------------------------------------------------
    val start get() = s("Let's do it together. I'll tell you what I see; you tell me what to do.", "चलिए साथ में करते हैं। मैं बताऊँगा क्या दिख रहा है, आप बताइए क्या करना है।")
    val notSure get() = s("I'm not sure what to do on this screen.", "इस स्क्रीन पर क्या करना है, मुझे पक्का नहीं पता।")
    val hereIsWhat get() = s("Here's what I see.", "यह दिख रहा है।")
    val whatNext get() = s("What should I do?", "क्या करूँ?")
    val pressSpeak get() = s("Press Speak when you're ready.", "तैयार हों तो Speak दबाइए।")
    fun on(app: String, title: String?) = if (title.isNullOrBlank()) s("$app.", "$app.") else s("$app: $title.", "$app: $title.")
    val iSee get() = s("I can see:", "दिख रहा है:")
    fun more(n: Int) = s("And $n more. Say \"read more\".", "और $n चीज़ें। \"और बताओ\" बोलिए।")
    val noMore get() = s("That's everything on this screen.", "इस स्क्रीन पर बस इतना ही है।")
    val nothingVisible get() = s("I can't see anything to read on this screen.", "इस स्क्रीन पर पढ़ने लायक कुछ नहीं दिख रहा।")
    val loading get() = s("The screen is still loading.", "स्क्रीन अभी खुल रही है।")
    val passwordField get() = s("a password field", "पासवर्ड का खाना")
    val codeField get() = s("a code or password field", "कोड या पासवर्ड का खाना")
    val codeText get() = s("a code or number (not read aloud)", "कोई कोड या नंबर (पढ़ा नहीं)")
    val savedAddress get() = s("your saved address", "आपका सेव किया पता")
    fun field(label: String) = s("$label field", "$label खाना")
    fun fieldHas(label: String, text: String) = s("$label field, has: $text", "$label खाना, लिखा है: $text")
    val emptyField get() = s("text field", "लिखने का खाना")
    fun cartBadge(text: String) = s("Cart: $text.", "कार्ट: $text.")
    fun dialog(text: String) = s("A message is showing: $text.", "एक संदेश दिख रहा है: $text.")
    fun outOfStock(heading: String, item: String) = s("$heading: $item.", "$heading: $item.")
    fun outOfStockAsk(item: String) = s(
        "Your cart has an item that's out of stock: $item. Should I move it to your wishlist? Say \"Move to wishlist\", \"keep it\", or add one of the suggestions.",
        "कार्ट में एक चीज़ स्टॉक में नहीं है: $item. विशलिस्ट में डालूँ? \"Move to wishlist\", \"रहने दो\", या कोई सुझाव जोड़ने को कहिए।",
    )
    fun alreadyInCart(item: String, qty: String) = s(
        "$item is already in your cart ($qty). I won't add more on my own.",
        "$item कार्ट में मौजूद है ($qty)। मैं अपने आप और नहीं डालूँगा।",
    )
    fun alreadyInCartHow(item: String, qty: String) = s(
        "$item is already in your cart: $qty. To add one more, say \"plus $item\".",
        "$item कार्ट में मौजूद है: $qty. एक और डालने के लिए \"plus $item\" बोलिए।",
    )

    // -- after an action ------------------------------------------------------------------------
    fun tapped(label: String) = s("Pressed $label.", "$label दबाया।")
    val added get() = s("Added.", "डाल दिया।")
    val removedOne get() = s("Removed one.", "एक हटा दिया।")
    val addedOne get() = s("Added one more.", "एक और डाल दिया।")
    fun typed(text: String) = s("Typed: $text.", "लिखा: $text.")
    val scrolledDown get() = s("Scrolled down.", "नीचे किया।")
    val scrolledUp get() = s("Scrolled up.", "ऊपर किया।")
    val wentBack get() = s("Went back.", "वापस गया।")
    fun searched(q: String) = s("Searched for $q.", "$q खोजा।")
    fun nowOn(title: String) = s("Now on $title.", "अब $title पर।")
    fun nowIn(app: String) = s("Now in $app.", "अब $app में।")
    fun qtyNow(name: String, qty: String) = s("$name now shows $qty.", "$name अब $qty दिखा रहा है।")
    fun showsAdd(name: String, add: String) = s("$name shows $add again.", "$name पर फिर से $add दिख रहा है।")
    val cartGone get() = s("The cart bar is gone.", "कार्ट वाली पट्टी हट गई।")
    val nothingChanged get() = s("Nothing changed on screen.", "स्क्रीन पर कुछ नहीं बदला।")
    val screenChanged get() = s("The screen changed.", "स्क्रीन बदल गई।")
    val couldNotPress get() = s("I couldn't do that on the screen.", "स्क्रीन पर यह नहीं हो पाया।")
    val couldNotReturn get() = s("I couldn't get back to the app.", "ऐप पर वापस नहीं जा पाया।")
    val screenMoved get() = s("The screen changed before I could act, so I didn't press anything.", "दबाने से पूर्व स्क्रीन बदल गई, इसलिए कुछ नहीं दबाया।")
    val keptIt get() = s("OK, I've left it as it is.", "ठीक है, जैसा था वैसा छोड़ दिया।")

    // -- understanding --------------------------------------------------------------------------
    fun cantSee(what: String, seen: List<String>) = if (seen.isEmpty()) {
        s("I can't see \"$what\" here.", "यहाँ \"$what\" नहीं दिख रहा।")
    } else {
        s("I can't see \"$what\" here. I can see: ${seen.joinToString("; ")}.", "यहाँ \"$what\" नहीं दिख रहा। दिख रहा है: ${seen.joinToString("; ")}.")
    }
    fun whichOne(options: List<String>) = s("Which one: ${options.joinToString(", or ")}?", "कौन सा: ${options.joinToString(", या ")}?")
    val pickHint get() = s("Say its name, or tap it. Cancel is the default.", "नाम बोलिए या उस पर टैप कीजिए। अपने आप कैंसल होगा।")
    val pickCancelled get() = s("OK, I didn't press anything.", "ठीक है, कुछ नहीं दबाया।")
    val didntUnderstand get() = s(
        "I didn't understand. You can say: tap and a name, add an item, search, scroll down, go back, read more, or done.",
        "समझ नहीं आया। आप बोल सकते हैं: किसी नाम पर टैप, कोई चीज़ जोड़ो, खोजो, नीचे करो, वापस जाओ, और बताओ, या बस।",
    )
    val help get() = didntUnderstand
    val tooSoon get() = s("I heard that while I was still talking. Please say it again.", "मैं बोल ही रहा था तब सुनाई दिया। फिर से बोलिए।")
    val echo get() = s("That sounded like my own words. Please say it again in short.", "यह मेरी ही बात जैसी लगी। छोटा करके फिर बोलिए।")
    val whichItem get() = s("Which item?", "कौन सी चीज़?")
    val noField get() = s("I can't see a text box here.", "यहाँ लिखने का खाना नहीं दिख रहा।")
    val noSearch get() = s("I can't see a search box here.", "यहाँ खोजने का खाना नहीं दिख रहा।")
    val noCart get() = s("I can't see a cart on this screen.", "इस स्क्रीन पर कार्ट नहीं दिख रहा।")
    fun notInCart(name: String) = s("$name isn't in your cart.", "$name कार्ट में नहीं है।")
    fun noAddButton(name: String) = s("$name has no add button here.", "$name का जोड़ने वाला बटन यहाँ नहीं है।")
    val tooMany get() = s("I add at most 5 at a time.", "मैं एक बार में ज़्यादा से ज़्यादा 5 डालता हूँ।")
    fun cartIs(text: String) = s("Your cart: $text.", "आपका कार्ट: $text.")

    // -- refusals -------------------------------------------------------------------------------
    val noSecrets get() = s("I won't type codes, PINs or passwords. Please type it yourself.", "कोड, पिन या पासवर्ड मैं नहीं लिखूँगा। खुद लिखिए।")
    val risky get() = s(
        "That button may send, pay or buy something, and I can't check it in this app. Please press it yourself.",
        "यह बटन कुछ भेज, भुगतान या खरीद सकता है, और इस ऐप में मैं जाँच नहीं सकता। खुद दबाइए।",
    )
    val choosePayment get() = s(
        "Choosing how to pay is up to you. Please choose and pay yourself; I never choose a payment method or enter PINs.",
        "भुगतान का तरीका आप खुद चुनिए और भुगतान कीजिए; मैं न तरीका चुनता हूँ, न पिन डालता हूँ।",
    )
    val loginScreen get() = s("This is a sign-in screen. Please sign in yourself; I never type phone numbers, codes or passwords.", "यह लॉग इन स्क्रीन है। खुद लॉग इन कीजिए; मैं नंबर, कोड या पासवर्ड नहीं डालता।")
    val noPayments get() = s("I don't make payments in this mode.", "इस मोड में मैं भुगतान नहीं करता।")
    fun appOff(app: String) = s("$app is off in Settings, so I won't use it.", "Settings में $app बंद है, मैं इसे नहीं चलाऊँगा।")
    val groceryReadOnly get() = s("Grocery is set to read only in Settings, so I won't add or order.", "Settings में किराना सिर्फ़ पढ़ने पर है, मैं न डालूँगा न ऑर्डर करूँगा।")
    val whatsappReadOnly get() = s("WhatsApp is set to read only in Settings, so I won't send.", "Settings में WhatsApp सिर्फ़ पढ़ने पर है, मैं नहीं भेजूँगा।")
    val cartGuard get() = s("That would add more than you asked for, so I didn't press it.", "इससे जितना कहा उससे ज़्यादा जुड़ जाता, इसलिए नहीं दबाया।")
    val ownScreen get() = s("Saathi itself is in front, so I won't press anything.", "सामने Saathi ही है, इसलिए कुछ नहीं दबाऊँगा।")
    val notSent get() = s("Nothing was sent.", "कुछ नहीं भेजा।")
    val notOrdered get() = s("Nothing was ordered.", "कुछ ऑर्डर नहीं हुआ।")
    val cancelledHold get() = s("Cancelled. I didn't press it.", "कैंसल। मैंने नहीं दबाया।")
    val boxKept get() = s("Your message is still in the box.", "आपका संदेश अभी भी खाने में है।")

    // -- ending ---------------------------------------------------------------------------------
    val bye get() = s("OK, I've stopped guiding.", "ठीक है, साथ में करना बंद।")
    val idle get() = s("I haven't heard from you for 3 minutes, so I've stopped guiding.", "3 मिनट से कुछ नहीं सुना, इसलिए साथ में करना बंद कर दिया।")
    fun cartLeft(count: String) = s("Your cart has $count; nothing was ordered.", "आपके कार्ट में $count हैं; कुछ ऑर्डर नहीं हुआ।")
    val orderPlaced get() = s("Your order was placed.", "आपका ऑर्डर हो गया।")
    val messageLeft get() = s("Your message is still in the box; it wasn't sent.", "आपका संदेश अभी खाने में है; भेजा नहीं गया।")
    fun sentCount(n: Int) = s(if (n == 1) "1 message was sent." else "$n messages were sent.", "$n संदेश भेजे गए।")
    fun leftOn(app: String, title: String?) = if (title.isNullOrBlank()) s("You're on $app.", "आप $app पर हैं।") else s("You're on $app: $title.", "आप $app पर हैं: $title.")
    val title get() = s("Doing it together", "साथ में")
    val noTarget get() = s(
        "Which app? Say for example \"guide me in Blinkit\", or open the app, hold Volume-down there and say \"guide me\".",
        "कौन सा ऐप? जैसे \"Blinkit में साथ में करते हैं\" बोलिए, या ऐप खोलकर वहाँ Volume-down दबाकर \"guide me\" बोलिए।",
    )
}
