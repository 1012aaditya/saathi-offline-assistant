"""Deterministic safety gate, rules R1-R10 (SYSTEM_DESIGN section 6). Never an LLM decision.

check() runs on EVERY action before execution and returns allow / hold / block / handoff.
  hold    -> irreversible step (pay, send, order): needs a grounded read-back + explicit yes.
  block   -> never execute; tell the user.
  handoff -> stop acting and give control back to the user (PIN, CAPTCHA).

Implemented: R1 R2 R3 R4 R7 R8 R9 R10 + index/app validation.
M3 adds: R5 payee fuzzy match, R6 amount cap, R8 black-screenshot detection.
"""

import re
from dataclasses import dataclass
from typing import Literal

from saathi.actions import Action, OpenApp, Tap, TypeText
from saathi.config import APP_ALIASES, ORDER_APPS, PAYMENT_APPS, WHATSAPP
from saathi.device import Element, Screen

PAY_REGEX = re.compile(r"(?i)\b(pay|send|proceed|confirm|place order)\b|भेजें|पे करें|भुगतान|₹")
ORDER_REGEX = re.compile(r"(?i)place order|\bpay\b|slide to pay|ऑर्डर करें")
SECRET_REGEX = re.compile(r"(?i)\b(pin|otp|password|passcode|cvv|mpin)\b|पिन|ओटीपी|पासवर्ड")
PIN_SCREEN_REGEX = re.compile(r"(?i)upi pin|enter (your )?pin|enter mpin|पिन (डालें|दर्ज)|यूपीआई पिन")
CAPTCHA_REGEX = re.compile(r"(?i)captcha|i'?m not a robot|verify you are human")
RISKY_REGEX = re.compile(
    r"(?i)\b(delete|uninstall|log ?out|sign ?out|clear (chat|history|data)|block|report|"
    r"exit group|reset|remove|voice call|video call|call)\b|payments? on whatsapp|send payment|"
    r"हटाएं|डिलीट"
)
WHATSAPP_SEND_RIDS = {"send", "send_btn"}
WHATSAPP_SEND_LABELS = {"send", "भेजें"}

Kind = Literal["allow", "hold", "block", "handoff"]


@dataclass(frozen=True)
class Verdict:
    kind: Kind
    rule: str = ""
    message: str = ""  # spoken to the user (Hindi, Devanagari) on block / handoff
    readback: str = ""  # spoken before an irreversible step (hold)


ALLOW = Verdict("allow")


def _label(e: Element) -> str:
    return f"{e.text} {e.desc}".strip()


def _screen_texts(screen: Screen) -> list[str]:
    return [t for e in screen.elements for t in (e.text, e.desc) if t]


def is_pin_screen(screen: Screen) -> bool:
    """R8: PIN entry screen -> stop everything and hand off."""
    return any(PIN_SCREEN_REGEX.search(t) for t in _screen_texts(screen))


def is_captcha(screen: Screen) -> bool:
    """R9."""
    return any(CAPTCHA_REGEX.search(t) for t in _screen_texts(screen))


def grounded(parts: list[str], screen: Screen) -> bool:
    """R4: every read-back string must appear verbatim in the current screen."""
    texts = _screen_texts(screen)
    return all(p and any(p in t for t in texts) for p in parts)


def _whatsapp_send_readback(screen: Screen) -> Verdict:
    """R2: read back recipient + message text, both taken verbatim from the screen."""
    by_rid = {e.rid: e for e in screen.elements}
    recipient = by_rid.get("conversation_contact_name")
    entry = by_rid.get("entry")
    if not recipient or not recipient.text or not entry or not entry.text:
        return Verdict("block", "R2", "संदेश या नाम स्क्रीन पर साफ़ नहीं है, मैं रुक रहा हूँ।")
    parts = [recipient.text, entry.text]
    if not grounded(parts, screen):
        return Verdict("block", "R4", "पढ़कर सुनाने वाली बात स्क्रीन पर नहीं मिली।")
    return Verdict("hold", "R2", readback=f"{recipient.text} को भेज रहे हैं: '{entry.text}'. पक्का?")


def check(action: Action, screen: Screen) -> Verdict:
    if is_pin_screen(screen):
        return Verdict("handoff", "R8", "अपना पिन खुद डालिए, मैं रुक रहा हूँ।")
    if is_captcha(screen):
        return Verdict("handoff", "R9", "यह कैप्चा आपको खुद भरना होगा।")

    target: Element | None = None
    if isinstance(action, (Tap, TypeText)) and action.index is not None:
        if action.index >= len(screen.elements):
            return Verdict("block", "index", "यह बटन स्क्रीन पर नहीं मिला।")
        target = screen.elements[action.index]

    if isinstance(action, OpenApp) and action.app.lower().strip() not in APP_ALIASES:
        return Verdict("block", "app", "यह ऐप मेरी सूची में नहीं है।")

    if isinstance(action, TypeText):
        # R7: never type into secret fields (also covers "type" with no index on a password form).
        if target is not None and (target.password or SECRET_REGEX.search(_label(target))):
            return Verdict("block", "R7", "पिन, ओटीपी या पासवर्ड मैं नहीं डालूँगा।")
        if target is None and any(e.password for e in screen.elements if e.editable):
            return Verdict("block", "R7", "पिन, ओटीपी या पासवर्ड मैं नहीं डालूँगा।")
        # R2: a newline can send the message when WhatsApp's "Enter is send" is on.
        if screen.package == WHATSAPP and "\n" in action.text:
            return Verdict("block", "R2", "संदेश भेजने से पहले आपसे पूछूँगा।")
        return ALLOW

    if isinstance(action, Tap):
        assert target is not None
        label = _label(target)
        # R2 message hold
        if screen.package == WHATSAPP and (
            target.rid in WHATSAPP_SEND_RIDS
            or target.desc.lower() in WHATSAPP_SEND_LABELS
            or target.text.lower() in WHATSAPP_SEND_LABELS
        ):
            return _whatsapp_send_readback(screen)
        # R1 payment hold (read-back details + R5/R6 land in M3; until then this cancels)
        if screen.package in PAYMENT_APPS and PAY_REGEX.search(label):
            return Verdict("hold", "R1", readback=f"'{label}' दबाने से पैसे जा सकते हैं। पक्का?")
        # R3 order hold
        if screen.package in ORDER_APPS and ORDER_REGEX.search(label):
            return Verdict("hold", "R3", readback=f"'{label}' दबाने से ऑर्डर हो जाएगा। पक्का?")
        # R10 risky misc
        if RISKY_REGEX.search(label):
            return Verdict("block", "R10", "यह काम आपकी बात का हिस्सा नहीं है, मैं नहीं करूँगा।")
    return ALLOW
