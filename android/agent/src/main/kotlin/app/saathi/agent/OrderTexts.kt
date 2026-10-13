package app.saathi.agent

/**
 * Saathi's words for grocery ordering (Blinkit), one language at a time like [Texts]. Product
 * names, sizes, prices and totals are always passed in verbatim from the screen (hard rule 4).
 *
 * Nothing said or shown while Saathi waits for a yes may contain a yes word (see
 * [Texts.confirmQuestion]); [OrderTextsTest] checks it.
 */
class OrderTexts(val lang: Lang) {
    private fun s(en: String, hi: String): String = if (lang == Lang.HI) hi else en

    private fun lines(lines: List<CartLine>): String =
        lines.joinToString(", ") { "${it.label} ×${it.qty} ${it.price}".replace("  ", " ") }

    // -- titles --------------------------------------------------------------------------------
    fun title(c: GroceryCommand): String = when (c) {
        is GroceryCommand.Order -> if (c.addOnly) "Add ${c.item ?: "an item"} to the Blinkit cart" else "Order ${c.item ?: "groceries"} on Blinkit"
        is GroceryCommand.ReadCart -> "What's in the Blinkit cart"
        is GroceryCommand.PlaceOrder -> "Place the Blinkit order"
        is GroceryCommand.EmptyCart -> "Empty the Blinkit cart"
    }

    // -- narration -----------------------------------------------------------------------------
    val opening get() = s("Opening Blinkit", "Blinkit खोल रहा हूँ")
    fun searching(item: String) = s("Searching Blinkit for $item", "Blinkit पर $item खोज रहा हूँ")
    val waiting get() = s("Waiting for Blinkit to load", "Blinkit के खुलने का इंतज़ार कर रहा हूँ")
    val openingSearch get() = s("Opening Blinkit's search box", "Blinkit का खोज बॉक्स खोल रहा हूँ")
    fun typingSearch(item: String) = s("Typing $item in the search box", "खोज बॉक्स में $item लिख रहा हूँ")
    fun adding(p: Product) = s("Adding ${p.label} to the cart", "${p.label} कार्ट में डाल रहा हूँ")
    fun addingOneMore(p: Product) = s("Adding one more ${p.label}", "एक और ${p.label} डाल रहा हूँ")
    val choosingSize get() = s("Choosing the size", "साइज़ चुन रहा हूँ")
    val openingCart get() = s("Opening the cart", "कार्ट खोल रहा हूँ")
    val closingSheet get() = s("Closing this sheet", "यह शीट बंद कर रहा हूँ")
    val manualLocation get() = s("Choosing the location manually; I never share your location", "जगह खुद चुन रहा हूँ; आपकी लोकेशन नहीं दूँगा")
    val closingLocation get() = s("Closing the location sheet", "लोकेशन वाली शीट बंद कर रहा हूँ")
    val readyToOrder get() = s("Cart is ready. I'll read it to you before ordering", "कार्ट तैयार है, ऑर्डर से पहले पढ़कर सुनाऊँगा")
    val placing get() = s("Placing the order", "ऑर्डर कर रहा हूँ")
    val removingOne get() = s("Removing one item", "एक आइटम हटा रहा हूँ")
    val scrollingClear get() = s("Scrolling so the button isn't covered", "स्क्रॉल कर रहा हूँ ताकि बटन ढका न रहे")
    val lookingForCart get() = s("Looking for the cart", "कार्ट ढूँढ रहा हूँ")

    // -- questions -----------------------------------------------------------------------------
    /** "Amul Taaza 500 ml for ₹27" (verbatim name, size and price). */
    fun option(p: Product) = s("${p.label} for ${p.price}", "${p.label}, ${p.price}")

    fun whichProduct(options: List<Product>) = s(
        "I found a few close matches: ${options.joinToString(", or ") { option(it) }}. Which one?",
        "कुछ मिलते-जुलते मिले: ${options.joinToString(", या ") { option(it) }}। कौन सा चाहिए?",
    )

