"""Tests for saathi.session: spoken controls and follow-ups."""

import pytest

from saathi.session import Session, contact_in, control


@pytest.mark.parametrize(
    "text,kind",
    [
        ("phir se bolo", "repeat"),
        ("फिर से बोलो", "repeat"),
        ("dheere bolo", "slower"),
        ("धीरे बोलिए", "slower"),
        ("tez bolo", "faster"),
        ("madad", "help"),
        ("kya kar sakte ho", "help"),
        ("bas", "quit"),
        ("बस", "quit"),
        ("band karo", "quit"),
        # tasks are not controls
        ("Mummy ko bolo jaldi ghar aao", None),
        ("WhatsApp kholo aur Mummy ka last message padho", None),
        ("bas stop pe utar jaana Mummy ko bolo", None),
        ("", None),
    ],
)
def test_control(text, kind):
    assert control(text) == kind


@pytest.mark.parametrize(
    "cmd,name",
    [
        ("WhatsApp kholo aur Mummy ka last message padho", "Mummy"),
        ("Rahul ko reply karo: ok", "Rahul"),
        ("राहुल को एक रुपया भेजो", "राहुल"),
        ("Screen pe kya hai?", None),
        ("Kiske message aaye hain?", None),
    ],
)
def test_contact_in(cmd, name):
    assert contact_in(cmd) == name


def test_follow_ups_reuse_last_contact():
    s = Session()
    s.remember("WhatsApp kholo aur Mummy ka last message padho", 'Mummy का आखिरी संदेश: "aa jao"')
    assert s.expand("reply karo: main aa raha hoon") == "Mummy ko reply karo: main aa raha hoon"
    assert s.expand("aur pichle message padho") == "WhatsApp par Mummy ke pichle message padho"
    assert s.expand("Rahul ko reply karo: ok") == "Rahul ko reply karo: ok"  # names someone else
    assert s.expand("Screen pe kya hai?") == "Screen pe kya hai?"
    assert s.last_answer.startswith("Mummy")


def test_no_contact_no_change():
    assert Session().expand("reply karo: hi") == "reply karo: hi"


def test_pace_bounds():
    s = Session()
    for _ in range(10):
        s.adjust_pace(faster=True)
    assert s.pace == 1.6
    for _ in range(10):
        s.adjust_pace(faster=False)
    assert s.pace == 0.6
