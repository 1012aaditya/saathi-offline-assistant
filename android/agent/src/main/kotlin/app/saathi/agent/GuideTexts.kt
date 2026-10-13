package app.saathi.agent

/**
 * Saathi's own words in guide mode ("let's do it together"), said like a helpful friend: what's
 * on screen first, short sentences, one question at the end. Anything quoted from the screen is
 * passed in verbatim (hard rule 4). No text here contains a yes word or a position word, so a
 * real "yes" / "first" on a sheet that follows can't be mistaken for an echo of these lines.
 * Nothing here tells the user to say "read more": that hint is shown on screen, never spoken
 * (14 Oct 00:40: a spoken 'Say "read more"' came back as a command).
 */
class GuideTexts(val lang: Lang) {
    private fun s(en: String, hi: String): String = if (lang == Lang.HI) hi else en
    private val hi get() = lang == Lang.HI

    // -- lists ------------------------------------------------------------------------------------
    /** "A and B" / "A, B, and C" (a comma before "and" when the items have commas of their own). */
    fun joinAnd(items: List<String>): String = when {
        items.isEmpty() -> ""
        items.size == 1 -> items[0]
        hi -> items.dropLast(1).joinToString(", ") + " और " + items.last()
        items.size == 2 && items.none { "," in it } -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
    }

    /** End of a sentence: "." or "।". */
    val stop get() = s(".", "।")

    /** Product cards, each with its own commas: "A; B; and C". */
    fun joinCards(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString("; ") + s("; and ", "; और ") + items.last()
    }

    fun couldSay(commands: List<String>): String? = when (commands.size) {
        0 -> null
        1 -> s("You could say \"${commands[0]}\".", "आप बोल सकते हैं \"${commands[0]}\"।")
        else -> s("You could say \"${commands[0]}\" or \"${commands[1]}\".", "आप बोल सकते हैं \"${commands[0]}\" या \"${commands[1]}\"।")
    }

    // Suggested commands (the guide grammar understands each of these).
    fun sayAdd(name: String) = s("add $name", "$name डालो")
    val sayOpenCart get() = s("open the cart", "कार्ट खोलो")
    fun sayOpen(name: String) = s("open $name", "$name खोलो")
    fun sayTap(name: String) = s("tap $name", "$name दबाओ")
    val sayReadMessages get() = s("read messages", "मैसेज पढ़ो")
    fun saySearch(q: String) = s("search for $q", "$q खोजो")

    // -- the conversation ----------------------------------------------------------------------
    val start get() = s("Let's do it together. I'll tell you what I see; you tell me what to do.", "चलिए साथ में करते हैं। मैं बताऊँगा क्या दिख रहा है, आप बताइए क्या करना है।")
    val notSure get() = s("I'm not sure what to do on this screen.", "इस स्क्रीन पर क्या करना है, मुझे पक्का नहीं पता।")
    val hereIsWhat get() = s("Here's what I see.", "यह दिख रहा है।")
    val whatNext get() = s("What next?", "अब क्या करूँ?")
    val pressSpeak get() = s("Press Speak when you're ready.", "तैयार हों तो Speak दबाइए।")
    fun on(app: String, title: String?) = if (title.isNullOrBlank()) "$app." else "$app: $title."
    val iSee get() = s("I can see:", "दिख रहा है:")
    fun iCanSee(list: String) = s("I can see $list.", "दिख रहा है: $list।")
    fun andMore(n: Int) = s("And $n more.", "और $n चीज़ें हैं।")

    /** On screen only (never spoken): how to hear the rest. */
    val moreHint get() = s("Say “read more” to hear the rest.", "बाकी सुनने के लिए “और बताओ” बोलिए।")
    val noMore get() = s("That's everything on this screen.", "इस स्क्रीन पर बस इतना ही है।")
    val nothingVisible get() = s("I can't see anything to read on this screen.", "इस स्क्रीन पर पढ़ने लायक कुछ नहीं दिख रहा।")
    val loading get() = s("The screen is still loading. Ask me again in a moment.", "स्क्रीन अभी खुल रही है। थोड़ी देर में फिर पूछिए।")
    val passwordField get() = s("a password field", "पासवर्ड का खाना")
    val codeField get() = s("a code or password field", "कोड या पासवर्ड का खाना")
    val codeText get() = s("a code or number (not read aloud)", "कोई कोड या नंबर (पढ़ा नहीं)")
    val savedAddress get() = s("your saved address", "आपका सेव किया पता")
    fun field(label: String) = s("$label field", "$label खाना")
    fun fieldHas(label: String, text: String) = s("$label field, has: $text", "$label खाना, लिखा है: $text")
    val emptyField get() = s("text field", "लिखने का खाना")
    fun cartBadge(text: String) = s("Cart: $text.", "कार्ट: $text.")

