"""Unit tests for saathi.safety rules R1-R10. Every rule change needs a test here (hard rule 1)."""

import pytest

from saathi.actions import Back, Done, OpenApp, Scroll, Tap, TypeText
from saathi.config import BLINKIT, GPAY, WHATSAPP
from saathi.device import Element, Screen
from saathi.safety import check, grounded, is_pin_screen


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