    fun whichSize(options: List<Product>) = s(
        "Which size: ${options.joinToString(", or ") { option(it) }}?",
        "कौन सा साइज़: ${options.joinToString(", या ") { option(it) }}?",
    )

    val pickHint get() = s(
        "Say the name or “first”, “second”, or tap one. If I'm not sure, I'll ask again, then cancel.",
        "नाम या “पहला”, “दूसरा” बोलिए, या बटन दबाइए। समझ न आया तो एक बार फिर पूछूँगा, फिर कैंसल।",
    )

    /** Confirm-screen question before ordering (English; Hindi uses the gate's own read-back). No yes word. */
    fun orderQuestion(o: OrderSummary): String {
        val more = if (o.shipment.isNotEmpty()) " (${o.shipment}; some items are further down the screen)" else ""
        val total = if (o.total.isNotEmpty()) " Total ${o.total}." else " I can't see the total on this screen."
        return s(
            "Order from Blinkit: ${lines(o.lines)}$more.$total Shall I order it?",
            "Blinkit ऑर्डर: ${lines(o.lines)}$more।${if (o.total.isNotEmpty()) " कुल ${o.total}।" else ""} ऑर्डर कर दूँ?",
        )
    }

    fun emptyQuestion(o: OrderSummary) = s(
        "Remove everything from your Blinkit cart: ${lines(o.lines)}? Nothing will be ordered.",
        "Blinkit कार्ट से सब हटा दूँ: ${lines(o.lines)}? कुछ ऑर्डर नहीं होगा।",
    )

    // -- answers -------------------------------------------------------------------------------
    fun readback(o: OrderSummary): String {
        val more = if (o.shipment.isNotEmpty()) s(" (${o.shipment}; some are further down)", " (${o.shipment}, बाकी नीचे हैं)") else ""
        val total = if (o.total.isNotEmpty()) s(" Total ${o.total}.", " कुल ${o.total}।") else s(" The total isn't shown on this screen.", " कुल रकम स्क्रीन पर नहीं दिख रही।")
        return s("Your Blinkit cart: ${lines(o.lines)}$more.$total", "Blinkit कार्ट में: ${lines(o.lines)}$more।$total")
    }

    val orderingOff get() = s(
        "Ordering is turned off in Settings; your cart is ready. Nothing was ordered.",
        "Settings में ऑर्डर करना बंद है; आपका कार्ट तैयार है। कुछ ऑर्डर नहीं हुआ।",
    )
    fun added(p: Product, n: Int) = s("Added ${p.label} ×$n to your Blinkit cart.", "${p.label} ×$n Blinkit कार्ट में डाल दिया।")
    fun alreadyInCart(p: Product) = s("${p.label} was already in your cart, so I didn't add another.", "${p.label} पहले से कार्ट में था, इसलिए और नहीं डाला।")
    val sayPlaceOrder get() = s("Nothing was ordered. Say “place my order” when you're ready.", "कुछ ऑर्डर नहीं हुआ। तैयार हों तो “ऑर्डर करो” बोलिए।")
    val cartEmpty get() = s("Your Blinkit cart is empty.", "आपका Blinkit कार्ट खाली है।")
    val cartEmptied get() = s("I emptied your Blinkit cart. Nothing was ordered.", "Blinkit कार्ट खाली कर दिया। कुछ ऑर्डर नहीं हुआ।")
    fun orderPlaced(seen: String) = s("Blinkit says: \"$seen\".", "Blinkit पर लिखा है: \"$seen\"।")
    fun nothingFound(item: String) = s("Blinkit found nothing for $item. Nothing was added.", "Blinkit पर $item नहीं मिला। कुछ नहीं डाला।")
    fun outOfStock(item: String) = s("$item is out of stock on Blinkit right now. Nothing was added.", "$item अभी Blinkit पर स्टॉक में नहीं है। कुछ नहीं डाला।")
    fun noGoodMatch(item: String, seen: List<Product>) = s(
        "I couldn't find $item on Blinkit. I saw: ${seen.joinToString("; ") { option(it) }}. Nothing was added.",
        "Blinkit पर $item नहीं मिला। दिखे: ${seen.joinToString("; ") { option(it) }}। कुछ नहीं डाला।",
    )
    fun foundReadOnly(item: String, seen: List<Product>) = s(
        "On Blinkit for $item: ${seen.joinToString("; ") { option(it) }}. The grocery app is set to Read only in Settings, so I didn't add anything.",
        "Blinkit पर $item: ${seen.joinToString("; ") { option(it) }}। Settings में ग्रॉसरी ऐप सिर्फ़ पढ़ने के लिए है, इसलिए कुछ नहीं डाला।",
    )