    // -- summaries ------------------------------------------------------------------------------
    fun youreIn(app: String, title: String?) = if (title.isNullOrBlank()) s("You're in $app.", "आप $app में हैं।") else s("You're in $app: $title.", "आप $app में हैं: $title।")
    fun foundFor(q: String) = s("Here's what I found for \"$q\":", "\"$q\" के लिए यह मिला:")
    fun inPlace(app: String, title: String) = s("In $app, $title:", "$app में, $title:")
    fun found(app: String) = s("Here's what $app shows:", "$app में यह दिख रहा है:")
    val alsoHere get() = s("Also here:", "और यह भी है:")
    fun inCartQty(n: Int) = s("$n in your cart", "कार्ट में $n")
    val outOfStockShort get() = s("out of stock", "स्टॉक में नहीं")
    fun cartHas(count: String) = s("Your cart has $count.", "आपके कार्ट में $count हैं।")
    val wantAdd get() = s("Want me to add one?", "कोई डालूँ?")
    val whatToBuy get() = s("What would you like to buy?", "क्या मँगाना है?")
    val atCheckout get() = s("You're at checkout.", "आप चेकआउट पर हैं।")
    fun oosOne(item: String) = s("One thing's out of stock: $item.", "एक चीज़ स्टॉक में नहीं है: $item।")
    fun oosMany(n: Int, items: String) = s("$n things are out of stock: $items.", "$n चीज़ें स्टॉक में नहीं हैं: $items।")
    val oosUnnamed get() = s("Something in your cart is out of stock.", "कार्ट में कुछ स्टॉक में नहीं है।")
    fun suggesting(app: String, word: String?) = when {
        word == null -> s("$app is suggesting other items.", "$app बदले में और चीज़ें सुझा रहा है।")
        hi -> "$app बदले में और $word सुझा रहा है।"
        else -> "$app is suggesting other ${if (word.endsWith("s")) word else word + "s"}."
    }
    fun inYourCart(lines: List<String>, more: Int) =
        s("In your cart: ${joinAnd(lines)}", "कार्ट में: ${joinAnd(lines)}") + (if (more > 0) s(", and $more more", ", और $more चीज़ें") else "") + stop
    val cartEmpty get() = s("Your cart is empty.", "आपका कार्ट खाली है।")
    fun totalIs(total: String) = s("The total is $total.", "कुल $total है।")
    val payYourself get() = s("To pay, you'll need to choose a payment method yourself.", "भुगतान का तरीका आपको खुद चुनना होगा।")
    val placeWhenReady get() = s(
        "When you're ready to order, say \"place order\" and I'll check with you before ordering.",
        "ऑर्डर करना हो तो \"place order\" बोलिए, ऑर्डर से पूर्व मैं आपसे पूछूँगा।",
    )
    val checkoutAsk get() = s("Want me to remove something or add more?", "कुछ हटाऊँ या और डालूँ?")
    val inChats get() = s("You're in your WhatsApp chats.", "आप WhatsApp चैट में हैं।")
    fun unreadChats(n: Int, list: String) =
        if (n == 1) s("One chat has unread messages: $list.", "एक चैट में नए मैसेज हैं: $list।")
        else s("$n chats have unread messages: $list.", "$n चैट में नए मैसेज हैं: $list।")
    val noUnread get() = s("No unread messages.", "कोई नया मैसेज नहीं।")
    fun chatsInclude(list: String) = s("Your chats include $list.", "चैट में $list हैं।")
    val wantOpen get() = s("Want me to open one?", "कोई चैट खोलूँ?")
    fun chatWith(name: String) = s("You're in your chat with $name.", "आप $name की चैट में हैं।")
    fun messagesOnScreen(n: Int) = when (n) {
        0 -> s("I can't see any messages.", "कोई मैसेज नहीं दिख रहा।")
        1 -> s("There's 1 message on screen.", "स्क्रीन पर 1 मैसेज है।")
        else -> s("There are $n messages on screen.", "स्क्रीन पर $n मैसेज हैं।")
    }
    fun boxHas(text: String) = s("Your message box has: $text.", "आपके मैसेज के खाने में लिखा है: $text।")
    val chatAsk get() = s("Want me to read the newest message, or type a reply?", "नया मैसेज पढ़ूँ, या जवाब लिखूँ?")
    fun asking(app: String, question: String) = s("$app is asking: $question", "$app पूछ रहा है: $question")
    fun choices(list: String) = s("The choices are $list.", "विकल्प हैं: $list।")
    val whichOne get() = s("Which one?", "कौन सा?")

