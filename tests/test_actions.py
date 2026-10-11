"""Unit tests for saathi.actions schema validation."""

import pytest

from saathi.actions import Done, Fail, OpenApp, Tap, TypeText, parse_decision


def raw(type_, index=None, text=None, direction=None, app=None, say="", thought=""):
    return {
        "thought": thought,
        "say": say,
        "action": {"type": type_, "index": index, "text": text, "direction": direction, "app": app},
    }


def test_tap():
    d = parse_decision(raw("tap", index=4, say="राहुल को ढूंढ रहा हूँ"))
    assert isinstance(d.action, Tap) and d.action.index == 4 and d.say


def test_type_with_and_without_index():
    assert parse_decision(raw("type", text="Mummy", index=2)).action == TypeText(
        text="Mummy", index=2
    )
    assert parse_decision(raw("type", text="Mummy")).action.index is None


def test_text_maps_to_summary_and_reason():
    assert parse_decision(raw("done", text="ok")).action == Done(summary="ok")
    assert parse_decision(raw("fail", text="no")).action == Fail(reason="no")


def test_open_app_accepts_app_or_text():
    assert parse_decision(raw("open_app", app="whatsapp")).action == OpenApp(app="whatsapp")
    assert parse_decision(raw("open_app", text="whatsapp")).action == OpenApp(app="whatsapp")


@pytest.mark.parametrize(
    "bad",
    [
        raw("tap"),  # missing index
        raw("tap", index=-1),
        raw("type"),  # missing text
        raw("scroll", direction="left"),
        raw("done"),  # missing summary
        raw("explode"),
        {"say": "x"},  # no action
    ],
)
def test_invalid_outputs_raise(bad):
    with pytest.raises(ValueError):
        parse_decision(bad)
