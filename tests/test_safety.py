"""Unit tests for saathi.safety rules R1-R10. Every rule change needs a test here (hard rule 1)."""

import pytest

from saathi.actions import Back, Done, OpenApp, Scroll, Tap, TypeText
from saathi.config import BLINKIT, GPAY, WHATSAPP
from saathi.device import Element, Screen
from saathi.safety import (
    CommandContext,
    check,
    grounded,
    is_pin_screen,
    is_secure_screenshot,
    parse_yes_no,
    payee_matches,
)


def el(text="", desc="", rid="", cls="TextView", clickable=True, editable=False, password=False):
    return {
        "text": text,
        "desc": desc,
        "rid": rid,
        "cls": cls,
        "clickable": clickable,
        "editable": editable,
        "password": password,
    }


def screen(package: str, *specs: dict) -> Screen:
    els = [
        Element(index=i, bounds=(0, i * 100, 100, i * 100 + 50), center=(50, i * 100 + 25), **s)
        for i, s in enumerate(specs)
    ]
    return Screen(package=package, elements=els, hash="h")


WA_CHAT = screen(
    WHATSAPP,
    el("Mummy", rid="conversation_contact_name", clickable=False),
    el("main 7 baje aaunga", rid="entry", cls="EditText", editable=True),
    el(desc="Send", rid="send", cls="ImageButton"),
    el(desc="Voice call", rid="voice_call"),
    el(desc="Payments on WhatsApp", rid="menuitem_payment_rupee_icon"),
)


# --- allow ---------------------------------------------------------------------------------


@pytest.mark.parametrize("action", [Back(), Scroll(direction="down"), Done(summary="ok")])
def test_harmless_actions_allowed(action):
    assert check(action, WA_CHAT).kind == "allow"


def test_typing_message_text_is_allowed():
    assert check(TypeText(text="hello", index=1), WA_CHAT).kind == "allow"


def test_open_known_app_allowed_unknown_blocked():
    assert check(OpenApp(app="WhatsApp"), WA_CHAT).kind == "allow"
    assert check(OpenApp(app="instagram"), WA_CHAT).kind == "block"


def test_invalid_index_blocked():
    assert check(Tap(index=99), WA_CHAT).kind == "block"


# --- R2 message hold + R4 grounding ---------------------------------------------------------


def test_r2_send_is_held_with_grounded_readback():
    v = check(Tap(index=2), WA_CHAT)
    assert (v.kind, v.rule) == ("hold", "R2")
    assert "Mummy" in v.readback and "main 7 baje aaunga" in v.readback


def test_r2_send_matched_by_label_too():
    s = screen(
        WHATSAPP,
        el("Mummy", rid="conversation_contact_name"),
        el("hi", rid="entry", editable=True),
        el(desc="Send", rid="something_new"),
    )
    assert check(Tap(index=2), s).kind == "hold"


def test_r2_send_without_recipient_is_blocked():
    s = screen(WHATSAPP, el("hi", rid="entry", editable=True), el(desc="Send", rid="send"))
    v = check(Tap(index=1), s)
    assert (v.kind, v.rule) == ("block", "R2")


def test_r2_newline_in_whatsapp_text_blocked():
    v = check(TypeText(text="hi\n", index=1), WA_CHAT)
    assert (v.kind, v.rule) == ("block", "R2")


def test_r4_grounded():
    assert grounded(["Mummy", "7 baje"], WA_CHAT)
    assert not grounded(["Papa"], WA_CHAT)
    assert not grounded([""], WA_CHAT)


# --- R1 payment hold / R3 order hold --------------------------------------------------------


def test_r1_pay_button_in_payment_app_held():
    s = screen(GPAY, el("Rahul Sharma"), el("₹1"), el("Pay", cls="Button"))
    assert (check(Tap(index=2), s).kind, check(Tap(index=2), s).rule) == ("hold", "R1")


def test_r1_ordinary_tap_in_payment_app_allowed():
    s = screen(GPAY, el("Rahul Sharma"), el("Search"))
    assert check(Tap(index=1), s).kind == "allow"


def test_r3_place_order_held():
    s = screen(BLINKIT, el("Total ₹28"), el("Place order", cls="Button"))
    assert (check(Tap(index=1), s).kind, check(Tap(index=1), s).rule) == ("hold", "R3")


