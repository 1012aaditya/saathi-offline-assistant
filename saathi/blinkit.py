"""Deterministic Blinkit helper: product cards, ranking, cart, and scripted next steps.

Built against real screens (traces, 12 Oct): product cards are ViewGroups with
desc "<name> is available for ₹<price>[, <size>]"; inside each card an "ADD" button
(or "quantity N" once in the cart) and an "Ad" badge for sponsored items. The checkout
screen has title "Checkout" and a button "Place Order" / "Select Payment Method".

next_action() handles the predictable steps (popups, picking the product, cart, checkout)
so the model is only consulted for unexpected screens. Every action it returns still goes
through safety.check() in the agent.
"""

import re
from dataclasses import dataclass, field
from urllib.parse import quote

from rapidfuzz import fuzz

from saathi.actions import Action, AskUser, Back, Tap
from saathi.device import Element, Screen

SEARCH_URL = "https://blinkit.com/s/?q={q}"
_CARD = re.compile(r"^(?P<name>.+?) is available for (?P<price>₹[\d.,]+)(?:, (?P<size>.+))?$")
_QTY = re.compile(r"^quantity (\d+)$", re.IGNORECASE)
_SIZE = re.compile(
    r"(\d+(?:\.\d+)?)\s*(litres?|liters?|ltrs?|l|ml|kgs?|kg|g|gm|grams?|pcs|pieces?)\b",
    re.IGNORECASE,
)
_UNIT = {"l": ("ml", 1000), "ltr": ("ml", 1000), "litre": ("ml", 1000), "liter": ("ml", 1000),
         "ml": ("ml", 1), "kg": ("g", 1000), "g": ("g", 1), "gm": ("g", 1), "gram": ("g", 1),
         "pcs": ("pcs", 1), "piece": ("pcs", 1)}  # fmt: skip
CLEAR_MARGIN = 6  # score gap needed to pick without asking (FR-A3)


@dataclass
class Card:
    name: str
    price: str  # verbatim, e.g. "₹59"
    size: str  # verbatim, e.g. "1 ltr" ("" in the cart)
    card: Element
    add: Element | None  # ADD button inside the card
    qty: int  # already in cart
    ad: bool


@dataclass
class BlinkitState:
    query: str = ""  # English product query used for the search link
    answer: str = ""  # user's reply to the one clarification question
    asked: bool = False
    cart_start: int | None = None  # items in the cart when we first saw results
    cart_now: int = 0

    def observe_cart(self, screen: Screen) -> None:
        """Track the cart bar. Items count as added only when this number goes up: an ADD on
        a "2 options" card just opens a size picker (verified on device)."""
        n = cart_count(screen)
        if self.cart_start is None and cards(screen):
            self.cart_start = n or 0
        if n is not None:
            self.cart_now = n

    @property
    def added(self) -> int:
        return max(0, self.cart_now - (self.cart_start or 0))


_SHIPMENT = re.compile(r"Shipment of (\d+) items?", re.IGNORECASE)
_CART_COUNT = re.compile(r"view cart\s*·?\s*(\d+)\s+items?", re.IGNORECASE)


def cart_count(screen: Screen) -> int | None:
    for e in screen.elements:
        m = _CART_COUNT.search(f"{e.text} {e.desc}")
        if m:
            return int(m.group(1))
    return None


def search_url(query: str) -> str:
    return SEARCH_URL.format(q=quote(query.strip()))


def _inside(e: Element, box: Element) -> bool:
    x, y = e.center
    x1, y1, x2, y2 = box.bounds
    return x1 <= x <= x2 and y1 <= y <= y2


def cards(screen: Screen) -> list[Card]:
    out: list[Card] = []
    for e in screen.elements:
        m = _CARD.match(e.desc) or _CARD.match(e.text)
        if not m:
            continue
        inner = [x for x in screen.elements if x is not e and _inside(x, e)]
        add = next((x for x in inner if (x.desc or x.text).strip().upper() == "ADD"), None)
        qty = next((int(q.group(1)) for x in inner for q in [_QTY.match(x.desc)] if q), 0)
        size = m.group("size") or next(
            (x.desc for x in inner if x.rid in ("tv_variant", "tv_uom_title")), ""
        )
        out.append(
            Card(m.group("name").strip(), m.group("price"), size.strip(), e, add, qty,
                 any(x.desc == "Ad" for x in inner))
        )  # fmt: skip
    return out


def _size_ml(text: str) -> tuple[str, float] | None:
    m = _SIZE.search(text)
    if not m:
        return None
    unit = re.sub(r"s$", "", m.group(2).lower())
    base, mult = _UNIT.get(unit, (unit, 1))
    return base, float(m.group(1)) * mult


