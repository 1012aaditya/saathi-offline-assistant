"""Deterministic safety gate, rules R1-R10 (SYSTEM_DESIGN section 6). Never an LLM decision.

check() runs on EVERY action before execution and returns allow / hold / block / handoff.
  hold    -> irreversible step (pay, send, order): needs a grounded read-back + explicit yes.
  block   -> never execute; tell the user.
  handoff -> stop acting and give control back to the user (PIN, CAPTCHA).

Also here (pure, no I/O): CommandContext (who / how much the user asked for), the spoken
yes/no parser for confirmations, and the black-screenshot (secure screen) detector.
"""

import io
import re
from dataclasses import dataclass, field
from typing import Literal

from rapidfuzz import fuzz

from saathi.actions import Action, OpenApp, Search, Tap, TypeText
from saathi.config import APP_ALIASES, ORDER_APPS, PAYMENT_APPS, WHATSAPP
from saathi.device import Element, Screen

PAY_REGEX = re.compile(r"(?i)\b(pay|send|proceed|confirm|place order)\b|भेजें|पे करें|भुगतान|₹")
# Checkout buttons = the stop-and-confirm point. "Select Payment Method" is Blinkit's checkout
# button when no payment method is saved (seen on device, 12 Oct).
ORDER_REGEX = re.compile(r"(?i)place order|\bpay\b|slide to pay|select payment method|ऑर्डर करें")
# Generic words also used on non-order screens (Blinkit's location sheet has "Proceed"):
# only a checkout when a ₹ amount is on screen.
ORDER_GENERIC_REGEX = re.compile(r"(?i)\b(proceed|checkout|continue)\b")
SECRET_REGEX = re.compile(r"(?i)\b(pin|otp|password|passcode|cvv|mpin)\b|पिन|ओटीपी|पासवर्ड")
PIN_SCREEN_REGEX = re.compile(r"(?i)upi pin|enter (your )?pin|enter mpin|पिन (डालें|दर्ज)|यूपीआई पिन")
CAPTCHA_REGEX = re.compile(r"(?i)captcha|i'?m not a robot|verify you are human")
RISKY_REGEX = re.compile(
    r"(?i)\b(delete|uninstall|log ?out|sign ?out|clear (chat|history|data)|block|report|"
    r"exit group|reset|remove|voice call|video call|call)\b|payments? on whatsapp|send payment|"
    r"हटाएं|डिलीट"
)
AMOUNT_REGEX = re.compile(r"₹\s?([\d,]+(?:\.\d{1,2})?)")
UPI_HANDLE_REGEX = re.compile(r"[\w.\-]{2,}@[a-zA-Z]{2,}")
# R10: never grant Android runtime permissions (location, contacts, mic...) on the user's behalf.
PERMISSION_PACKAGES = {
    "com.google.android.permissioncontroller",
    "com.android.permissioncontroller",
}
PERMISSION_ALLOW_REGEX = re.compile(
    r"(?i)^(allow|while using the app|only this time|allow all the time|allow only while using)"
    r"|अनुमति दें"
)
WHATSAPP_SEND_RIDS = {"send", "send_btn"}
WHATSAPP_SEND_LABELS = {"send", "भेजें"}
PAYEE_MATCH_MIN = 80  # rapidfuzz token_set_ratio (R5)
BLACK_PIXEL_MAX = 16  # luminance 0-255 counted as black
BLACK_SHARE_MIN = 0.95  # R8: a >= 95% black screenshot is a secure screen

Kind = Literal["allow", "hold", "block", "handoff"]


@dataclass(frozen=True)
class Verdict:
    kind: Kind
    rule: str = ""
    message: str = ""  # spoken to the user (Hindi, Devanagari) on block / handoff
    readback: str = ""  # spoken before an irreversible step (hold)


ALLOW = Verdict("allow")


# -- what the user asked for ----------------------------------------------------------------