def test_r3_search_in_blinkit_allowed():
    s = screen(BLINKIT, el(desc="Search for atta, dal, coke and more"))
    assert check(Tap(index=0), s).kind == "allow"


# --- R7 secret fields -----------------------------------------------------------------------


def test_r7_password_field_blocked():
    s = screen(GPAY, el(cls="EditText", editable=True, password=True))
    assert check(TypeText(text="1234", index=0), s).rule == "R7"


@pytest.mark.parametrize("label", ["Enter OTP", "UPI PIN", "Password", "पिन"])
def test_r7_labelled_secret_field_blocked(label):
    s = screen(GPAY, el(desc=label, cls="EditText", editable=True))
    assert check(TypeText(text="1234", index=0), s).kind in ("block", "handoff")


def test_r7_type_without_index_on_password_form_blocked():
    s = screen(GPAY, el(cls="EditText", editable=True, password=True))
    assert check(TypeText(text="1234"), s).rule == "R7"


# --- R8 PIN screen / R9 CAPTCHA -------------------------------------------------------------


@pytest.mark.parametrize("text", ["Enter UPI PIN", "ENTER 4-DIGIT UPI PIN", "यूपीआई पिन"])
def test_r8_pin_screen_detected_and_handed_off(text):
    s = screen(GPAY, el(text, clickable=False), el("1"), el("2"))
    assert is_pin_screen(s)
    assert check(Tap(index=1), s).kind == "handoff"


def test_r8_normal_screen_not_pin():
    assert not is_pin_screen(WA_CHAT)


def test_r9_captcha_handed_off():
    s = screen(BLINKIT, el("I'm not a robot"), el("Continue"))
    v = check(Tap(index=1), s)
    assert (v.kind, v.rule) == ("handoff", "R9")


# --- R10 risky misc -------------------------------------------------------------------------


@pytest.mark.parametrize("idx", [3, 4])  # voice call, WhatsApp payments
def test_r10_calls_and_whatsapp_payments_blocked(idx):
    assert check(Tap(index=idx), WA_CHAT).rule == "R10"


@pytest.mark.parametrize("label", ["Delete chat", "Block", "Log out", "Clear chat", "Exit group"])
def test_r10_destructive_labels_blocked(label):
    s = screen(WHATSAPP, el(label))
    assert check(Tap(index=0), s).rule == "R10"


def test_r10_calls_tab_not_blocked():
    s = screen(WHATSAPP, el("Calls"))
    assert check(Tap(index=0), s).kind == "allow"


# --- M3: CommandContext -------------------------------------------------------------------


@pytest.mark.parametrize(
    "cmd,names,amount",
    [
        ("Rahul ko 1 rupaya bhejo", ["Rahul"], 1),
        ("Rahul ko ek rupaya bhejo", ["Rahul"], 1),
        ("राहुल को एक रुपया भेजो।", ["राहुल"], 1),
        ("Mummy ko reply karo: main 7 baje aaunga", ["Mummy"], None),
        ("Rahul ko 10 rupees bhejo", ["Rahul"], 10),
        ("WhatsApp kholo aur Mummy ka last message padho", [], None),
    ],
)
def test_context_from_utterance(cmd, names, amount):
    ctx = CommandContext.from_utterance(cmd)
    assert (ctx.names, ctx.amount) == (names, amount)


def test_r5_payee_match():
    ctx = CommandContext("x", names=["राहुल", "Rahul"])
    assert payee_matches(ctx, "Rahul Sharma")
    assert not payee_matches(ctx, "Rohit Verma")
    assert payee_matches(CommandContext("x"), "anyone")  # no name spoken -> nothing to check


# --- M3: confirmation parsing (hard rule 5) -----------------------------------------------


@pytest.mark.parametrize(
    "reply,expected",
    [
        ("haan", "yes"),
        ("हाँ भेज दो", "yes"),
        ("ठीक है।", "yes"),
        ("bhej do", "yes"),
        ("nahi", "no"),
        ("नहीं, रुको", "no"),
        ("haan nahi", "no"),  # any no wins: fail safe
        ("mat bhejo", "no"),
        ("", "unclear"),
        ("pata nahi kya", "no"),
        ("hmm shayad", "unclear"),
        ("Rahul Sharma", "unclear"),
    ],
)
def test_parse_yes_no(reply, expected):
    assert parse_yes_no(reply) == expected


