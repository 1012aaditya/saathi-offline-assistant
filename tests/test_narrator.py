"""Unit tests for saathi.narrator (no network)."""

import pytest

from saathi.narrator import Dedup, clip, needs_transliteration


@pytest.mark.parametrize(
    "line,expected",
    [
        ("WhatsApp khol raha hoon...", True),
        ('Mummy ka last message: "achaa"', True),
        ('Mummy का आखिरी संदेश: "achaa"', False),  # already Devanagari; names stay as written
        ("WhatsApp खोल रहा हूँ", False),
        ("", False),
        ("12345", False),
    ],
)
def test_needs_transliteration(line, expected):
    assert needs_transliteration(line) is expected


def test_clip_caps_progress_lines_at_12_words():
    long = " ".join(str(i) for i in range(20))
    assert len(clip(long).split()) == 12
    assert clip("छोटी लाइन") == "छोटी लाइन"


def test_dedup_skips_repeats_and_empty():
    d = Dedup()
    assert d.should_say("WhatsApp खोल रहा हूँ।")
    assert not d.should_say("WhatsApp खोल रहा हूँ")
    assert not d.should_say("  ")
    assert d.should_say("Mummy की चैट खोल रहा हूँ")