_NUMBER_WORDS = {
    "ek": 1, "एक": 1, "do": 2, "दो": 2, "teen": 3, "तीन": 3, "char": 4, "chaar": 4, "चार": 4,
    "paanch": 5, "panch": 5, "पांच": 5, "पाँच": 5, "chhe": 6, "छह": 6, "saat": 7, "सात": 7,
    "aath": 8, "आठ": 8, "nau": 9, "नौ": 9, "das": 10, "दस": 10,
}  # fmt: skip
_NAME_BEFORE_KO = re.compile(r"([^\s,.:।]+)\s+(?:ko|को)(?=\s|$)", re.IGNORECASE)
_AMOUNT_SPOKEN = re.compile(
    r"(?:₹\s?)?(\d+|[^\s,.:।]+)\s*(?:rupaye?|rupay[ae]?|rupees?|rs\.?|रुपये|रुपया|रुपए)",
    re.IGNORECASE,
)


_QUANTITY_SPOKEN = re.compile(
    r"([^\s,.:।]+)\s*(?:packets?|packs?|pcs|pieces?|bottles?|पैकेट|बोतल)", re.IGNORECASE
)
ADD_BUTTON_REGEX = re.compile(r"(?i)^(add|\+|add to cart|increase quantity|जोड़ें)$")


def is_add_button(e: Element, package: str) -> bool:
    """A Blinkit-style ADD / + button that puts one more item in the cart."""
    return package in ORDER_APPS and bool(
        ADD_BUTTON_REGEX.match(e.text.strip()) or ADD_BUTTON_REGEX.match(e.desc.strip())
    )


@dataclass
class CommandContext:
    """Payee / recipient and amount extracted from the utterance, before any screen is seen.
    `names` may get extra spellings (e.g. a transliteration) so a Devanagari "राहुल" can match
    an on-screen "Rahul Sharma"."""

    command: str
    names: list[str] = field(default_factory=list)
    amount: int | None = None
    quantity: int = 1  # items to add to a cart ("2 packet"); sizes like "1 litre" don't count
    items_added: int = 0  # updated by the agent after each executed ADD / + tap

    @classmethod
    def from_utterance(cls, command: str) -> "CommandContext":
        names = [m.group(1) for m in _NAME_BEFORE_KO.finditer(command)][:1]
        amount = None
        m = _AMOUNT_SPOKEN.search(command)
        if m:
            tok = m.group(1).lower()
            amount = int(tok) if tok.isdigit() else _NUMBER_WORDS.get(tok)
        quantity = 1
        q = _QUANTITY_SPOKEN.search(command)
        if q:
            tok = q.group(1).lower()
            quantity = int(tok) if tok.isdigit() else _NUMBER_WORDS.get(tok, 1)
        return cls(command=command, names=names, amount=amount, quantity=max(1, quantity))


def payee_matches(ctx: CommandContext, on_screen: str) -> bool:
    """R5: fuzzy match between any spoken spelling and the on-screen name. No name -> True."""
    if not ctx.names:
        return True
    target = on_screen.lower()
    return any(fuzz.token_set_ratio(n.lower(), target) >= PAYEE_MATCH_MIN for n in ctx.names)


# -- confirmation --------------------------------------------------------------------------

YES = {"haan", "haa", "ha", "han", "yes", "confirm", "हाँ", "हां", "हा", "जी", "ok", "okay"}
YES_PHRASES = {"kar do", "bhej do", "theek hai", "thik hai", "ठीक है", "भेज दो", "कर दो"}
NO = {
    "nahi", "nahin", "nai", "na", "no", "cancel", "ruko", "mat", "stop",
    "नहीं", "नही", "ना", "मत", "रुको", "रुकिए", "कैंसल",
}  # fmt: skip

Answer = Literal["yes", "no", "unclear"]


def parse_yes_no(reply: str) -> Answer:
    """Any 'no' word wins (fail safe); otherwise any 'yes' word/phrase; otherwise unclear."""
    tokens = re.findall(r"[^\s,.!?।]+", reply.lower())
    joined = " ".join(tokens)
    if any(t in NO for t in tokens):
        return "no"
    if any(t in YES for t in tokens) or any(p in joined for p in YES_PHRASES):
        return "yes"
    return "unclear"


# -- screen helpers ------------------------------------------------------------------------


def _label(e: Element) -> str:
    return f"{e.text} {e.desc}".strip()


def _screen_texts(screen: Screen) -> list[str]:
    return [t for e in screen.elements for t in (e.text, e.desc) if t]


def is_secure_screenshot(jpeg_or_png: bytes | None) -> bool:
    """R8: Android renders FLAG_SECURE windows (UPI PIN pads) black in screenshots."""
    if not jpeg_or_png:
        return False
    from PIL import Image

    img = Image.open(io.BytesIO(jpeg_or_png)).convert("L")
    img.thumbnail((200, 200))
    hist = img.histogram()
    return sum(hist[:BLACK_PIXEL_MAX]) / max(1, sum(hist)) >= BLACK_SHARE_MIN