# --- M3: R1 payment read-back with R4/R5/R6 -----------------------------------------------

PAY = screen(
    GPAY,
    el("Rahul Sharma", clickable=False),
    el("rahul.s@okaxis", clickable=False),
    el("₹1", clickable=False),
    el("Pay", cls="Button"),
)
RAHUL_1 = CommandContext("Rahul ko 1 rupaya bhejo", names=["Rahul"], amount=1)


def test_r1_readback_is_grounded_and_complete():
    v = check(Tap(index=3), PAY, RAHUL_1, cap=10)
    assert (v.kind, v.rule) == ("hold", "R1")
    for part in ("Rahul Sharma", "rahul.s@okaxis", "₹1"):
        assert part in v.readback


def test_r5_wrong_payee_blocked():
    ctx = CommandContext("Rohit ko 1 rupaya bhejo", names=["Rohit"], amount=1)
    assert check(Tap(index=3), PAY, ctx, cap=10).rule == "R5"


def test_r6_amount_over_cap_blocked():
    s = screen(GPAY, el("Rahul Sharma"), el("₹500"), el("Pay", cls="Button"))
    v = check(Tap(index=2), s, RAHUL_1, cap=10)
    assert (v.kind, v.rule) == ("block", "R6")


def test_r6_amount_typed_in_field_counts():
    s = screen(
        GPAY,
        el("Rahul Sharma"),
        el("2000", cls="EditText", editable=True),
        el("₹1"),
        el("Pay", cls="Button"),
    )
    assert check(Tap(index=3), s, RAHUL_1, cap=10).rule == "R6"


def test_r6_spoken_amount_must_match_screen():
    s = screen(GPAY, el("Rahul Sharma"), el("₹5"), el("Pay", cls="Button"))
    assert check(Tap(index=2), s, RAHUL_1, cap=10).rule == "R6"


def test_r6_no_amount_on_screen_blocked():
    s = screen(GPAY, el("Rahul Sharma"), el("Pay", cls="Button"))
    assert check(Tap(index=1), s, RAHUL_1, cap=10).kind == "block"


# --- M3: R2 with R5 -----------------------------------------------------------------------


def test_r2_recipient_must_match_spoken_name():
    ctx = CommandContext("Papa ko reply karo: hi", names=["Papa"])
    v = check(Tap(index=2), WA_CHAT, ctx)
    assert (v.kind, v.rule) == ("block", "R5")
    ok = CommandContext("Mummy ko reply karo: hi", names=["Mummy", "मम्मी"])
    assert check(Tap(index=2), WA_CHAT, ok).kind == "hold"


# --- M3: R3 order read-back ---------------------------------------------------------------


def test_r3_readback_has_total():
    s = screen(BLINKIT, el("Grand total ₹28"), el("Place order", cls="Button"))
    v = check(Tap(index=1), s)
    assert v.kind == "hold" and "₹28" in v.readback


# --- M3: R8 black (secure) screenshot -----------------------------------------------------


def _png(color: int) -> bytes:
    import io

    from PIL import Image

    buf = io.BytesIO()
    Image.new("L", (300, 600), color).save(buf, "PNG")
    return buf.getvalue()


def test_r8_black_screenshot_is_secure():
    assert is_secure_screenshot(_png(0))
    assert not is_secure_screenshot(_png(200))
    assert not is_secure_screenshot(None)


def test_r8_black_screen_in_payment_app_hands_off():
    s = screen(GPAY, el("1"), el("2"))
    s.screenshot = _png(0)
    assert check(Tap(index=0), s).kind == "handoff"
    w = screen(WHATSAPP, el("x"))
    w.screenshot = _png(0)  # dark-mode chats etc. outside payment apps are not PIN screens
    assert not is_pin_screen(w)


def test_r3_readback_prefers_labelled_total_over_item_price():
    s = screen(
        BLINKIT,
        el("Amul Taaza ₹28"),
        el("Delivery ₹15"),
        el("To Pay ₹43"),
        el("Proceed to pay", cls="Button"),
    )
    v = check(Tap(index=3), s)
    assert v.kind == "hold" and "To Pay ₹43" in v.readback


def test_r3_readback_falls_back_to_largest_amount():
    s = screen(BLINKIT, el("₹28"), el("₹43"), el("Place order", cls="Button"))
    assert "₹43" in check(Tap(index=2), s).readback
