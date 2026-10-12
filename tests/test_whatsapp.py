"""Tests for saathi.whatsapp (unread summary), layouts copied from the real chat list."""

import pytest

from saathi.actions import Back, Done, OpenApp, Tap
from saathi.config import WHATSAPP
from saathi.device import Element, Screen
from saathi.safety import check
from saathi.whatsapp import UnreadState, is_unread_query, summary_text, unread_next, unread_rows


def E(i, text="", desc="", rid="", y=0, x=(100, 600)):
    return Element(index=i, cls="View", text=text, desc=desc, rid=rid, clickable=True,
                   editable=False, password=False, bounds=(x[0], y, x[1], y + 38),
                   center=((x[0] + x[1]) // 2, y + 19))  # fmt: skip


def chat_list(filter_selected=False):
    state = "selected" if filter_selected else "unselected"
    return Screen(WHATSAPP, [
        E(0, desc="Ask Meta AI or Search", rid="search_bar_inner_layout", y=100),
        E(1, desc=f"Unread filter, 99+, {state}", rid="conversations_filter_debug_view_id_unread", y=200),
        E(9, desc=f"All filter, , {'unselected' if filter_selected else 'selected'}", y=200, x=(10, 90)),
        E(2, text="Mummy", rid="conversations_row_contact_name", y=438),
        E(3, text="khana kha liya?", rid="single_msg_tv", y=482),
        E(4, desc="3 unread messages", rid="conversations_row_message_count", y=482, x=(1100, 1140)),
        E(5, text="Rahul", rid="conversations_row_contact_name", y=551),
        E(6, text="ok", rid="single_msg_tv", y=595),
        E(7, text="College Group", rid="conversations_row_contact_name", y=666),
        E(8, desc="23 unread messages", rid="conversations_row_message_count", y=710, x=(1100, 1140)),
    ], "h")  # fmt: skip


@pytest.mark.parametrize(
    "cmd,yes",
    [
        ("Kiske message aaye hain?", True),
        ("किसके मैसेज आए हैं?", True),
        ("naye message batao", True),
        ("WhatsApp kholo aur Mummy ka last message padho", False),
        ("Screen pe kya hai?", False),
    ],
)
def test_is_unread_query(cmd, yes):
    assert is_unread_query(cmd) is yes


def test_unread_rows_pair_counts_with_names():
    assert unread_rows(chat_list()) == [("Mummy", 3), ("College Group", 23)]


def test_summary_text():
    assert summary_text([]) == "अभी कोई नया संदेश नहीं है।"
    assert summary_text([("Mummy", 3)]) == "1 चैट में नए संदेश हैं: Mummy के 3।"


def test_flow_opens_filters_reads_restores():
    st = UnreadState()
    other = Screen("com.sec.android.app.launcher", [E(0, text="Chrome")], "h")
    assert unread_next(other, st) == OpenApp(app="whatsapp")
    inside_chat = Screen(WHATSAPP, [E(0, text="Mummy", rid="conversation_contact_name")], "h")
    assert unread_next(inside_chat, st) == Back()
    assert unread_next(chat_list(), st) == Tap(index=1)  # turn the Unread filter on
    a = unread_next(chat_list(filter_selected=True), st)
    assert a == Tap(index=9) and "Mummy के 3" in st.summary  # read, then tap "All" to restore
    done = unread_next(chat_list(), st)
    assert isinstance(done, Done) and done.summary == st.summary


def test_filter_already_on_is_left_on():
    st = UnreadState()
    done = unread_next(chat_list(filter_selected=True), st)
    assert isinstance(done, Done)


def test_every_scripted_step_passes_safety():
    s = chat_list()
    assert check(Tap(index=1), s).kind == "allow"