def is_pin_screen(screen: Screen) -> bool:
    """R8: PIN entry screen (by text, or a black secure screenshot) -> stop and hand off."""
    return any(PIN_SCREEN_REGEX.search(t) for t in _screen_texts(screen)) or (
        screen.package in PAYMENT_APPS and is_secure_screenshot(screen.screenshot)
    )


def is_captcha(screen: Screen) -> bool:
    """R9."""
    return any(CAPTCHA_REGEX.search(t) for t in _screen_texts(screen))


_QUOTED = re.compile(r'["“”]([^"“”]{2,})["“”]')


def ungrounded_quotes(summary: str, screen: Screen) -> list[str]:
    """R4 for read-outs: text Saathi quotes to the user must be verbatim on screen."""
    texts = _screen_texts(screen)
    return [q for q in _QUOTED.findall(summary) if not any(q.strip() in t for t in texts)]


def grounded(parts: list[str], screen: Screen) -> bool:
    """R4: every read-back string must appear verbatim in the current screen."""
    texts = _screen_texts(screen)
    return all(p and any(p in t for t in texts) for p in parts)


def screen_amounts(screen: Screen) -> list[float]:
    """Every ₹ amount on screen, plus a bare number typed into an editable amount field."""
    found: list[float] = []
    for e in screen.elements:
        for t in (e.text, e.desc):
            found += [float(a.replace(",", "")) for a in AMOUNT_REGEX.findall(t)]
        if e.editable and re.fullmatch(r"[\d,]+(?:\.\d{1,2})?", e.text):
            found.append(float(e.text.replace(",", "")))
    return found


# -- read-backs ----------------------------------------------------------------------------


def _whatsapp_send(screen: Screen, ctx: CommandContext) -> Verdict:
    """R2 (+R4, R5): read back recipient + message text, both verbatim from the screen."""
    by_rid = {e.rid: e for e in screen.elements}
    recipient = by_rid.get("conversation_contact_name")
    entry = by_rid.get("entry")
    if not recipient or not recipient.text or not entry or not entry.text:
        return Verdict("block", "R2", "संदेश या नाम स्क्रीन पर साफ़ नहीं है, मैं रुक रहा हूँ।")
    if not payee_matches(ctx, recipient.text):
        return Verdict("block", "R5", f"यह चैट {recipient.text} की है, आपने किसी और का नाम लिया था।")
    if not grounded([recipient.text, entry.text], screen):
        return Verdict("block", "R4", "पढ़कर सुनाने वाली बात स्क्रीन पर नहीं मिली।")
    return Verdict("hold", "R2", readback=f'{recipient.text} को भेज रहे हैं: "{entry.text}". भेज दूँ?')


def _payment(screen: Screen, ctx: CommandContext, cap: int) -> Verdict:
    """R1 (+R4, R5, R6): payee + UPI handle + amount, each taken verbatim from the screen."""
    amounts = screen_amounts(screen)
    if not amounts:
        return Verdict("block", "R6", "स्क्रीन पर रकम नहीं दिखी, मैं रुक रहा हूँ।")
    if max(amounts) > cap:
        return Verdict("block", "R6", f"रकम {cap} रुपये की सीमा से ज़्यादा है, मैं नहीं भेजूँगा।")
    if ctx.amount is not None and float(ctx.amount) not in amounts:
        return Verdict("block", "R6", f"आपने {ctx.amount} रुपये बोले थे, स्क्रीन पर रकम अलग है।")
    texts = _screen_texts(screen)
    payee = next((t for t in texts if ctx.names and payee_matches(ctx, t)), None)
    if ctx.names and payee is None:
        return Verdict("block", "R5", "स्क्रीन पर वही नाम नहीं मिला जो आपने बोला था।")
    handle = next((m.group(0) for t in texts for m in [UPI_HANDLE_REGEX.search(t)] if m), "")
    amount_text = next((t for t in texts if AMOUNT_REGEX.search(t)), "")
    parts = [p for p in (payee, handle, amount_text) if p]
    if not amount_text or not grounded(parts, screen):
        return Verdict("block", "R4", "पढ़कर सुनाने वाली बात स्क्रीन पर नहीं मिली।")
    who = " ".join(p for p in (payee, handle) if p) or "इस व्यक्ति"
    return Verdict("hold", "R1", readback=f"{amount_text} {who} को भेज रहे हैं। पक्का?")


