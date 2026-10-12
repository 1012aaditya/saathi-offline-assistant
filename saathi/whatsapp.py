"""Deterministic WhatsApp helpers: "who has unread messages?".

Built against the real chat list (fixture whatsapp_home, 12 Oct): each row has
rid=conversations_row_contact_name and, if unread, rid=conversations_row_message_count with
desc "<N> unread messages" ~45 px below the name. The "Unread" filter chip
(rid=conversations_filter_debug_view_id_unread, desc "Unread filter, 99+, unselected") shows
only unread chats.

unread_next() drives: open WhatsApp → back out to the chat list → turn the Unread filter on →
read names + counts verbatim → turn the filter off again (leave WhatsApp as it was) → done.
Every action still goes through safety.check() in the agent.
"""

import re
from dataclasses import dataclass

from saathi.actions import Action, Back, Done, OpenApp, Tap
from saathi.config import WHATSAPP
from saathi.device import Element, Screen

UNREAD_QUERY = re.compile(
    r"(?i)kis(ke|ka|ki|se)?\b.*\b(message|msg|sandesh)|unread|naye? (message|sandesh)|"
    r"किस(के|का|की|से).*(संदेश|मैसेज)|नए (संदेश|मैसेज)|who (has )?messaged"
)
_COUNT = re.compile(r"(\d+)\s+unread", re.IGNORECASE)
FILTER_RID = "conversations_filter_debug_view_id_unread"
MAX_SPOKEN = 6


def is_unread_query(command: str) -> bool:
    return bool(UNREAD_QUERY.search(command))


@dataclass
class UnreadState:
    filter_turned_on: bool = False  # we switched the filter on and must switch it off
    summary: str = ""


def _filter(screen: Screen) -> Element | None:
    return next((e for e in screen.elements if e.rid == FILTER_RID), None)


def _filter_selected(e: Element) -> bool:
    return "unselected" not in e.desc.lower() and "selected" in e.desc.lower()


def unread_rows(screen: Screen) -> list[tuple[str, int]]:
    """(contact name, unread count) for visible rows, top to bottom, names verbatim."""
    names = [e for e in screen.elements if e.rid == "conversations_row_contact_name" and e.text]
    rows: list[tuple[str, int]] = []
    for c in screen.elements:
        if c.rid != "conversations_row_message_count":
            continue
        m = _COUNT.search(c.desc or c.text)
        if not m:
            continue
        above = [n for n in names if n.bounds[1] <= c.bounds[1] and c.bounds[1] - n.bounds[1] < 140]
        if above:
            name = max(above, key=lambda n: n.bounds[1])
            rows.append((name.text, int(m.group(1))))
    return rows


def summary_text(rows: list[tuple[str, int]]) -> str:
    if not rows:
        return "अभी कोई नया संदेश नहीं है।"
    spoken = ", ".join(f"{name} के {n}" for name, n in rows[:MAX_SPOKEN])
    more = f", और {len(rows) - MAX_SPOKEN} चैट" if len(rows) > MAX_SPOKEN else ""
    return f"{len(rows)} चैट में नए संदेश हैं: {spoken}{more}।"


def _on_chat_list(screen: Screen) -> bool:
    return screen.package == WHATSAPP and any(
        e.rid in ("search_bar_inner_layout", FILTER_RID) for e in screen.elements
    )


def unread_next(screen: Screen, st: UnreadState) -> Action | None:
    if screen.package != WHATSAPP:
        return OpenApp(app="whatsapp")
    if not _on_chat_list(screen):
        return Back()  # inside a chat / contact info / search: back out to the list
    chip = _filter(screen)
    if st.summary:  # already read: restore the filter, then finish
        if st.filter_turned_on and chip is not None and _filter_selected(chip):
            st.filter_turned_on = False
            return Tap(index=chip.index)
        return Done(summary=st.summary)
    if chip is not None and not _filter_selected(chip):
        st.filter_turned_on = True
        return Tap(index=chip.index)
    st.summary = summary_text(unread_rows(screen))
    return unread_next(screen, st)