    // -- stops and hand-overs --------------------------------------------------------------------
    fun overLimit(total: String, limit: Int) = s(
        "The total is $total, above your spending limit of ₹$limit in Settings, so I won't place this order.",
        "कुल $total है, जो Settings की ₹$limit की सीमा से ज़्यादा है, इसलिए ऑर्डर नहीं करूँगा।",
    )
    val noTotal get() = s(
        "I can't see the order total on screen, so I won't place the order.",
        "स्क्रीन पर कुल रकम नहीं दिख रही, इसलिए ऑर्डर नहीं करूँगा।",
    )
    val noOrderButton get() = s("I can't find the order button on the checkout screen, so I've stopped.", "चेकआउट पर ऑर्डर का बटन नहीं मिला, मैं रुक गया।")
    val notGated get() = s("The order button didn't look as expected, so I've stopped to be safe.", "ऑर्डर का बटन वैसा नहीं दिखा जैसा होना चाहिए, इसलिए रुक गया।")
    val loginNeeded get() = s(
        "Blinkit wants you to log in. Please log in yourself (I never type OTPs or passwords), then ask me again.",
        "Blinkit लॉग इन माँग रहा है। खुद लॉग इन कीजिए (मैं OTP या पासवर्ड नहीं डालता), फिर दोबारा कहिए।",
    )
    val noNetwork get() = s("Blinkit says there's no internet connection. Nothing was ordered.", "Blinkit कह रहा है इंटरनेट नहीं है। कुछ ऑर्डर नहीं हुआ।")
    val addressNeeded get() = s(
        "Blinkit needs a delivery address. Please add or choose it yourself, then ask me again.",
        "Blinkit को डिलीवरी का पता चाहिए। खुद पता जोड़िए या चुनिए, फिर दोबारा कहिए।",
    )
    fun notServiceable(seen: String) = s("Blinkit says: \"$seen\". Nothing was ordered.", "Blinkit पर लिखा है: \"$seen\"। कुछ ऑर्डर नहीं हुआ।")
    val permissionAsk get() = s(
        "Blinkit is asking for a permission. Please answer it yourself; I never grant permissions for you.",
        "Blinkit एक अनुमति माँग रहा है। खुद जवाब दीजिए; मैं आपकी तरफ़ से अनुमति नहीं देता।",
    )
    val payYourself get() = s(
        "Blinkit is asking how to pay. Please choose and pay yourself; I never enter PINs.",
        "Blinkit पूछ रहा है कि भुगतान कैसे करें। खुद चुनकर भुगतान कीजिए; मैं पिन नहीं डालता।",
    )
    val checkScreen get() = s(
        "I pressed the order button, but I can't tell yet whether the order went through. Please check the Blinkit screen.",
        "ऑर्डर का बटन दबा दिया, पर अभी पता नहीं चल रहा कि ऑर्डर हुआ या नहीं। Blinkit की स्क्रीन देख लीजिए।",
    )
    val searchFailed get() = s("I couldn't search on Blinkit, so I've stopped. Nothing was added.", "Blinkit पर खोज नहीं पाया, रुक गया। कुछ नहीं डाला।")
    val couldNotOpen get() = s("I couldn't open Blinkit. Is it installed?", "Blinkit नहीं खुला। क्या यह फ़ोन में है?")
    val couldNotAdd get() = s("I couldn't add it to the cart, so I've stopped.", "कार्ट में नहीं डाल पाया, रुक गया।")
    val stuck get() = s("This Blinkit screen isn't what I expected, so I've stopped.", "Blinkit की यह स्क्रीन अनजानी है, मैं रुक गया।")
    val cartChanged get() = s("The cart changed while I was working, so I've stopped.", "काम के बीच कार्ट बदल गया, इसलिए रुक गया।")
    fun quantityMismatch(p: Product, n: Int) = s(
        "The cart shows ${p.label} ×$n, not what you asked for, so I won't order.",
        "कार्ट में ${p.label} ×$n दिख रहा है, जो आपने कहा उससे अलग है, इसलिए ऑर्डर नहीं करूँगा।",
    )
    val pickCancelled get() = s("Cancelled. Nothing was added or ordered.", "कैंसल कर दिया। कुछ नहीं डाला, कुछ ऑर्डर नहीं हुआ।")
    val orderCancelled get() = s("Cancelled. Nothing was ordered.", "कैंसल कर दिया। कुछ ऑर्डर नहीं हुआ।")
    val emptyCancelled get() = s("Cancelled. I left your cart as it was.", "कैंसल कर दिया। कार्ट जैसा था वैसा है।")