    // -- after an action ------------------------------------------------------------------------
    /** Put before the first change: "Done — Amul Buffalo A2 Milk is gone." */
    val donePrefix get() = s("Done — ", "कर दिया — ")
    fun tapped(label: String) = s("I pressed $label.", "$label दबाया।")
    val added get() = s("Added.", "डाल दिया।")
    val removedOne get() = s("Removed one.", "एक हटा दिया।")
    val addedOne get() = s("Added one more.", "एक और डाल दिया।")
    fun typed(text: String) = s("I typed: $text.", "लिख दिया: $text।")
    val scrolledDown get() = s("Scrolled down.", "नीचे किया।")
    val scrolledUp get() = s("Scrolled up.", "ऊपर किया।")
    val wentBack get() = s("Went back.", "वापस गया।")
    fun nowIn(app: String) = s("Now in $app.", "अब $app में।")
    fun nowInCart(name: String, qty: Int, qtyText: String) =
        if (qty <= 1) s("$name is in your cart.", "$name कार्ट में आ गया।") else s("$name is in your cart, $qtyText.", "$name कार्ट में आ गया, $qtyText।")
    fun gone(name: String) = s("$name is gone.", "$name हटा दिया।")
    fun qtyNow(name: String, qty: String) = s("$name is now at $qty.", "$name अब $qty है।")
    fun cartNow(count: String, lines: List<String>, more: Int) =
        s("Your cart now has $count: ${joinAnd(lines)}", "अब कार्ट में $count हैं: ${joinAnd(lines)}") + (if (more > 0) s(", and $more more", ", और $more चीज़ें") else "") + stop
    fun cartNowCount(count: String) = s("Your cart now has $count.", "अब कार्ट में $count हैं।")
    fun itemsCount(n: Int) = s(if (n == 1) "1 item" else "$n items", "$n चीज़ें")
    val cartEmptyNow get() = s("Your cart is empty now.", "अब आपका कार्ट खाली है।")
    val cartGone get() = cartEmptyNow
    val nothingChanged get() = s("I tried, but nothing changed on screen.", "कोशिश की, पर स्क्रीन पर कुछ नहीं बदला।")
    val screenChanged get() = s("The screen changed.", "स्क्रीन बदल गई।")
    val couldNotPress get() = s("I couldn't do that on the screen.", "स्क्रीन पर यह नहीं हो पाया।")
    val couldNotReturn get() = s("I couldn't get back to the app.", "ऐप पर वापस नहीं जा पाया।")
    val notInFront get() = s(
        "That app isn't in front any more, so I won't press anything. Tap the Saathi bubble in the app you want.",
        "वह ऐप अब सामने नहीं है, इसलिए कुछ नहीं दबाऊँगा। जिस ऐप में चाहिए, वहाँ Saathi बबल दबाइए।",
    )
    val screenMoved get() = s("The screen changed before I could act, so I didn't press anything.", "दबाने से पूर्व स्क्रीन बदल गई, इसलिए कुछ नहीं दबाया।")
    val keptIt get() = s("OK, I've left it as it is.", "ठीक है, जैसा था वैसा छोड़ दिया।")
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

