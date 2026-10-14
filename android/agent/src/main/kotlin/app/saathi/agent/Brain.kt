package app.saathi.agent

/**
 * Smart understanding: an on-device language model (Gemma 3 1B through MediaPipe on the phone,
 * fully offline) that turns a free-form sentence ("can you order diet Coke in my card on",
 * "mujhe doodh chahiye", "which is cheaper") into ONE guide command, so talking to Saathi feels
 * like talking to a friend.
 *
 * The model is an interpreter only, never an actor:
 *  - it can only pick an intent from [BrainIntent]; answers (yes / no), send, pay, place order,
 *    payment methods and the Confirm / Pick sheets stay with the user (VoiceAnswer pipeline);
 *  - everything it returns is validated ([BrainParser]); an invalid answer is retried once with a
 *    shorter prompt, then the offline rule parser ([GuideGrammar]) decides as before;
 *  - its intent is mapped to an existing [GuideCommand], so execution still goes
 *    Guide -> GatedDevice -> SafetyGate, unchanged;
 *  - a target it guessed (not named by the user's own words) is never acted on: the user picks it
 *    on the "which one?" sheet (Cancel is the default);
 *  - anything it says about the screen must be verbatim from the screen ([BrainParser.grounded]),
 *    and it never claims something was sent, paid or ordered.
 */
interface Brain {
    /** True when the model is loaded and can answer now. Never blocks (loading happens elsewhere). */
    val ready: Boolean

    /**
     * The model's raw text for [prompt], or null on timeout / error / not ready. Must give up
     * within about [timeoutMs] and be cancellable.
     */
    suspend fun complete(prompt: String, timeoutMs: Long): String?
}

/** The only things the model may ask for. Wire names are what the prompt and the JSON use. */
enum class BrainIntent(val wire: String) {
    ADD_ITEM("add_item"),
    REMOVE_ITEM("remove_item"),
    INCREASE("increase"),
    OPEN_CART("open_cart"),
    CHECKOUT("checkout"),
    SEARCH("search"),
    TAP("tap"),
    SCROLL_DOWN("scroll_down"),
    SCROLL_UP("scroll_up"),
    BACK("back"),
    READ_MORE("read_more"),
    WHAT_ON_SCREEN("what_on_screen"),
    LOOK_AT_PICTURE("look_at_picture"),
    DO_TASK("do_task"),
    CART_SUMMARY("cart_summary"),
    COMPARE_PRICES("compare_prices"),
    ASK_CLARIFY("ask_clarify"),
    DONE("done"),
    CHIT_CHAT("chit_chat"),
    ;

    companion object {
        fun of(wire: String): BrainIntent? = entries.firstOrNull { it.wire == wire.trim().lowercase() }

        val WIRE: List<String> = entries.map { it.wire }

        /**
         * Intents that would answer for the user or do something irreversible. A model that asks for
         * one of these is rejected outright (not retried): the user does these themselves.
         */
        val FORBIDDEN = Regex(
            "yes|no\\b|haan|confirm|approve|accept|agree|send|place|order|pay|purchase|buy|checkout_confirm|" +
                "payment|upi|pin|otp|password|answer|pick|select_option|choose|type|call|delete|transfer",
            RegexOption.IGNORE_CASE,
        )
    }
}

/** One validated model answer (still to be grounded on the screen by [GuideBrain]). */
data class BrainDecision(
    val intent: BrainIntent,
    val target: String? = null,
    val quantity: Int? = null,
    val query: String? = null,
    val reply: String = "",
)

/** One exchange of the conversation memory: what the user said, what Saathi answered. */
data class BrainTurn(val user: String, val saathi: String)

/** Everything the model sees for one utterance. */
data class BrainInput(
    val utterance: String,
    val summary: ScreenSummary,
    val history: List<BrainTurn>,
    val lang: Lang,
)
