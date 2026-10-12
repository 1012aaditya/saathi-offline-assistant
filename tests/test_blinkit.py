"""Tests for saathi.blinkit, using element layouts copied from real Blinkit screens (12 Oct)."""

import json
from pathlib import Path

import pytest

from saathi.actions import AskUser, Search, Tap
from saathi.blinkit import (
    BlinkitState,
    cards,
    cart_total,
    next_action,
    order_readback,
    rank,
    search_url,
)
from saathi.config import BLINKIT
from saathi.device import Element, Screen, normalize
from saathi.safety import check

RAW = Path(__file__).parent / "fixtures" / "raw"


def E(i, desc="", text="", rid="", box=(0, 0, 10, 10), cls="View"):
    x1, y1, x2, y2 = box
    return Element(index=i, cls=cls, text=text, desc=desc, rid=rid, clickable=True,
                   editable=False, password=False, bounds=box,
                   center=((x1 + x2) // 2, (y1 + y2) // 2))  # fmt: skip


def results_screen(gold_in_cart=False):
    els = [
        E(0, "Showing results for \"amul milk\"", rid="tv_title", box=(0, 170, 400, 210)),
        # sponsored chocolate card
        E(1, "Cadbury Dairy Milk Milkinis Milk Chocolate Bar is available for ₹36, 34 g",
          box=(0, 240, 330, 850), cls="ViewGroup"),
        E(2, "Ad", rid="bottom_left_overlay", box=(30, 560, 60, 580)),
        E(3, "ADD", rid="tv_title", box=(220, 600, 290, 625)),
        # Amul Gold
        E(4, "Amul Gold Full Cream Milk is available for ₹72, 1 ltr", box=(340, 240, 670, 850),
          cls="ViewGroup"),
        *(
            [E(5, "quantity 1", rid="tv_title", box=(560, 600, 600, 625))]
            if gold_in_cart
            else [E(5, "ADD", rid="tv_title", box=(550, 600, 610, 625))]
        ),
        # Amul Taaza
        E(6, "Amul Taaza Toned Milk is available for ₹59, 1 ltr", box=(680, 240, 1000, 850),
          cls="ViewGroup"),
        E(7, "ADD", rid="tv_title", box=(890, 590, 950, 610)),
        # Mother Dairy 500 ml
        E(8, "Mother Dairy Toned Milk is available for ₹28, 500 ml", box=(1010, 240, 1330, 850),
          cls="ViewGroup"),
        E(9, "ADD", rid="tv_title", box=(1200, 600, 1260, 625)),
        E(10, text="View cart · 1 item", rid="container", box=(900, 930, 1100, 980),
          cls="ViewGroup"),
    ]  # fmt: skip
    return Screen(package=BLINKIT, elements=els, hash="h")


def checkout_screen(button="Place Order", total=True):
    els = [
        E(0, "Checkout", rid="title", box=(100, 60, 180, 85)),
        E(1, "Amul Taaza Toned Milk is available for ₹59", box=(0, 600, 1200, 680),
          cls="ViewGroup"),
        E(2, "Amul Taaza Toned Milk", rid="tv_name", box=(500, 605, 700, 625)),
        E(3, "1 ltr", rid="tv_variant", box=(500, 635, 560, 655)),
        E(4, "quantity 1", rid="tv_title", box=(1100, 615, 1140, 640)),
        E(5, "₹59", rid="total_item_price", box=(1130, 655, 1170, 680)),
        E(6, "You might also like", rid="tv_title", box=(0, 900, 300, 930)),
        E(7, "Table White White Eggs is available for ₹95, 10 pcs", box=(400, 950, 600, 1200),
          cls="ViewGroup"),
        E(8, "ADD", rid="tv_title", box=(520, 1010, 580, 1035)),
        *(
            [E(9, text="₹59 · TOTAL · Place Order", rid="cv_checkout_container",
               box=(400, 1850, 1200, 1900), cls="ViewGroup"),
             E(10, text="Place Order", rid="tv_action_text", box=(780, 1860, 880, 1890))]
            if total
            else [E(9, text=button, rid="cv_checkout_container", box=(0, 1850, 1200, 1900),
                    cls="ViewGroup"),
                  E(10, text=button, rid="tv_action_text", box=(500, 1860, 700, 1890))]
        ),
    ]  # fmt: skip
    return Screen(package=BLINKIT, elements=els, hash="h")


def test_search_url_encodes_query():
    assert search_url("amul milk 1 litre") == "https://blinkit.com/s/?q=amul%20milk%201%20litre"


def test_cards_parse_name_price_size_add_ad_qty():
    cs = {c.name: c for c in cards(results_screen(gold_in_cart=True))}
    assert cs["Amul Taaza Toned Milk"].price == "₹59"
    assert cs["Amul Taaza Toned Milk"].size == "1 ltr"
    assert cs["Amul Taaza Toned Milk"].add.index == 7
    assert cs["Cadbury Dairy Milk Milkinis Milk Chocolate Bar"].ad
    assert cs["Amul Gold Full Cream Milk"].qty == 1 and cs["Amul Gold Full Cream Milk"].add is None


def test_rank_prefers_brand_and_size_over_ads_and_other_brands():
    names = [c.name for _, c in rank("amul milk 1 litre", cards(results_screen()))]
    assert names[:2] == ["Amul Gold Full Cream Milk", "Amul Taaza Toned Milk"] or names[:2] == [
        "Amul Taaza Toned Milk",
        "Amul Gold Full Cream Milk",
    ]
    assert names[-1] in (
        "Mother Dairy Toned Milk",
        "Cadbury Dairy Milk Milkinis Milk Chocolate Bar",
    )


def test_ambiguous_asks_once_then_picks_users_choice():
    st = BlinkitState(query="amul milk 1 litre")
    plan = next_action(results_screen(), st, quantity=1, items_added=0)
    assert isinstance(plan.action, AskUser) and "Amul Taaza" in plan.action.question
    st.answer = "Amul Taaza"
    plan = next_action(results_screen(), st, quantity=1, items_added=0)
    assert plan.action == Tap(index=7)  # ADD on Amul Taaza


def test_clear_winner_adds_without_asking():
    st = BlinkitState(query="mother dairy toned milk 500 ml")
    assert next_action(results_screen(), st, 1, 0).action == Tap(index=9)


def test_after_add_or_already_in_cart_opens_cart():
    st = BlinkitState(query="amul milk 1 litre", answer="gold")
    assert next_action(results_screen(), st, 1, 1).action == Tap(index=10)
    st2 = BlinkitState(query="amul gold milk 1 litre")
    assert next_action(results_screen(gold_in_cart=True), st2, 1, 0).action == Tap(index=10)


def test_checkout_screen_taps_checkout_and_safety_reads_back_items():
    s = checkout_screen()
    plan = next_action(s, BlinkitState(query="x"), 1, 1)
    assert isinstance(plan.action, Tap)
    v = check(plan.action, s)
    assert (v.kind, v.rule) == ("hold", "R3")
    assert "Amul Taaza Toned Milk" in v.readback and "₹59" in v.readback
    assert "Eggs" not in v.readback and "₹95" not in v.readback  # recommendation, not in cart


def test_select_payment_method_readback_without_total_says_so():
    s = checkout_screen(button="Select Payment Method", total=False)
    assert cart_total(s) == ""
    text, _parts = order_readback(s)
    assert "Amul Taaza Toned Milk" in text and "नहीं दिख रही" in text
    assert check(Tap(index=10), s).kind == "hold"


def test_location_prompt_handled_without_granting():
    s = Screen(BLINKIT, [E(0, text="Location permission not enabled"),
                         E(1, text="Enable device location"),
                         E(2, text="Select location manually")], "h")  # fmt: skip
    assert next_action(s, BlinkitState(), 1, 0).action == Tap(index=2)


def test_unknown_screen_defers_to_model():
    s = Screen(BLINKIT, [E(0, text="Something new")], "h")
    assert next_action(s, BlinkitState(query="milk"), 1, 0).action is None


def test_search_action_is_allowed_by_safety():
    assert check(Search(query="amul milk"), results_screen()).kind == "allow"


def test_real_search_link_dump():
    path = RAW / "blinkit_search_link.json"
    if not path.exists():
        pytest.skip("personal dump not present")
    s = normalize(json.loads(path.read_text()), max_elements=400)
    cs = cards(Screen(BLINKIT, s, "h"))
    assert any("Amul Taaza" in c.name and c.add for c in cs)
    assert all(c.price.startswith("₹") for c in cs)


def test_cart_counting_uses_cart_bar_not_taps():
    from saathi.blinkit import cart_count

    st = BlinkitState(query="amul milk")
    s = results_screen()  # "View cart · 1 item"
    assert cart_count(s) == 1
    st.observe_cart(s)
    assert (st.cart_start, st.added) == (1, 0)
    s.elements[10] = s.elements[10].model_copy(update={"text": "View cart · 2 items"})
    st.observe_cart(s)
    assert st.added == 1
    sheet = Screen(BLINKIT, [E(0, "Amul Taaza Toned Milk is available for ₹59, 1 ltr")], "h")
    st.observe_cart(sheet)  # no cart bar on the size picker: keep the last count
    assert st.added == 1


def test_size_picker_sheet_picks_requested_size():
    path = RAW / "blinkit_variant_sheet.json"
    if not path.exists():
        pytest.skip("personal dump not present")
    s = Screen(BLINKIT, normalize(json.loads(path.read_text())), "h")
    st = BlinkitState(query="amul taaza milk 1 litre", asked=True)
    plan = next_action(s, st, 1, 0)
    target = s.elements[plan.action.index]
    assert target.desc == "ADD" and target.center[0] > 1100  # the 1 ltr card, not 500 ml


def test_sheet_with_item_in_cart_closes_sheet():
    from saathi.actions import Back

    sheet = Screen(BLINKIT, [
        E(0, "Amul Taaza Toned Milk is available for ₹59, 1 ltr", box=(1000, 800, 1350, 1150),
          cls="ViewGroup"),
        E(1, "quantity 1", rid="tv_title", box=(1230, 970, 1280, 990)),
    ], "h")  # fmt: skip
    st = BlinkitState(query="amul milk 1 litre", answer="Amul Taaza", asked=True)
    assert next_action(sheet, st, 1, 0).action == Back()


def test_readback_flags_items_not_on_screen():
    s = checkout_screen()
    s.elements.append(E(11, "Shipment of 2 items", rid="subtitle", box=(100, 520, 300, 550)))
    text, parts = order_readback(s)
    assert "Shipment of 2 items" in text and "Shipment of 2 items" in parts
    s1 = checkout_screen()
    s1.elements.append(E(11, "Shipment of 1 item", rid="subtitle", box=(100, 520, 300, 550)))
    assert "Shipment" not in order_readback(s1)[0]
