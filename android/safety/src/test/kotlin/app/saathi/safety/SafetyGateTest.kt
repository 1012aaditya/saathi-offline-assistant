package app.saathi.safety

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * 1:1 port of tests/test_safety.py (88 pytest cases: 52 test functions, 10 parametrized).
 * Every rule change needs a test here and in the Python file (hard rule 1).
 */
class SafetyGateTest {
    private val gate = SafetyGate()

    private fun check(action: Action, s: Screen, ctx: CommandContext? = null, cap: Int = 10): Verdict =
        gate.check(action, s, ctx, cap)

    private fun isPinScreen(s: Screen) = gate.isPinScreen(s)

    companion object {
        const val WHATSAPP = Apps.WHATSAPP
        const val GPAY = Apps.GPAY
        const val BLINKIT = Apps.BLINKIT

        data class Spec(
            val text: String,
            val desc: String,
            val rid: String,
            val cls: String,
            val clickable: Boolean,
            val editable: Boolean,
            val password: Boolean,
        )

        fun el(
            text: String = "",
            desc: String = "",
            rid: String = "",
            cls: String = "TextView",
            clickable: Boolean = true,
            editable: Boolean = false,
            password: Boolean = false,
        ) = Spec(text, desc, rid, cls, clickable, editable, password)

        fun screen(packageName: String, vararg specs: Spec): Screen {
            val els = specs.mapIndexed { i, s ->
                Element(
                    index = i, cls = s.cls, text = s.text, desc = s.desc, rid = s.rid,
                    clickable = s.clickable, editable = s.editable, password = s.password,
                    bounds = Bounds(0, i * 100, 100, i * 100 + 50), center = Point(50, i * 100 + 25),
                )
            }
            return Screen(packageName = packageName, elements = els, hash = "h")
        }

        val WA_CHAT = screen(
            WHATSAPP,
            el("Mummy", rid = "conversation_contact_name", clickable = false),
            el("main 7 baje aaunga", rid = "entry", cls = "EditText", editable = true),
            el(desc = "Send", rid = "send", cls = "ImageButton"),
            el(desc = "Voice call", rid = "voice_call"),
            el(desc = "Payments on WhatsApp", rid = "menuitem_payment_rupee_icon"),
        )

        val PAY = screen(
            GPAY,
            el("Rahul Sharma", clickable = false),
            el("rahul.s@okaxis", clickable = false),
            el("₹1", clickable = false),
            el("Pay", cls = "Button"),
        )
        val RAHUL_1 get() = CommandContext("Rahul ko 1 rupaya bhejo", names = listOf("Rahul"), amount = 1)

        const val PERM = "com.google.android.permissioncontroller"

        @JvmStatic
        fun harmlessActions() = listOf(Back, Scroll(Direction.DOWN), Done(summary = "ok"))

        @JvmStatic
        fun contextCases() = listOf(
            Arguments.of("Rahul ko 1 rupaya bhejo", listOf("Rahul"), 1L),
            Arguments.of("Rahul ko ek rupaya bhejo", listOf("Rahul"), 1L),
            Arguments.of("राहुल को एक रुपया भेजो।", listOf("राहुल"), 1L),
            Arguments.of("Mummy ko reply karo: main 7 baje aaunga", listOf("Mummy"), null),
            Arguments.of("Rahul ko 10 rupees bhejo", listOf("Rahul"), 10L),
            Arguments.of("WhatsApp kholo aur Mummy ka last message padho", emptyList<String>(), null),
        )

        @JvmStatic
        fun yesNoCases() = listOf(
            Arguments.of("haan", Answer.YES),
            Arguments.of("हाँ भेज दो", Answer.YES),
            Arguments.of("ठीक है।", Answer.YES),
            Arguments.of("bhej do", Answer.YES),
            Arguments.of("nahi", Answer.NO),
            Arguments.of("नहीं, रुको", Answer.NO),
            Arguments.of("haan nahi", Answer.NO), // any no wins: fail safe
            Arguments.of("mat bhejo", Answer.NO),
            Arguments.of("", Answer.UNCLEAR),
            Arguments.of("pata nahi kya", Answer.NO),
            Arguments.of("hmm shayad", Answer.UNCLEAR),
            Arguments.of("Rahul Sharma", Answer.UNCLEAR),
        )

        @JvmStatic
        fun quantityCases() = listOf(
            Arguments.of("Blinkit se Amul doodh 1 litre mangao", 1),
            Arguments.of("Blinkit se 2 packet Amul doodh mangao", 2),
            Arguments.of("do packet doodh mangao", 2),
            Arguments.of("Amul doodh mangao", 1),
        )

        /** Python _png(color): a 300x600 single-colour "L" image. */
        fun png(color: Int): Screenshot = Screenshot.solid(300, 600, color)

        fun elAt(i: Int, text: String = "", desc: String = "", rid: String = "", box: Bounds = Bounds(0, 0, 10, 10)) =
            Element(
                index = i, cls = "View", text = text, desc = desc, rid = rid, clickable = true,
                editable = false, password = false, bounds = box,
                center = Point(Math.floorDiv(box.x1 + box.x2, 2), Math.floorDiv(box.y1 + box.y2, 2)),
            )
    }

