"""Unit tests for eval scoring (no device)."""

import sys
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "eval"))

from run_flows import _cost, _last_received, score

STEPS = [
    {
        "elements": [
            '[3] TextView "khana kha liya?" desc="" rid=message_text (300,900) from=them',
            '[4] TextView "haan" desc="" rid=message_text (900,1000) from=me',
            '[5] TextView "aa jao jaldi" desc="" rid=message_text (300,1100) from=them',
        ],
        "llm": {"model": "gpt-5-nano", "tokens_in": 1_000_000, "tokens_out": 0},
    }
]


def res(reached, message="", status="done"):
    return SimpleNamespace(reached=reached, message=message, status=status)


def test_last_received_is_lowest_from_them():
    assert _last_received(STEPS) == "aa jao jaldi"


def test_score_quotes_last_received():
    flow = {"success": {"reached": "done", "quotes_last_received": True}}
    assert score(flow, res("done", 'Mummy का आखिरी संदेश: "aa jao jaldi"'), STEPS) == (True, "")
    ok, why = score(flow, res("done", 'Mummy का आखिरी संदेश: "haan"'), STEPS)
    assert not ok and "quote" in why


def test_score_reached_mismatch():
    ok, why = score({"success": {"reached": "order_hold"}}, res("halt", status="halt"), STEPS)
    assert not ok and "halt" in why


def test_score_contains_any():
    flow = {"success": {"summary_contains_any": ["WhatsApp", "चैट"]}}
    assert score(flow, res("done", "यह WhatsApp की चैट सूची है"), [])[0]
    assert not score(flow, res("done", "यह होम स्क्रीन है"), [])[0]


def test_cost():
    assert abs(_cost(STEPS) - 0.05) < 1e-9