def score(query: str, c: Card) -> float:
    words = _SIZE.sub("", query).strip()
    s = fuzz.token_set_ratio(words.lower(), c.name.lower())
    brand = words.split()[0].lower() if words.split() else ""
    if brand and brand not in c.name.lower():
        s -= 30  # "Amul" asked, "Mother Dairy" shown
    want, have = _size_ml(query), _size_ml(c.size)
    if want and have:
        s += 15 if want == have else -15
    if c.ad:
        s -= 5
    return s


def rank(query: str, cs: list[Card]) -> list[tuple[float, Card]]:
    return sorted(((score(query, c), c) for c in cs), key=lambda t: -t[0])


def is_checkout(screen: Screen) -> bool:
    return any(e.desc == "Checkout" and e.rid == "title" for e in screen.elements)


def cart_items(screen: Screen) -> list[Card]:
    """Items on the checkout screen: cards with a quantity, above "You might also like"."""
    cut = next(
        (e.center[1] for e in screen.elements if "You might also like" in (e.text + e.desc)),
        10**9,
    )
    return [c for c in cards(screen) if c.qty > 0 and c.card.center[1] < cut]


def cart_total(screen: Screen) -> str:
    """Verbatim total, only when the screen shows one next to "TOTAL"."""
    for e in screen.elements:
        if "TOTAL" in e.text and "₹" in e.text:
            return next((p.strip() for p in e.text.split("·") if "₹" in p), "")
    return ""


def _find(screen: Screen, pattern: str) -> Element | None:
    rx = re.compile(pattern, re.IGNORECASE)
    return next(
        (e for e in screen.elements if rx.search(e.text.strip()) or rx.search(e.desc.strip())),
        None,
    )


@dataclass
class Plan:
    """What the helper decided: an action to run, or None to let the model decide."""

    action: Action | None
    why: str = ""
    notes: list[str] = field(default_factory=list)


def next_action(screen: Screen, st: BlinkitState, quantity: int, items_added: int) -> Plan:
    # 1. Known pop-ups: never grant location; pick manual / close.
    manual = _find(screen, r"^select location manually$")
    if manual:
        return Plan(Tap(index=manual.index), "location prompt: choose manual")
    if _find(screen, r"device location not enabled"):
        close = next((e for e in screen.elements if e.rid == "crossButtonContainer"), None)
        if close:
            return Plan(Tap(index=close.index), "close location sheet (saved address is used)")

    # 2. Checkout screen: tap the checkout button (safety turns it into the R3 read-back).
    if is_checkout(screen):
        btn = next(
            (e for e in screen.elements if e.rid in ("tv_action_text", "cv_checkout_container")),
            None,
        )
        return Plan(Tap(index=btn.index), "checkout button") if btn else Plan(None)

    cs = cards(screen)
    if not cs or not st.query:
        return Plan(None)

    # 3. Results screen.
    query = f"{st.query} {st.answer}".strip()
    ranked = rank(query, cs)
    best_score, best = ranked[0]
    view_cart = _find(screen, r"view cart")
    if items_added >= quantity or best.qty > 0:
        if view_cart:
            return Plan(Tap(index=view_cart.index), "item in cart: open cart")
        # A size-picker sheet hides the cart bar: close it to get back to the results.
        return Plan(Back(), "item in cart, sheet open: close it")
    if best.add is None:
        return Plan(None)
    close_call = [c for s, c in ranked[1:4] if best_score - s < CLEAR_MARGIN and c.add]
    if close_call and not st.asked and not st.answer:
        st.asked = True
        options = " या ".join(
            f"{c.name} {c.size} {c.price}".strip() for c in [best, *close_call][:3]
        )
        return Plan(AskUser(question=f"कौन सा चाहिए: {options}?"), "ambiguous products")
    return Plan(Tap(index=best.add.index), f"ADD best match: {best.name} {best.size} {best.price}")


def order_readback(screen: Screen) -> tuple[str, list[str]] | None:
    """R3 read-back from the checkout screen: each cart item verbatim (+ total if shown).
    Returns (spoken text, the verbatim parts for R4 grounding), or None if not a cart."""
    items = cart_items(screen)
    if not items:
        return None
    parts: list[str] = []
    lines = []
    for c in items:
        parts += [c.name, c.price]
        lines.append(f"{c.name} {c.size} ×{c.qty} {c.price}".replace("  ", " "))
    total = cart_total(screen)
    if total:
        parts.append(total)
    shipment = next(
        (m.group(0) for e in screen.elements for m in [_SHIPMENT.search(e.desc or e.text)] if m),
        "",
    )
    shown = sum(c.qty for c in items)
    more = ""
    if shipment and int(_SHIPMENT.search(shipment).group(1)) > shown:
        # Never let a total cover items the user didn't hear about.
        parts.append(shipment)
        more = f" ({shipment}, बाकी आइटम स्क्रीन के नीचे हैं)"
    tail = f" कुल {total}." if total else " कुल रकम इस स्क्रीन पर नहीं दिख रही।"
    return f"कार्ट में: {', '.join(lines)}{more}.{tail} ऑर्डर कर दूँ?", parts
