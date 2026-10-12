"""Tests for the UI tree normalizer in saathi.device.

Synthetic trees run everywhere; real-dump checks skip when tests/fixtures/raw/ (gitignored,
personal screens) isn't present.
"""

import json
from pathlib import Path

import pytest

from saathi.device import foreground_package, normalize

RAW = Path(__file__).parent / "fixtures" / "raw"


def node(cls="TextView", text="", desc="", clickable=False, bounds=(0, 0, 100, 50), **extra):
    l, t, r, b = bounds
    return {
        "className": f"android.widget.{cls}",
        "text": text,
        "contentDescription": desc,
        "resourceId": extra.pop("rid", ""),
        "boundsInScreen": {"left": l, "top": t, "right": r, "bottom": b},
        "isClickable": clickable,
        "isVisibleToUser": extra.pop("visible", True),
        "children": extra.pop("children", []),
        **extra,
    }


def tree(*children, pkg="com.example"):
    return {
        "a11y_tree": node("FrameLayout", bounds=(0, 0, 1200, 2000), children=list(children)),
        "phone_state": {"packageName": pkg},
    }


def test_basic_fields_and_center():
    els = normalize(
        tree(
            node(
                "Button",
                text="Pay",
                clickable=True,
                bounds=(500, 1800, 580, 1840),
                rid="com.x:id/pay_btn",
            )
        )
    )
    assert len(els) == 1
    e = els[0]
    assert (e.index, e.cls, e.text, e.rid, e.clickable) == (0, "Button", "Pay", "pay_btn", True)
    assert e.center == (540, 1820)
    assert e.to_line() == '[0] Button "Pay" desc="" rid=pay_btn (540,1820)'


def test_drops_invisible_zero_area_and_empty_nodes():
    els = normalize(
        tree(
            node(text="hidden", visible=False),
            node(text="flat", bounds=(10, 10, 10, 50)),
            node("View"),  # empty, not clickable
            node(text="kept"),
        )
    )
    assert [e.text for e in els] == ["kept"]


def test_strips_icon_font_glyphs():
    els = normalize(tree(node(text=""), node(text=" Sort")))
    assert [e.text for e in els] == ["Sort"]


def test_unlabeled_clickable_borrows_child_text():
    card = node("FrameLayout", clickable=True, children=[node(text="Amul Taaza"), node(text="₹28")])
    els = normalize(tree(card))
    assert els[0].text == "Amul Taaza · ₹28"


def test_unlabeled_wrapper_of_buttons_is_dropped():
    wrapper = node(
        "ViewGroup", clickable=True, children=[node("Button", text="Sort", clickable=True)]
    )
    assert [e.text for e in normalize(tree(wrapper))] == ["Sort"]


def test_hint_is_not_user_text():
    field = node("EditText", text="Search", hint="Search", isEditable=True, isShowingHintText=True)
    e = normalize(tree(field))[0]
    assert (e.text, e.desc, e.editable) == ("", "Search", True)


def test_password_flag_both_formats():
    portal = normalize(tree(node("EditText", isEditable=True, isPassword=True)))
    adb = normalize(tree({**node("EditText"), "password": True}))
    assert portal[0].password and adb[0].password and adb[0].editable


def test_uiautomator_fallback_keys():
    n = node("Button", text="OK")
    n.pop("isClickable")
    n.pop("isVisibleToUser")
    n["clickable"] = True
    assert normalize(tree(n))[0].clickable


def test_cap():
    els = normalize(tree(*[node(text=str(i)) for i in range(300)]), max_elements=150)
    assert len(els) == 150 and els[-1].index == 149


def test_foreground_package():
    assert foreground_package(tree(pkg="com.whatsapp")) == "com.whatsapp"


@pytest.mark.parametrize(
    "name,pkg",
    [
        ("whatsapp_home", "com.whatsapp"),
        ("whatsapp_chat", "com.whatsapp"),
        ("whatsapp_popup_notifications", "com.whatsapp"),
        ("blinkit_search", "com.grofers.customerapp"),
        ("whatsapp_home_uiautomator", "com.whatsapp"),
    ],
)
def test_real_dumps(name, pkg):
    path = RAW / f"{name}.json"
    if not path.exists():
        pytest.skip(f"{path} not present (personal dumps are gitignored)")
    raw = json.loads(path.read_text())
    els = normalize(raw)
    assert foreground_package(raw) == pkg
    assert 0 < len(els) <= 150
    assert all(e.bounds[2] > e.bounds[0] for e in els)
    assert not any("" <= ch <= "" for e in els for ch in e.text + e.desc)


def _row(msg: str, sent: bool, y: int) -> dict:
    children = [node(text=msg, rid="com.whatsapp:id/message_text", bounds=(100, y, 900, y + 40))]
    if sent:
        children.append(
            node(desc="Delivered", rid="com.whatsapp:id/status", bounds=(950, y, 990, y + 40))
        )
    return node(
        "ViewGroup",
        rid="com.whatsapp:id/conversation_row_text",
        bounds=(0, y, 1200, y + 50),
        children=children,
    )


def test_whatsapp_messages_tagged_by_sender():
    els = normalize(
        tree(_row("khana kha liya?", sent=False, y=100), _row("achaa", sent=True, y=200))
    )
    msgs = {e.text: e.sender for e in els if e.rid == "message_text"}
    assert msgs == {"khana kha liya?": "them", "achaa": "me"}
    line = next(e for e in els if e.text == "achaa").to_line()
    assert line.endswith("from=me")
    assert all(e.sender == "" for e in els if e.rid != "message_text")