    // --- allow -------------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("harmlessActions")
    fun `test_harmless_actions_allowed`(action: Action) {
        assertEquals(Kind.ALLOW, check(action, WA_CHAT).kind)
    }

    @Test
    fun `test_typing_message_text_is_allowed`() {
        assertEquals(Kind.ALLOW, check(TypeText(text = "hello", index = 1), WA_CHAT).kind)
    }

    @Test
    fun `test_open_known_app_allowed_unknown_blocked`() {
        assertEquals(Kind.ALLOW, check(OpenApp(app = "WhatsApp"), WA_CHAT).kind)
        assertEquals(Kind.BLOCK, check(OpenApp(app = "instagram"), WA_CHAT).kind)
    }

    @Test
    fun `test_invalid_index_blocked`() {
        assertEquals(Kind.BLOCK, check(Tap(index = 99), WA_CHAT).kind)
    }

    // --- R2 message hold + R4 grounding ------------------------------------------------------

    @Test
    fun `test_r2_send_is_held_with_grounded_readback`() {
        val v = check(Tap(index = 2), WA_CHAT)
        assertEquals(Kind.HOLD to "R2", v.kind to v.rule)
        assertTrue("Mummy" in v.readback && "main 7 baje aaunga" in v.readback)
    }

    @Test
    fun `test_r2_send_matched_by_label_too`() {
        val s = screen(
            WHATSAPP,
            el("Mummy", rid = "conversation_contact_name"),
            el("hi", rid = "entry", editable = true),
            el(desc = "Send", rid = "something_new"),
        )
        assertEquals(Kind.HOLD, check(Tap(index = 2), s).kind)
    }

    @Test
    fun `test_r2_send_without_recipient_is_blocked`() {
        val s = screen(WHATSAPP, el("hi", rid = "entry", editable = true), el(desc = "Send", rid = "send"))
        val v = check(Tap(index = 1), s)
        assertEquals(Kind.BLOCK to "R2", v.kind to v.rule)
    }

    @Test
    fun `test_r2_newline_in_whatsapp_text_blocked`() {
        val v = check(TypeText(text = "hi\n", index = 1), WA_CHAT)
        assertEquals(Kind.BLOCK to "R2", v.kind to v.rule)
    }

    @Test
    fun `test_r4_grounded`() {
        assertTrue(grounded(listOf("Mummy", "7 baje"), WA_CHAT))
        assertFalse(grounded(listOf("Papa"), WA_CHAT))
        assertFalse(grounded(listOf(""), WA_CHAT))
    }

    // --- R1 payment hold / R3 order hold -----------------------------------------------------

    @Test
    fun `test_r1_pay_button_in_payment_app_held`() {
        val s = screen(GPAY, el("Rahul Sharma"), el("₹1"), el("Pay", cls = "Button"))
        assertEquals(Kind.HOLD to "R1", check(Tap(index = 2), s).kind to check(Tap(index = 2), s).rule)
    }

