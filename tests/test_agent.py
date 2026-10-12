"""Unit tests for pure helpers in saathi.agent (no device, no network)."""

import pytest

from saathi.agent import is_screen_summary, needs_vision
from saathi.device import Element, Screen


@pytest.mark.parametrize(
    "cmd,expected",
    [
        ("Screen pe kya hai?", True),
        ("screen par kya likha hai", True),
        ("स्क्रीन पर क्या है?", True),
        ("What's on the screen", True),
        ("WhatsApp kholo aur Mummy ka last message padho", False),
        ("Blinkit se Amul doodh mangao", False),
    ],
)
def test_is_screen_summary(cmd, expected):
    assert is_screen_summary(cmd) is expected


def _screen(n_labeled: int, package: str = "com.whatsapp") -> Screen:
    els = [
        Element(
            index=i,
            cls="Button",
            text=f"b{i}",
            desc="",
            rid="",
            clickable=True,
            editable=False,
            password=False,
            bounds=(0, 0, 10, 10),
            center=(5, 5),
        )
        for i in range(n_labeled)
    ]
    return Screen(package=package, elements=els, hash="h")


def test_vision_policy():
    assert needs_vision(None, True, True)  # look requested
    assert needs_vision(_screen(10), False, False)  # last action changed nothing
    assert needs_vision(_screen(3), True, False)  # unlabeled UI
    assert not needs_vision(_screen(10), True, False)
    assert needs_vision(_screen(10, "com.phonepe.app"), True, False)  # VISION_APPS