TOTAL_REGEX = re.compile(r"(?i)grand total|to pay|total|bill|कुल")


ITEM_COUNT_REGEX = re.compile(r"(?i)\b\d+\s+items?\b")


def _order(screen: Screen) -> Verdict:
    """R3: prefer the itemised Blinkit cart read-back; fall back to the on-screen total."""
    from saathi.blinkit import order_readback

    cart = order_readback(screen)
    if cart is not None:
        text, parts = cart
        if not grounded(parts, screen):
            return Verdict("block", "R4", "पढ़कर सुनाने वाली बात स्क्रीन पर नहीं मिली।")
        return Verdict("hold", "R3", readback=text)
    return _order_total(screen)


def _order_total(screen: Screen) -> Verdict:
    """R3 (+R4): read back the order total, verbatim from the screen. Prefers a text labelled
    total / to pay; otherwise the largest ₹ amount on screen (never a single item price)."""
    texts = [t for t in _screen_texts(screen) if AMOUNT_REGEX.search(t)]
    labelled = [t for t in texts if TOTAL_REGEX.search(t)]

    def value(t: str) -> float:
        return max(float(a.replace(",", "")) for a in AMOUNT_REGEX.findall(t))

    total = (labelled or sorted(texts, key=value, reverse=True) or [""])[0]
    if not total:
        return Verdict("block", "R3", "कुल रकम स्क्रीन पर नहीं दिखी, मैं रुक रहा हूँ।")
    count = next(
        (m.group(0) for t in _screen_texts(screen) for m in [ITEM_COUNT_REGEX.search(t)] if m), ""
    )
    what = f"{count}, {total}" if count and count not in total else total
    return Verdict("hold", "R3", readback=f"ऑर्डर: {what}. ऑर्डर कर दूँ?")


# -- the gate ------------------------------------------------------------------------------


def check(
    action: Action, screen: Screen, ctx: CommandContext | None = None, cap: int = 10
) -> Verdict:
    ctx = ctx or CommandContext(command="")
    if is_pin_screen(screen):
        return Verdict("handoff", "R8", "अपना पिन खुद डालिए, मैं रुक रहा हूँ।")
    if is_captcha(screen):
        return Verdict("handoff", "R9", "यह कैप्चा आपको खुद भरना होगा।")

    target: Element | None = None
    if isinstance(action, (Tap, TypeText)) and action.index is not None:
        if action.index >= len(screen.elements):
            return Verdict("block", "index", "यह बटन स्क्रीन पर नहीं मिला।")
        target = screen.elements[action.index]

    if isinstance(action, Search):
        return ALLOW  # only opens search results; nothing is bought or sent
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
        if screen.package == WHATSAPP and (
            target.rid in WHATSAPP_SEND_RIDS
            or target.desc.lower() in WHATSAPP_SEND_LABELS
            or target.text.lower() in WHATSAPP_SEND_LABELS
        ):
            return _whatsapp_send(screen, ctx)
        if screen.package in PAYMENT_APPS and PAY_REGEX.search(label):
            return _payment(screen, ctx, cap)
        if screen.package in ORDER_APPS and (
            ORDER_REGEX.search(label)
            or (ORDER_GENERIC_REGEX.search(label) and screen_amounts(screen))
        ):
            return _order(screen)
        # R3: never put more in the cart than the user asked for.
        if is_add_button(target, screen.package) and ctx.items_added >= ctx.quantity:
            return Verdict("block", "R3", f"{ctx.items_added} आइटम जोड़ दिया है, अब कार्ट खोल रहा हूँ।")
        if (
            screen.package in PERMISSION_PACKAGES or target.rid.startswith("permission_allow")
        ) and PERMISSION_ALLOW_REGEX.search(label):
            return Verdict("block", "R10", "मैं आपकी तरफ़ से कोई अनुमति नहीं दूँगा।")
        if RISKY_REGEX.search(label):
            return Verdict("block", "R10", "यह काम आपकी बात का हिस्सा नहीं है, मैं नहीं करूँगा।")
    return ALLOW