    /** Said whenever a run ends without an order after items were added or a cart was seen. */
    fun cartStillHas(lines: List<CartLine>) = s(
        "Your Blinkit cart still has: ${lines(lines)}. Say “empty my cart” if you want me to remove them.",
        "Blinkit कार्ट में अभी भी है: ${lines(lines)}। हटाना हो तो “कार्ट खाली करो” बोलिए।",
    )
    val cartPartlyEmptied get() = s(
        "I removed some items, but your Blinkit cart may not be empty yet. Please check it.",
        "कुछ आइटम हटाए, पर कार्ट शायद अभी पूरा खाली नहीं है। देख लीजिए।",
    )

    // -- before starting -----------------------------------------------------------------------
    val whatToOrder get() = s(
        "What should I get from Blinkit? Say, for example, “order milk from Blinkit”.",
        "Blinkit से क्या मंगाऊँ? जैसे बोलिए: “Blinkit से दूध मंगाओ”।",
    )
    fun oneAtATime(items: List<String>) = s(
        "I can order one item at a time. Which first: ${items.joinToString(" or ")}?",
        "मैं एक बार में एक ही चीज़ मंगा सकता हूँ। पहले कौन सी: ${items.joinToString(" या ")}?",
    )
    fun unknownItem(word: String) = s(
        "I don't know the item \"$word\" yet. Please say it another way, like “order milk” or “bread mangao”.",
        "\"$word\" मैं अभी नहीं पहचानता। किसी और तरह बोलिए, जैसे “दूध मंगाओ” या “bread mangao”।",
    )
    fun tooMany(max: Int) = s("I can add at most $max of one item at a time.", "एक बार में एक चीज़ के ज़्यादा से ज़्यादा $max ही डाल सकता हूँ।")
    val groceryOff get() = s(
        "The grocery app is turned off for Saathi in Settings, so I didn't open Blinkit.",
        "Settings में ग्रॉसरी ऐप साथी के लिए बंद है, इसलिए Blinkit नहीं खोला।",
    )
    val groceryReadOnly get() = s(
        "The grocery app is set to Read only in Settings, so I won't change the cart or order. I can search or read your cart.",
        "Settings में ग्रॉसरी ऐप सिर्फ़ पढ़ने के लिए है, इसलिए कार्ट नहीं बदलूँगा, ऑर्डर नहीं करूँगा। खोज सकता हूँ या कार्ट पढ़ सकता हूँ।",
    )
}