    // -- understanding --------------------------------------------------------------------------
    fun cantSee(what: String, seen: List<String>) = if (seen.isEmpty()) {
        s("I can't see \"$what\" here.", "यहाँ \"$what\" नहीं दिख रहा।")
    } else {
        s("I can't see \"$what\" here. I can see ${joinAnd(seen)}.", "यहाँ \"$what\" नहीं दिख रहा। दिख रहा है: ${joinAnd(seen)}।")
    }
    fun whichOne(options: List<String>) = s("Which one: ${options.joinToString(", or ")}?", "कौन सा: ${options.joinToString(", या ")}?")
    val pickHint get() = s("Say its name, or tap it. Cancel is the default.", "नाम बोलिए या उस पर टैप कीजिए। अपने आप कैंसल होगा।")
    val pickCancelled get() = s("OK, I didn't press anything.", "ठीक है, कुछ नहीं दबाया।")
    val pickNothingAdded get() = s("OK, I didn't add anything.", "ठीक है, कुछ नहीं डाला।")
    fun foundAsk(q: String, n: Int) = s(
        if (n == 1) "For \"$q\" I found one match. Should I add it? Pick it, or cancel." else "For \"$q\" I found $n matches. Which one should I add?",
        if (n == 1) "\"$q\" के लिए एक चीज़ मिली। डालूँ? उसे चुनिए, या कैंसल कीजिए।" else "\"$q\" के लिए $n चीज़ें मिलीं। कौन सी डालूँ?",
    )
    fun didYouMeanPick(heard: String) = s("I'm not sure which one you meant by \"$heard\". Which one should I add?", "\"$heard\" से कौन सी चीज़ मतलब है, पक्का नहीं। कौन सी डालूँ?")
    fun searchedNoMatch(q: String, seen: List<String>) =
        s("I searched for \"$q\". Did you mean something else?", "मैंने \"$q\" खोजा। क्या आप कुछ और कहना चाह रहे थे?") +
            (if (seen.isEmpty()) "" else " " + s("I found ${joinAnd(seen)}.", "मिला: ${joinAnd(seen)}।"))
    fun noResults(q: String) = s("I searched for \"$q\", but I can't see any results.", "मैंने \"$q\" खोजा, पर कुछ नहीं मिला।")

    /** Unknown input: what was heard, reflected back. */
    fun heard(text: String) = s("I heard \"$text\".", "मैंने सुना: \"$text\"।")
    fun guessAdd(name: String) = s("Did you want me to add $name to your cart?", "क्या $name कार्ट में डालूँ?")
    fun guessSearchAdd(name: String) = s("Did you want me to search for $name and add it to your cart?", "क्या $name खोजकर कार्ट में डालूँ?")
    fun guessMean(name: String) = s("Did you mean $name?", "क्या आपका मतलब $name था?")
    val guessCart get() = s("Did you want me to add something to your cart?", "क्या कार्ट में कुछ डालना है?")
    val notSureMeant get() = s("I'm not sure what you meant.", "पक्का नहीं समझा।")
    fun ifSoSay(command: String) = s("If so, say \"$command\".", "हो तो \"$command\" बोलिए।")
    val didntUnderstand get() = s(
        "I didn't understand. You can say: add an item, search, open the cart, scroll down, go back, read more, or done.",
        "समझ नहीं आया। आप बोल सकते हैं: कोई चीज़ डालो, खोजो, कार्ट खोलो, नीचे करो, वापस जाओ, और बताओ, या बस।",
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
    val wantOpenCart get() = s("Want me to open it?", "खोलूँ?")
    fun priceIs(line: String, price: String) = s(if ("," in line) "$line, is $price." else "$line is $price.", "$line $price का है।")
    fun available(line: String, price: String) = s(if ("," in line) "$line, is available for $price." else "$line is available for $price.", "$line मिल रहा है, $price का।")
    fun outOfStockNow(name: String) = s("$name is out of stock right now.", "$name अभी स्टॉक में नहीं है।")
    fun cheapest(list: String) = s("The cheapest here: $list.", "यहाँ सबसे सस्ते: $list।")
    val noPrices get() = s("I can't see any prices here.", "यहाँ कोई दाम नहीं दिख रहा।")
    val noTotal get() = s("I can't see a total on this screen.", "इस स्क्रीन पर कुल रकम नहीं दिख रही।")
    val totalAtCheckout get() = s("The total shows at checkout.", "कुल रकम चेकआउट पर दिखती है।")
    fun newestFrom(name: String, text: String) = s("The newest message, from $name, says: $text", "$name का नया मैसेज: $text")
    fun newestMine(text: String) = s("Your newest message says: $text", "आपका नया मैसेज: $text")
    val noMessages get() = s("I can't see any messages here.", "यहाँ कोई मैसेज नहीं दिख रहा।")

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
