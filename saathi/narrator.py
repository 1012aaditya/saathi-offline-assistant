"""Short Hindi status lines (Devanagari, at most 12 words), rate limited.

Bulbul's docs: romanized Hindi "significantly reduces output quality", and gpt-5-nano writes
Roman Hinglish about half the time despite the prompt. So any line without Devanagari is
transliterated en-IN -> hi-IN (Sarvam /transliterate) before it is spoken. Deterministic,
not left to the model.
"""

import re

_DEVANAGARI = re.compile(r"[ऀ-ॿ]")
_LATIN = re.compile(r"[A-Za-z]")
MAX_WORDS = 12


def needs_transliteration(line: str) -> bool:
    """True for Roman-only lines ("WhatsApp khol raha hoon"). Lines that already contain
    Devanagari are left alone, so English names inside Hindi text stay as written."""
    return bool(_LATIN.search(line)) and not _DEVANAGARI.search(line)


def clip(line: str, max_words: int = MAX_WORDS) -> str:
    """Progress lines are capped at 12 words (FR-V3). Summaries/read-backs are not clipped."""
    words = line.split()
    return line if len(words) <= max_words else " ".join(words[:max_words])


class Dedup:
    """Skip a progress line identical to the previous one (rate limiting, FR-A4)."""

    def __init__(self) -> None:
        self._last = ""

    def should_say(self, line: str) -> bool:
        key = line.strip().rstrip("।.!").lower()
        if not key or key == self._last:
            return False
        self._last = key
        return True