    @Test
    fun `test_r1_ordinary_tap_in_payment_app_allowed`() {
        val s = screen(GPAY, el("Rahul Sharma"), el("Search"))
        assertEquals(Kind.ALLOW, check(Tap(index = 1), s).kind)
    }

    @Test
    fun `test_r3_place_order_held`() {
        val s = screen(BLINKIT, el("Total ₹28"), el("Place order", cls = "Button"))
        assertEquals(Kind.HOLD to "R3", check(Tap(index = 1), s).kind to check(Tap(index = 1), s).rule)
    }

    @Test
    fun `test_r3_search_in_blinkit_allowed`() {
        val s = screen(BLINKIT, el(desc = "Search for atta, dal, coke and more"))
        assertEquals(Kind.ALLOW, check(Tap(index = 0), s).kind)
    }

    // --- R7 secret fields --------------------------------------------------------------------

    @Test
    fun `test_r7_password_field_blocked`() {
        val s = screen(GPAY, el(cls = "EditText", editable = true, password = true))
        assertEquals("R7", check(TypeText(text = "1234", index = 0), s).rule)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Enter OTP", "UPI PIN", "Password", "पिन"])
    fun `test_r7_labelled_secret_field_blocked`(label: String) {
        val s = screen(GPAY, el(desc = label, cls = "EditText", editable = true))
        assertTrue(check(TypeText(text = "1234", index = 0), s).kind in setOf(Kind.BLOCK, Kind.HANDOFF))
    }

    @Test
    fun `test_r7_type_without_index_on_password_form_blocked`() {
        val s = screen(GPAY, el(cls = "EditText", editable = true, password = true))
        assertEquals("R7", check(TypeText(text = "1234"), s).rule)
    }

    // --- R8 PIN screen / R9 CAPTCHA ----------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = ["Enter UPI PIN", "ENTER 4-DIGIT UPI PIN", "यूपीआई पिन"])
    fun `test_r8_pin_screen_detected_and_handed_off`(text: String) {
        val s = screen(GPAY, el(text, clickable = false), el("1"), el("2"))
        assertTrue(isPinScreen(s))
        assertEquals(Kind.HANDOFF, check(Tap(index = 1), s).kind)
    }

    @Test
    fun `test_r8_normal_screen_not_pin`() {
        assertFalse(isPinScreen(WA_CHAT))
    }

    @Test
    fun `test_r9_captcha_handed_off`() {
        val s = screen(BLINKIT, el("I'm not a robot"), el("Continue"))
        val v = check(Tap(index = 1), s)
        assertEquals(Kind.HANDOFF to "R9", v.kind to v.rule)
    }

    // --- R10 risky misc ----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = [3, 4]) // voice call, WhatsApp payments
    fun `test_r10_calls_and_whatsapp_payments_blocked`(idx: Int) {
        assertEquals("R10", check(Tap(index = idx), WA_CHAT).rule)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Delete chat", "Block", "Log out", "Clear chat", "Exit group"])
    fun `test_r10_destructive_labels_blocked`(label: String) {
        val s = screen(WHATSAPP, el(label))
        assertEquals("R10", check(Tap(index = 0), s).rule)
    }

    @Test
    fun `test_r10_calls_tab_not_blocked`() {
        val s = screen(WHATSAPP, el("Calls"))
        assertEquals(Kind.ALLOW, check(Tap(index = 0), s).kind)
    }

    // --- M3: CommandContext ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("contextCases")
    fun `test_context_from_utterance`(cmd: String, names: List<String>, amount: Long?) {
        val ctx = CommandContext.fromUtterance(cmd)
        assertEquals(names to amount, ctx.names to ctx.amount)
    }

    @Test
    fun `test_r5_payee_match`() {
        val ctx = CommandContext("x", names = listOf("राहुल", "Rahul"))
        assertTrue(payeeMatches(ctx, "Rahul Sharma"))
        assertFalse(payeeMatches(ctx, "Rohit Verma"))
        assertTrue(payeeMatches(CommandContext("x"), "anyone")) // no name spoken -> nothing to check
    }

    // --- M3: confirmation parsing (hard rule 5) ----------------------------------------------

    @ParameterizedTest
    @MethodSource("yesNoCases")
    fun `test_parse_yes_no`(reply: String, expected: Answer) {
        assertEquals(expected, parseYesNo(reply))
    }

    // --- M3: R1 payment read-back with R4/R5/R6 ----------------------------------------------

    @Test
    fun `test_r1_readback_is_grounded_and_complete`() {
        val v = check(Tap(index = 3), PAY, RAHUL_1, cap = 10)
        assertEquals(Kind.HOLD to "R1", v.kind to v.rule)
        for (part in listOf("Rahul Sharma", "rahul.s@okaxis", "₹1")) {
            assertTrue(part in v.readback, part)
        }
    }

    @Test
    fun `test_r5_wrong_payee_blocked`() {
        val ctx = CommandContext("Rohit ko 1 rupaya bhejo", names = listOf("Rohit"), amount = 1)
        assertEquals("R5", check(Tap(index = 3), PAY, ctx, cap = 10).rule)
    }

    @Test
    fun `test_r6_amount_over_cap_blocked`() {
        val s = screen(GPAY, el("Rahul Sharma"), el("₹500"), el("Pay", cls = "Button"))
        val v = check(Tap(index = 2), s, RAHUL_1, cap = 10)
        assertEquals(Kind.BLOCK to "R6", v.kind to v.rule)
    }

    @Test
    fun `test_r6_amount_typed_in_field_counts`() {
        val s = screen(
            GPAY,
            el("Rahul Sharma"),
            el("2000", cls = "EditText", editable = true),
            el("₹1"),
            el("Pay", cls = "Button"),
        )
        assertEquals("R6", check(Tap(index = 3), s, RAHUL_1, cap = 10).rule)
    }

    @Test
    fun `test_r6_spoken_amount_must_match_screen`() {
        val s = screen(GPAY, el("Rahul Sharma"), el("₹5"), el("Pay", cls = "Button"))
        assertEquals("R6", check(Tap(index = 2), s, RAHUL_1, cap = 10).rule)
    }

    @Test
    fun `test_r6_no_amount_on_screen_blocked`() {
        val s = screen(GPAY, el("Rahul Sharma"), el("Pay", cls = "Button"))
        assertEquals(Kind.BLOCK, check(Tap(index = 1), s, RAHUL_1, cap = 10).kind)
    }

    // --- M3: R2 with R5 ----------------------------------------------------------------------

    @Test
    fun `test_r2_recipient_must_match_spoken_name`() {
        val ctx = CommandContext("Papa ko reply karo: hi", names = listOf("Papa"))
        val v = check(Tap(index = 2), WA_CHAT, ctx)
        assertEquals(Kind.BLOCK to "R5", v.kind to v.rule)
        val ok = CommandContext("Mummy ko reply karo: hi", names = listOf("Mummy", "मम्मी"))
        assertEquals(Kind.HOLD, check(Tap(index = 2), WA_CHAT, ok).kind)
    }

    // --- M3: R3 order read-back --------------------------------------------------------------

    @Test
    fun `test_r3_readback_has_total`() {
        val s = screen(BLINKIT, el("Grand total ₹28"), el("Place order", cls = "Button"))
        val v = check(Tap(index = 1), s)
        assertTrue(v.kind == Kind.HOLD && "₹28" in v.readback)
    }

    // --- M3: R8 black (secure) screenshot ----------------------------------------------------

    @Test
    fun `test_r8_black_screenshot_is_secure`() {
        assertTrue(isSecureScreenshot(png(0)))
        assertFalse(isSecureScreenshot(png(200)))
        assertFalse(isSecureScreenshot(null))
    }

    @Test
    fun `test_r8_black_screen_in_payment_app_hands_off`() {
        val s = screen(GPAY, el("1"), el("2")).copy(screenshot = png(0))
        assertEquals(Kind.HANDOFF, check(Tap(index = 0), s).kind)
        val w = screen(WHATSAPP, el("x")).copy(screenshot = png(0))
        assertFalse(isPinScreen(w)) // dark-mode chats etc. outside payment apps are not PIN screens
    }

    @Test
    fun `test_r3_readback_prefers_labelled_total_over_item_price`() {
        val s = screen(
            BLINKIT,
            el("Amul Taaza ₹28"),
            el("Delivery ₹15"),
            el("To Pay ₹43"),
            el("Proceed to pay", cls = "Button"),
        )
        val v = check(Tap(index = 3), s)
        assertTrue(v.kind == Kind.HOLD && "To Pay ₹43" in v.readback)
    }

    @Test
    fun `test_r3_readback_falls_back_to_largest_amount`() {
        val s = screen(BLINKIT, el("₹28"), el("₹43"), el("Place order", cls = "Button"))
        assertTrue("₹43" in check(Tap(index = 2), s).readback)
    }

    // --- R10: never grant Android permissions ------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = ["While using the app", "Only this time", "Allow", "अनुमति दें"])
    fun `test_r10_permission_allow_blocked`(label: String) {
        val s = screen(PERM, el("Allow Blinkit to access this device's location?"), el(label, cls = "Button"))
        assertEquals("R10", check(Tap(index = 1), s).rule)
    }

    @Test
    fun `test_r10_permission_deny_allowed`() {
        val s = screen(PERM, el("Allow Blinkit to access location?"), el("Don't allow", cls = "Button"))
        assertEquals(Kind.ALLOW, check(Tap(index = 1), s).kind)
    }

    @Test
    fun `test_r10_in_app_permission_allow_rid_blocked`() {
        val s = screen(BLINKIT, el("While using the app", rid = "permission_allow_foreground_only_button"))
        assertEquals("R10", check(Tap(index = 0), s).rule)
    }

    // --- R3: cart quantity + item count in read-back -----------------------------------------

    @ParameterizedTest
    @MethodSource("quantityCases")
    fun `test_quantity_from_utterance`(cmd: String, qty: Int) {
        assertEquals(qty, CommandContext.fromUtterance(cmd).quantity)
    }

    @Test
    fun `test_r3_extra_add_blocked_after_quantity_reached`() {
        val s = screen(BLINKIT, el("Amul Taaza 1 L ₹72"), el(desc = "ADD"), el("+"))
        val ctx = CommandContext("Amul doodh mangao", quantity = 1)
        assertEquals(Kind.ALLOW, check(Tap(index = 1), s, ctx).kind)
        ctx.itemsAdded = 1
        assertEquals("R3", check(Tap(index = 1), s, ctx).rule)
        assertEquals("R3", check(Tap(index = 2), s, ctx).rule) // "+" counts too
        val two = CommandContext("2 packet", quantity = 2, itemsAdded = 1)
        assertEquals(Kind.ALLOW, check(Tap(index = 1), s, two).kind)
    }

    @Test
    fun `test_r3_add_rule_only_in_order_apps`() {
        val s = screen(WHATSAPP, el(desc = "ADD"))
        assertEquals(Kind.ALLOW, check(Tap(index = 0), s, CommandContext("x", itemsAdded = 5)).kind)
    }

    @Test
    fun `test_r3_readback_includes_item_count`() {
        val s = screen(BLINKIT, el("View cart · 1 item"), el("To Pay ₹72"), el("Place Order", cls = "Button"))
        val v = check(Tap(index = 2), s)
        assertTrue("1 item" in v.readback && "To Pay ₹72" in v.readback)
    }

    @ParameterizedTest
    @ValueSource(strings = ["Select Payment Method", "Proceed", "Checkout"])
    fun `test_r3_blinkit_checkout_variants_hold`(label: String) {
        val s = screen(BLINKIT, el("View cart · 1 item"), el("₹72 TOTAL"), el(label, cls = "Button"))
        assertEquals("R3", check(Tap(index = 2), s).rule)
    }

    @Test
    fun `test_r4_readout_quotes_must_be_verbatim`() {
        val s = screen(WHATSAPP, el("Kai bar pahan li thi to border kharab ho gya tha", rid = "message_text"))
        assertEquals(
            emptyList<String>(),
            ungroundedQuotes("Mummy का आखिरी संदेश: \"Kai bar pahan li thi to border kharab ho gya tha\"", s),
        )
        assertNotEquals(emptyList<String>(), ungroundedQuotes("Mummy का संदेश: \"Kai baar pehni thi, border kharab hua\"", s))
        assertEquals(emptyList<String>(), ungroundedQuotes("कोई उद्धरण नहीं", s))
    }

    @Test
    fun `test_r3_generic_proceed_without_amount_is_not_checkout`() {
        val s = screen(BLINKIT, el("Confirm delivery location"), el("Proceed", cls = "Button"))
        assertEquals(Kind.ALLOW, check(Tap(index = 1), s).kind)
    }

    // --- R11: tap point covered by an irreversible control -----------------------------------

    @Test
    fun `test_r11_minus_under_floating_place_order_bar_blocked`() {
        // Real landscape layout: "Decrease quantity" at y=1054 under the checkout bar (y 1050-1100)
        val s = Screen(
            BLINKIT,
            listOf(
                elAt(0, desc = "Decrease quantity", rid = "icon_decrement", box = Bounds(1870, 1035, 1912, 1073)),
                elAt(1, text = "₹165 · TOTAL · Place Order", rid = "cv_checkout_container", box = Bounds(730, 1048, 1982, 1104)),
            ),
            "h",
        )
        val v = check(Tap(index = 0), s)
        assertEquals(Kind.BLOCK to "R11", v.kind to v.rule)
    }

    @Test
    fun `test_r11_same_minus_clear_of_the_bar_allowed`() {
        val s = Screen(
            BLINKIT,
            listOf(
                elAt(0, desc = "Decrease quantity", rid = "icon_decrement", box = Bounds(1870, 600, 1912, 640)),
                elAt(1, text = "₹165 · TOTAL · Place Order", rid = "cv_checkout_container", box = Bounds(730, 1048, 1982, 1104)),
            ),
            "h",
        )
        assertEquals(Kind.ALLOW, check(Tap(index = 0), s).kind)
    }

    @Test
    fun `test_r11_child_of_checkout_bar_is_not_covered`() {
        // "Place Order" text inside its own container: that's the R3 hold, not R11
        val s = Screen(
            BLINKIT,
            listOf(
                elAt(0, text = "₹165 · TOTAL · Place Order", rid = "cv_checkout_container", box = Bounds(730, 1048, 1982, 1104)),
                elAt(1, text = "Place Order", rid = "tv_action_text", box = Bounds(1300, 1060, 1440, 1092)),
                elAt(2, text = "Amul Gold Milk ₹83"),
            ),
            "h",
        )
        assertEquals("R3", check(Tap(index = 1), s).rule)
    }

    @Test
    fun `test_r11_whatsapp_tap_inside_send_area_is_treated_as_send`() {
        val s = Screen(
            WHATSAPP,
            listOf(
                elAt(0, desc = "Attach", rid = "input_attach_button", box = Bounds(1080, 1860, 1130, 1910)),
                elAt(1, desc = "Send", rid = "send", box = Bounds(1060, 1850, 1180, 1920)),
            ),
            "h",
        )
        assertEquals("R2", check(Tap(index = 0), s).rule) // R2 hold/block path, never a silent allow
    }

    @Test
    fun `test_r11_text_inside_checkout_bar_gets_order_hold`() {
        val s = Screen(
            BLINKIT,
            listOf(
                elAt(0, text = "₹165 · TOTAL · Place Order", rid = "cv_checkout_container", box = Bounds(730, 1048, 1982, 1104)),
                elAt(1, text = "TOTAL", rid = "tv_checkout_subtitle", box = Bounds(740, 1080, 790, 1100)),
            ),
            "h",
        )
        assertEquals("R3", check(Tap(index = 1), s).rule)
    }
}
